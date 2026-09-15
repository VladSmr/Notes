package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;

/**
 * Парсинг HTML-документа выгрузки оценок КП: контейнеры фильмов, названия, годы, оценки.
 */
@Slf4j
class KpRatingsPageParser {

    /**
     * Первый год из текста страницы в валидном диапазоне ({@link KpYearValidator} — единый источник).
     */
    static final Pattern PLAIN_YEAR = Pattern.compile("\\b" + KpYearValidator.YEAR_RANGE_REGEX + "\\b");
    private static final Pattern EN_TITLE_IN_PARENS = Pattern.compile("^.+?\\((.+?)\\)$");
    private static final Pattern FILM_ID_PATTERN = Pattern.compile("/(?:film|series)/(\\d+)");
    private static final String VOTES_URL = "https://www.kinopoisk.ru/user/%d/movies/voted-watched/";
    /**
     * Год в скобках («Название (2021)») — только из валидного диапазона {@link KpYearValidator}.
     */
    private static final Pattern YEAR_IN_TEXT = Pattern.compile("\\(" + KpYearValidator.YEAR_RANGE_REGEX + "\\)");

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

    /**
     * Год из скобок в тексте («Название (2021)»), 0 если скобок с годом нет.
     */
    int extractYear(String text) {
        Matcher m = YEAR_IN_TEXT.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Общее количество оценок пользователя: опрос страницы оценок КП до 15 с,
     * затем чтение счётчика из футера ({@link #parseTotalRatings}).
     *
     * @return количество оценок или null, если страница не загрузилась / счётчик не виден
     */
    Integer fetchTotalRatings(WebDriver driver, Long userId) {
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
            }
            log.warn("Не удалось прочитать счётчик оценок КП: страница не загрузилась или счётчик не виден");
        } catch (Exception e) {
            log.warn("Не удалось прочитать счётчик оценок КП: {}", e.getMessage());
        }
        return null;
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
        // Повторная зачистка после merge: год мог дописаться ПОСЛЕ первичной очистки
        // названия, и склейка «Название22025» осталась.
        if (target.getYear() > 0 && target.getName() != null) {
            target.setName(GluedYearTitleCleaner.stripTrailingGluedYear(target.getName(), target.getYear()));
        }
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
            if (year == 0) {
                // Год не найден нигде (extractYear ищет только в скобках) — вытаскиваем год
                // из хвоста названия и зачищаем его, иначе склейка уходит в CSV и на IMDB.
                int tailYear = GluedYearTitleCleaner.extractTrailingGluedYear(nameRus);
                String stripped = tailYear > 0 ? GluedYearTitleCleaner.stripTrailingGluedYear(nameRus, 0) : nameRus;
                if (tailYear > 0 && !stripped.equals(nameRus)) {
                    log.info("Название '{}' было склеено с годом — исправлено на '{}' ({} г.)",
                             nameRus, stripped, tailYear);
                    nameRus = stripped;
                    year = tailYear;
                }
            }
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

    /**
     * Парсит число оценок из футера страницы оценок КП.
     */
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

    /**
     * Первый год валидного диапазона из текста элемента (regex, а не склейка цифр:
     * элемент может содержать и длительность — «2021 · 2 ч 30 мин»), либо 0.
     */
    int parseYearDigits(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher m = PLAIN_YEAR.matcher(text);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                if (KpYearValidator.isValidYear(y)) {
                    return y;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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

    Document waitForPageLoad(WebDriver driver) {
        long deadline = System.currentTimeMillis() + 15_000;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            Document doc = Jsoup.parse(driver.getPageSource());
            int count = doc.select("a[href*='/film/'], a[href*='/series/']").size();
            if (count > 0 && count == last) {
                return doc;
            }
            last = count;
        }
        return Jsoup.parse(driver.getPageSource());
    }

}
