package ru.importer.notes.kp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

@Service
public class KpNotesImporter {

    private static final Logger log = LoggerFactory.getLogger(KpNotesImporter.class);

    private static final Pattern EN_TITLE_IN_PARENS = Pattern.compile("^.+?\\((.+?)\\)$");
    private static final Pattern FILM_ID_PATTERN = Pattern.compile("/(?:film|series)/(\\d+)");
    private static final int MAX_PAGES = 300;
    private static final String VOTES_URL = "https://www.kinopoisk.ru/user/%d/movies/voted-watched/";
    private static final String VOTES_URL_PAGE = "https://www.kinopoisk.ru/user/%d/movies/voted-watched/?page=%d";
    private static final Pattern YEAR_IN_TEXT = Pattern.compile("\\((\\d{4})\\)");
    private static final Pattern PLAIN_YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    /**
     * Доставляет оригинальные названия для фильмов, у которых их нет: открывает
     * страницу каждого фильма и берёт span[class*="originalTitle"] под русским названием.
     * CSS-module суффикс (__nZWQK и т.п.) меняется между релизами, поэтому ищем по подстроке класса.
     */
    public void fetchOriginalTitles(List<MovieData> movies, WebDriver driver, ImportProgress progress) {
        List<MovieData> missing = new ArrayList<>();
        for (MovieData m : movies) {
            if (m.getNameEn() == null || m.getNameEn().isBlank()) {
                missing.add(m);
            }
        }
        if (missing.isEmpty()) {
            log.info("Оригинальные названия есть у всех фильмов, проход не нужен");
            return;
        }
        log.info("Загружаю оригинальные названия: {} фильмов", missing.size());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));

        int done = 0;
        for (MovieData movie : missing) {
            if (progress != null && progress.isAborted()) {
                log.info("Остановлен пользователем: загружено оригиналов {}/{}", done, missing.size());
                break;
            }
            try {
                String url = movie.getKpUrl() != null ? movie.getKpUrl()
                        : (movie.getKpId() != null ? "https://www.kinopoisk.ru/film/" + movie.getKpId() + "/" : null);
                if (url == null) {
                    continue;
                }
                driver.get(url);
                String original = pollOriginalTitle(driver);
                if (original != null) {
                    movie.setNameEn(original);
                    log.info("Оригинал [{}/{}]: '{}' -> '{}'", done + 1, missing.size(), movie.getName(), original);
                } else {
                    log.warn("Оригинал не найден [{}/{}]: '{}' ({})", done + 1, missing.size(), movie.getName(), url);
                }
            } catch (Exception e) {
                log.warn("Не удалось получить оригинал [{}/{}]: '{}': {}", done + 1, missing.size(), movie.getName(), e.getMessage());
            }
            done++;
            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, movie.getName(), "original " + done + "/" + missing.size());
            }
        }
        log.info("Загрузка оригинальных названий завершена");
    }

    private String pollOriginalTitle(WebDriver driver) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            try {
                for (WebElement el : driver.findElements(By.cssSelector("span[class*=\"originalTitle\"]"))) {
                    String text = el.getText().trim();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * Читает общее количество оценок пользователя со страницы оценок КП.
     * Счётчик: в футере есть ссылка a[href*="movies/voted-watched"], внутри неё
     * первый span[class*="statValue"] — число оценок.
     *
     * @return количество оценок или null, если страница не загрузилась / счётчик не виден
     */
    public Integer fetchTotalRatings(WebDriver driver, Long userId) {
        try {
            driver.get(String.format(VOTES_URL, userId));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Document doc = Jsoup.parse(driver.getPageSource());
                    Integer total = parseTotalRatings(doc);
                    if (total != null) {
                        log.info("Всего оценок на КП у пользователя {}: {}", userId, total);
                        return total;
                    }
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("Не удалось прочитать счётчик оценок КП: страница не загрузилась или счётчик не виден");
        } catch (Exception e) {
            log.warn("Не удалось прочитать счётчик оценок КП: {}", e.getMessage());
        }
        return null;
    }

    /** Парсит число оценок из футера страницы оценок КП. package-private для тестов. */
    Integer parseTotalRatings(Document doc) {
        for (Element link : doc.select("a[href*='movies/voted-watched']")) {
            Element value = link.selectFirst("span[class*='statValue']");
            if (value != null) {
                String text = value.text().trim().replaceAll("\\D", "");
                if (!text.isEmpty()) {
                    try {
                        return Integer.parseInt(text);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private String extractEnglishTitle(String fullText) {
        String stripped = fullText.replaceAll("\\(\\d{4}(-\\d{2})?\\)\\s*$", "").trim();
        Matcher m = EN_TITLE_IN_PARENS.matcher(stripped);
        if (m.matches()) {
            String candidate = m.group(1).trim();
            if (candidate.matches("\\d{4}") || candidate.matches("\\d{4}-\\d{2}")) {
                return null;
            }
            return candidate;
        }
        return null;
    }

    private String extractRussianTitle(String fullText) {
        int idx = fullText.indexOf(" (");
        if (idx > 0) {
            return fullText.substring(0, idx).trim();
        }
        return fullText;
    }

    private int extractYear(String text) {
        Matcher m = YEAR_IN_TEXT.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private Element findContainer(Element filmLink) {
        Element parent = filmLink.parent();
        for (int i = 0; i < 8 && parent != null; i++) {
            String tag = parent.tagName();
            if ("body".equals(tag) || "html".equals(tag)) {
                break;
            }
            if (("div".equals(tag) || "li".equals(tag) || "tr".equals(tag)) && isRichItemContainer(parent)) {
                return parent;
            }
            parent = parent.parent();
        }

        parent = filmLink.parent();
        for (int i = 0; i < 5 && parent != null; i++) {
            String tag = parent.tagName();
            if ("div".equals(tag) || "tr".equals(tag) || "li".equals(tag)) {
                return parent;
            }
            parent = parent.parent();
        }
        return null;
    }

    private boolean isRichItemContainer(Element el) {
        if (el.children().size() < 2) {
            return false;
        }
        if (spansMultipleFilms(el)) {
            return false;
        }
        for (Element a : el.select("a[href*=/film/], a[href*=/series/]")) {
            if (!a.text().isBlank()) {
                return true;
            }
        }
        return !el.select("[class*=\"name\"], [class*=\"Name\"], .title, [class*=\"title\"], " +
                "span.year, [class*=\"year\"], [class*=\"value\"], [class*=\"rating\"], [class*=\"Rating\"]").isEmpty();
    }

    private boolean spansMultipleFilms(Element el) {
        Set<Long> ids = new HashSet<>();
        for (Element a : el.select("a[href*=/film/], a[href*=/series/]")) {
            Matcher m = FILM_ID_PATTERN.matcher(a.attr("href"));
            if (m.find()) {
                ids.add(Long.parseLong(m.group(1)));
                if (ids.size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    static final int PER_PAGE = 20;

    public List<MovieData> getNotes(WebDriver driver, Long userId, ImportProgress progress) {
        return getNotes(driver, userId, progress, 1);
    }

    /**
     * Сканирует страницы оценок КП, начиная с {@code startPage} (для гибридного способа:
     * первые страницы уже получены из API, остальные — сюда).
     */
    public List<MovieData> getNotes(WebDriver driver, Long userId, ImportProgress progress, int startPage) {
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        List<MovieData> result = new ArrayList<>();
        log.info("Начало сканирования оценок КП, пользователь {}, страницы с {}", userId, startPage);

        for (int page = startPage; page <= MAX_PAGES; page++) {
            if (progress != null && progress.isAborted()) {
                log.info("Импорт остановлен пользователем на странице {}", page);
                break;
            }

            String url = String.format(VOTES_URL_PAGE, userId, page);
            log.info("Сканирую страницу {}...", page);
            driver.get(url);

            List<MovieData> pageMovies;
            try {
                Document doc = waitForPageLoad(driver);

                if (page == 1) {
                    try {
                        Files.write(Paths.get("kp-debug-page-1.html"),
                                driver.getPageSource().getBytes(StandardCharsets.UTF_8));
                        log.info("Дамп HTML первой страницы сохранён в kp-debug-page-1.html");
                    } catch (IOException e) {
                        log.warn("Не удалось сохранить дамп HTML: {}", e.getMessage());
                    }
                }

                pageMovies = parseDocument(doc);
                result.addAll(pageMovies);

                log.info("Страница {} успешно просканирована: найдено {} фильмов, распарсено {}",
                        page, pageMovies.size(), pageMovies.size());
            } catch (Exception e) {
                log.error("Ошибка при сканировании страницы {}: {}", page, e.getMessage(), e);
                continue;
            }

            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, "Страница " + page, "kp");
            }

            if (pageMovies.size() < PER_PAGE) {
                log.info("На странице {} меньше {} фильмов — это последняя страница", page, PER_PAGE);
                break;
            }
        }

        log.info("Сканирование завершено. Всего найдено фильмов: {}", result.size());
        return result;
    }

    public List<MovieData> getNotes(Long userId) {
        List<MovieData> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<MovieData> pageMovies = parsePage(userId, page);
            result.addAll(pageMovies);
            if (pageMovies.size() < PER_PAGE) {
                break;
            }
        }
        return result;
    }

    List<MovieData> parseDocument(Document doc) {
        Map<Long, MovieData> byId = new LinkedHashMap<>();
        List<Long> order = new ArrayList<>();

        Elements filmLinks = doc.select("a[href*='/film/'], a[href*='/series/']");
        for (Element link : filmLinks) {
            Matcher m = FILM_ID_PATTERN.matcher(link.attr("href"));
            if (!m.find()) {
                continue;
            }

            Long filmId = Long.parseLong(m.group(1));
            Element container = findContainer(link);

            MovieData parsed = parseItem(container != null ? container : link, filmId);
            if (parsed == null) {
                continue;
            }

            if (!byId.containsKey(filmId)) {
                byId.put(filmId, parsed);
                order.add(filmId);
            } else {
                merge(byId.get(filmId), parsed);
            }
        }

        List<MovieData> movies = new ArrayList<>();
        for (Long id : order) {
            movies.add(byId.get(id));
        }

        if (movies.isEmpty()) {
            Elements items = doc.select("[class*=\"vote\"]");
            if (items.isEmpty()) {
                items = doc.select("div.item, tr.vote");
            }
            for (Element item : items) {
                MovieData movie = parseItem(item, null);
                if (movie != null) {
                    movies.add(movie);
                }
            }
        }

        return movies;
    }

    private void merge(MovieData target, MovieData source) {
        if ((target.getName() == null || target.getName().isBlank())
                && source.getName() != null && !source.getName().isBlank()) {
            target.setName(source.getName());
        }
        if (target.getNameEn() == null && source.getNameEn() != null) {
            target.setNameEn(source.getNameEn());
        }
        if (target.getYear() == 0 && source.getYear() > 0) {
            target.setYear(source.getYear());
        }
        if (target.getKpRating() == 0 && source.getKpRating() > 0) {
            target.setKpRating(source.getKpRating());
        }
    }

    MovieData parseItem(Element item, Long filmId) {
        String nameRus = null;
        String nameEn = null;
        int year = 0;
        int rating = 0;

        Element nameEl = findNameElement(item);
        if (nameEl != null) {
            String fullText = nameEl.text().trim();
            if (filmId == null) {
                Matcher m = FILM_ID_PATTERN.matcher(nameEl.attr("href"));
                if (m.find()) {
                    filmId = Long.parseLong(m.group(1));
                }
            }

            nameRus = extractRussianTitle(fullText);
            nameEn = extractEnglishTitle(fullText);
            year = extractYear(fullText);
        }

        if (filmId == null && nameRus == null) {
            return null;
        }

        Element yearEl = item.select("span.year, [class*=\"year\"], .info .year, .userFilm__year, [class*=\"Year\"]").first();
        if (yearEl != null) {
            try {
                year = Integer.parseInt(yearEl.text().trim().replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        if (year == 0) {
            Matcher ym = PLAIN_YEAR.matcher(item.text());
            if (ym.find()) {
                try {
                    year = Integer.parseInt(ym.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (nameRus != null) {
            nameRus = stripGenreTail(nameRus);
            nameRus = stripYearFromName(nameRus, year);
        }

        Element ratingEl = item.select("span.value, [class*=\"value\"], .vote .value, .rating, [class*=\"ratingValue\"], [class*=\"Rating\"]").first();
        if (ratingEl != null) {
            try {
                rating = Integer.parseInt(ratingEl.text().trim().replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        MovieData movie = new MovieData();
        movie.setKpId(filmId);
        movie.setName(nameRus);
        movie.setNameEn(nameEn);
        movie.setYear(year);
        movie.setKpRating(rating);
        if (filmId != null) {
            boolean series = !item.select("a[href*='/series/']").isEmpty();
            movie.setKpUrl("https://www.kinopoisk.ru/" + (series ? "series/" : "film/") + filmId + "/");
        }
        return movie;
    }

    private Element findNameElement(Element item) {
        if (item.tagName().equals("a") && isFilmOrSeriesHref(item.attr("href")) && !item.text().isBlank()) {
            return item;
        }

        for (Element link : item.select("a[href*=/film/], a[href*=/series/]")) {
            if (!link.text().isBlank()) {
                return link;
            }
        }

        for (Element el : item.select("[class*=\"name\"], [class*=\"Name\"], .title, [class*=\"title\"]")) {
            if (!el.text().isBlank()) {
                return el;
            }
        }

        return null;
    }

    private boolean isFilmOrSeriesHref(String href) {
        return href != null && (href.contains("/film/") || href.contains("/series/"));
    }

    private String stripGenreTail(String name) {
        if (name == null) {
            return null;
        }
        int idx = name.indexOf(',');
        if (idx > 0) {
            return name.substring(0, idx).trim();
        }
        return name.trim();
    }

    private String stripYearFromName(String name, int year) {
        if (year <= 0 || name == null) {
            return name;
        }
        return name.replaceAll("\\s*" + year + "\\s*", "").trim();
    }

    private Document waitForPageLoad(WebDriver driver) {
        long deadline = System.currentTimeMillis() + 15_000;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            Document doc = Jsoup.parse(driver.getPageSource());
            int count = doc.select("a[href*='/film/'], a[href*='/series/']").size();
            if (count > 0 && count == last) {
                return doc;
            }
            last = count;
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Jsoup.parse(driver.getPageSource());
    }

    private List<MovieData> parsePage(Long userId, int page) {
        List<MovieData> movies;
        try {
            String url = String.format(VOTES_URL_PAGE, userId, page);
            Document doc = Jsoup.connect(url)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .timeout(15000)
                                .get();
            movies = parseDocument(doc);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse KP page " + page, e);
        }
        return movies;
    }

}
