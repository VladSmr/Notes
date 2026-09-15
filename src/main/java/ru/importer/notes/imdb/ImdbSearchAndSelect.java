package ru.importer.notes.imdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebElement;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.kp.KpYearValidator;

/**
 * Поиск в выдаче IMDB: построение запроса, выбор результата в find-выдаче и переход на страницу тайтла.
 */
@Slf4j
class ImdbSearchAndSelect {

    private final ImdbPageVerifier verifier;

    ImdbSearchAndSelect(ImdbPageVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * tt-id из href результата (или null, если href не ссылка на титул).
     */
    private static String extractTtIdFromHref(String href) {
        if (href == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("/title/(tt\\d+)").matcher(href);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Текст результата в нижнем регистре; мёртвый (stale) элемент — пустая строка («не совпадение»).
     */
    private static String resultText(WebElement el) {
        try {
            String text = el.getText();
            return text != null ? text.toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Приводит href результата поиска к абсолютному URL IMDB (href бывает относительным).
     */
    private static String toAbsoluteImdbUrl(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        return "https://www.imdb.com" + (href.startsWith("/") ? href : "/" + href);
    }

    /**
     * Поисковый запрос для IMDB: первое непустое из nameEn/name + год.
     * Год добавляется только валидный ({@link KpYearValidator#isValidYear}):
     * «Название 0» уводит выдачу в сторону — поиск идёт по названию.
     */
    String buildSearchQuery(MovieData movie) {
        String name = movie.getNameEn();
        if (name == null || name.isBlank()) {
            name = movie.getName();
        }
        if (name == null || name.isBlank()) {
            return "";
        }
        return KpYearValidator.isValidYear(movie.getYear())
                ? name + " " + movie.getYear()
                : name;
    }

    private List<WebElement> collectTitleLinks(WebDriver driver) {
        try {
            return driver.findElements(By.cssSelector(
                    "[data-testid=\"find-results-section-title\"] a.ipc-metadata-list-summary-item__t, " +
                            ".findResult a, " +
                            "a[href*=\"/title/tt\"]"
            ));
        } catch (Exception ignored) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Название из текста ссылки результата: срезает ведущий нумератор и последнюю
     * группу в скобках (год/тип титула не участвуют в сравнении названий);
     * скобки внутри названия сохраняются.
     */
    private String extractResultName(WebElement el) {
        String text;
        try {
            text = el.getText();
        } catch (Exception e) {
            return ""; // устаревший/недоступный элемент — совпадением не считаем
        }
        if (text == null) {
            return "";
        }
        return text.trim()
                   .replaceFirst("^\\d+\\.\\s*", "")
                   .replaceFirst("\\s*\\([^)]*\\)\\s*$", "")
                   .trim();
    }

    private String getHref(WebElement el) {
        try {
            return el.getAttribute("href");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Точное (после нормализации) совпадение названия результата с одним из названий фильма.
     */
    private boolean isExactTitleMatch(WebElement el, MovieData movie) {
        String resultName = extractResultName(el);
        if (resultName.isBlank()) {
            return false;
        }
        String normalizedResult = ImdbNotesExporter.normalizeTitle(resultName);
        for (String candidate : ImdbNotesExporter.candidateTitles(movie)) {
            if (ImdbNotesExporter.normalizeTitle(candidate).equals(normalizedResult)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Нестрогий выбор (когда точных совпадений названия нет): название+год → год → название.
     * Мёртвый (stale) элемент — «не совпадение», а не исключение: иначе
     * StaleElementReferenceException уронил бы фильм в ERROR вместо фолбэка.
     */
    private WebElement pickFuzzyResult(List<WebElement> results, MovieData movie) {
        String nameEn = movie.getNameEn() != null ? movie.getNameEn().toLowerCase() : "";
        String name = movie.getName() != null ? movie.getName().toLowerCase() : "";
        String nameToken = !nameEn.isBlank() ? nameEn : name;
        if (nameToken.length() > 12) {
            nameToken = nameToken.substring(0, 12);
        }
        String yearStr = KpYearValidator.isValidYear(movie.getYear())
                ? "(" + movie.getYear() + ")"
                : null;

        if (yearStr != null && !nameToken.isBlank()) {
            for (WebElement el : results) {
                String text = resultText(el);
                if (text.contains(nameToken) && text.contains(yearStr)) {
                    return el;
                }
            }
        }
        if (yearStr != null) {
            for (WebElement el : results) {
                if (resultText(el).contains(yearStr)) {
                    return el;
                }
            }
        }
        if (!nameToken.isBlank()) {
            for (WebElement el : results) {
                if (resultText(el).contains(nameToken)) {
                    return el;
                }
            }
        }
        return null;
    }

    /**
     * Поиск по названию и переход на найденную страницу (клик не используется — «element click intercepted»).
     */
    void searchAndOpen(WebDriver driver, MovieData movie) {
        String query = buildSearchQuery(movie);
        log.info("Поиск на IMDB: '{}'", query);
        driver.get("https://www.imdb.com/find/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));

        SearchOutcome outcome = selectSearchResult(driver, movie);
        if (outcome.ambiguous()) {
            movie.setStatus(MovieStatus.NOT_FOUND);
            movie.setImdbId(null);
            log.info("Неоднозначный поиск по '{}' для фильма '{}': несколько точных совпадений "
                             + "названия — оценка не ставится, imdb_id не записывается "
                             + "(защита от одноимённого ремейка)", query, movie.getName());
            return;
        }

        WebElement firstResult = outcome.result();
        if (firstResult == null) {
            movie.setStatus(MovieStatus.NOT_FOUND);
            log.info("Не найден на IMDB: {}", movie.getName());
            return;
        }

        String imdbHref = firstResult.getAttribute("href");
        if (imdbHref != null && imdbHref.contains("/title/")) {
            String ttId = imdbHref.replaceAll(".*/title/(tt\\d+).*", "$1");
            movie.setImdbId(ttId);
            log.info("Найден на IMDB: {} ({})", movie.getName(), ttId);
            // Клик по ссылке результата перехватывается элементами страницы —
            // tt-id уже извлечён из href, открываем страницу напрямую.
            driver.get(toAbsoluteImdbUrl(imdbHref));
        } else {
            // Нештатный результат без ссылки на титул — старый клик как крайний фолбэк.
            firstResult.click();
        }
        verifier.verifyPageTypeAfterSearch(driver, movie);
    }

    /**
     * Выбирает результат на странице поиска IMDB: сначала точные (после нормализации)
     * совпадения названия с дедупом по tt-id; ≥2 — неоднозначность (риск оценки
     * одноимённому ремейку «Оно 1990/2017») — NOT_FOUND без оценки и imdb_id;
     * одно — берём. Точных нет — нестрогие стратегии: название+год → год → название.
     * Ничего не совпало — NOT_FOUND, чтобы не поставить оценку постороннему фильму.
     */
    private SearchOutcome selectSearchResult(WebDriver driver, MovieData movie) {
        List<WebElement> results = collectTitleLinks(driver);
        if (results.isEmpty()) {
            ImdbNotesExporter.sleepUninterruptibly(2000);
            results = collectTitleLinks(driver);
        }
        if (results.isEmpty()) {
            return SearchOutcome.notFoundSearch();
        }

        int exactMatches = 0;
        WebElement exactResult = null;
        Set<String> seenTtIds = new HashSet<>();
        for (WebElement el : results) {
            if (!isExactTitleMatch(el, movie)) {
                continue;
            }
            String ttId = extractTtIdFromHref(getHref(el));
            // Результат без читаемого tt-id — отдельный кандидат (консервативно:
            // лучше NOT_FOUND, чем оценка чужому фильму).
            String key = ttId != null ? ttId : "el-" + System.identityHashCode(el);
            if (!seenTtIds.add(key)) {
                continue; // тот же фильм (тот же tt-id) уже посчитан
            }
            exactMatches++;
            if (exactResult == null) {
                exactResult = el;
            }
        }

        if (exactMatches >= 2) {
            return SearchOutcome.ambiguousSearch();
        }
        if (exactMatches == 1) {
            return SearchOutcome.found(exactResult);
        }
        WebElement fuzzy = pickFuzzyResult(results, movie);
        return fuzzy != null ? SearchOutcome.found(fuzzy) : SearchOutcome.notFoundSearch();
    }

    /**
     * Исход выбора результата поиска: найденный элемент, неоднозначность или «не найден».
     */
    private record SearchOutcome(WebElement result, boolean ambiguous) {

        static SearchOutcome ambiguousSearch() {
            return new SearchOutcome(null, true);
        }

        static SearchOutcome found(WebElement element) {
            return new SearchOutcome(element, false);
        }

        static SearchOutcome notFoundSearch() {
            return new SearchOutcome(null, false);
        }

    }

}
