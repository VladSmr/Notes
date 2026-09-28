package ru.importer.notes.imdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.kp.KpYearValidator;

/**
 * Поиск в выдаче IMDB: построение запроса, выбор результата в find-выдаче и переход на страницу тайтла.
 */
@Slf4j
class ImdbSearchAndSelect {

    /** Логичный таймаут готовности выдачи IMDB (не минуты): одно ожидание — 10 с. */
    static final Duration SEARCH_WAIT_TIMEOUT = Duration.ofSeconds(10);
    /** Интервал опроса готовности выдачи. */
    static final Duration SEARCH_WAIT_POLLING = Duration.ofMillis(500);
    /**
     * Сколько ДОПОЛНИТЕЛЬНЫХ кандидатов открывать при неоднозначном выборе (после первого):
     * первый + до трёх переоткрытий. Ставка не делается до успешной верификации.
     */
    private static final int MAX_EXTRA_AMBIGUOUS_CANDIDATES = 3;
    /** Секция результатов «Titles» в выдаче IMDB (маркер готовности выдачи). */
    private static final String TITLES_SECTION = "[data-testid=\"find-results-section-title\"]";
    /** Ссылка результата в актуальной разметке IMDb (внутри секции Titles). */
    private static final String TITLE_LINK_WRAPPER = "a.ipc-title-link-wrapper";
    /** Ссылка результата в прежней разметке IMDb — совместимость с фикстурами/кэшем разметки. */
    private static final String LEGACY_TITLE_LINK = "a.ipc-metadata-list-summary-item__t";

    private final ImdbPageVerifier verifier;
    private final Duration searchTimeout;
    private final Duration searchPolling;

    ImdbSearchAndSelect(ImdbPageVerifier verifier) {
        this(verifier, SEARCH_WAIT_TIMEOUT, SEARCH_WAIT_POLLING);
    }

    /**
     * Тестовый конструктор: позволяет задать короткие таймаут/интервал ожидания выдачи.
     */
    ImdbSearchAndSelect(ImdbPageVerifier verifier, Duration searchTimeout, Duration searchPolling) {
        this.verifier = verifier;
        this.searchTimeout = searchTimeout;
        this.searchPolling = searchPolling;
    }

    /**
     * Фабрика ожидания готовности выдачи (вынесена отдельно, чтобы тесты могли подменить тайминг).
     */
    WebDriverWait newSearchWait(WebDriver driver) {
        return new WebDriverWait(driver, searchTimeout, searchPolling);
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

    /**
     * Ссылки результатов в выдаче IMDB — каскадом по приоритету:
     * (а) ссылки внутри секции «Titles» ({@code a.ipc-title-link-wrapper}); (б) если пусто —
     * legacy-класс {@code ipc-metadata-list-summary-item__t} (совместимость с фикстурами/кэшем);
     * (в) если пусто — широкий fallback {@code a[href*="/title/tt"]} (смена разметки).
     * DOM-порядок внутри каждого шага сохраняется; сайдбарные ссылки не подмешиваются, пока
     * есть результаты в секции.
     */
    private List<WebElement> collectTitleLinks(WebDriver driver) {
        List<WebElement> links = findBySelector(driver, TITLES_SECTION + " " + TITLE_LINK_WRAPPER);
        if (!links.isEmpty()) {
            return links;
        }
        links = findBySelector(driver, LEGACY_TITLE_LINK);
        if (!links.isEmpty()) {
            return links;
        }
        return findBySelector(driver, "a[href*=\"/title/tt\"]");
    }

    /** {@code findElements} по CSS с защитой от исключений (пустой список при сбое). */
    private static List<WebElement> findBySelector(WebDriver driver, String css) {
        try {
            return driver.findElements(By.cssSelector(css));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Готова ли выдача: появилась ТОЛЬКО секция результатов «Titles»
     * ({@code [data-testid="find-results-section-title"]}). Общий IPC-класс
     * {@code ipc-title-link-wrapper} маркером готовности НЕ является — он встречается и в
     * сайдбаре («More to explore»), который может отрендериться раньше результатов и дать
     * преждевременную готовность (исходный баг «первый фильм NOT_FOUND»).
     */
    private boolean isSearchResultsReady(WebDriver driver) {
        return !findBySelector(driver, TITLES_SECTION).isEmpty();
    }

    /**
     * Явное ожидание готовности выдачи (не длиннее {@link #searchTimeout}); по таймауту —
     * пустой список: решение о повторе принимает {@link #loadSearchResults}.
     */
    private List<WebElement> waitForResults(WebDriver driver) {
        try {
            newSearchWait(driver).until(d -> isSearchResultsReady(d));
        } catch (TimeoutException e) {
            log.warn("Выдача IMDB не готова за {} с — секция результатов не появилась",
                     searchTimeout.toSeconds());
            return Collections.emptyList();
        }
        return collectTitleLinks(driver);
    }

    /**
     * Загружает выдачу: ждёт готовности; если не дождались — ОДНА повторная навигация
     * и ещё одно ожидание. Длинных ожиданий нет.
     */
    private List<WebElement> loadSearchResults(WebDriver driver, String findUrl) {
        List<WebElement> results = waitForResults(driver);
        if (!results.isEmpty()) {
            return results;
        }
        log.warn("Повторная навигация на выдачу IMDB: первая попытка не дождалась результатов ({})", findUrl);
        driver.get(findUrl);
        return waitForResults(driver);
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

    /**
     * Год из текста результата выдачи IMDB (последняя группа в скобках, напр. «(2019)»):
     * используется для дизамбигуации одноимённых тайтлов. Скобок/года нет — 0.
     */
    private int extractResultYear(WebElement el) {
        String text;
        try {
            text = el.getText();
        } catch (Exception e) {
            return 0; // мёртвый (stale) элемент — года нет
        }
        if (text == null) {
            return 0;
        }
        String s = text.replaceFirst("(?i)\\s*-\\s*IMDb\\s*$", "").trim();
        int open = s.lastIndexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return 0;
        }
        String inner = s.substring(open + 1, close).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(inner);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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
     * Открывает кандидата выдачи: tt-id из href и прямой переход (клик перехватывается
     * элементами страницы). Без ссылки на титул — старый клик как крайний фолбэк.
     */
    private void openCandidate(WebDriver driver, MovieData movie, WebElement candidate) {
        String href = getHref(candidate);
        if (href != null && href.contains("/title/")) {
            String ttId = extractTtIdFromHref(href);
            if (ttId != null) {
                movie.setImdbId(ttId);
            }
            // Финальный успех логируется выше (после верификации), чтобы «Найден на IMDB»
            // не срабатывал на ещё не проверенном кандидате.
            log.debug("Открываю кандидата из выдачи: {} (tt={})", href, ttId);
            driver.get(toAbsoluteImdbUrl(href));
        } else {
            candidate.click();
        }
    }

    /**
     * Поиск по названию и переход на найденную страницу (клик не используется — «element click intercepted»).
     *
     * @return true, если выбор был неоднозначным (≥2 точных совпадений названия), но открытая
     * страница прошла верификацию — вызывающий код помечает результат «проставлено с оговоркой»;
     * false — обычный путь
     */
    boolean searchAndOpen(WebDriver driver, MovieData movie) {
        String query = buildSearchQuery(movie);
        log.info("Поиск на IMDB: '{}'", query);
        String findUrl = "https://www.imdb.com/find/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        driver.get(findUrl);

        SearchOutcome outcome = selectSearchResult(loadSearchResults(driver, findUrl), movie);
        if (outcome.candidates().isEmpty()) {
            movie.setStatus(MovieStatus.NOT_FOUND);
            log.info("Не найден на IMDB: {}", movie.getName());
            return false;
        }

        if (!outcome.ambiguous()) {
            openCandidate(driver, movie, outcome.candidates().get(0));
            verifier.verifyPageTypeAfterSearch(driver, movie);
            if (movie.getStatus() != MovieStatus.NOT_FOUND && movie.getImdbId() != null) {
                log.info("Найден на IMDB: {} ({})", movie.getName(), movie.getImdbId());
            }
            return false;
        }

        // Неоднозначный выбор (несколько одноимённых тайтлов): перебираем кандидатов в порядке
        // релевантности, пока открытая страница не пройдёт ПОЛНУЮ верификацию (тип JSON-LD +
        // название + год ±1). Не прошёл — пробуем следующего (до MAX_EXTRA доп. открытий).
        // Оценка не ставится, пока верификация не пройдена.
        int opened = 0;
        int maxOpens = 1 + MAX_EXTRA_AMBIGUOUS_CANDIDATES;
        for (WebElement candidate : outcome.candidates()) {
            if (opened >= maxOpens) {
                break;
            }
            opened++;
            openCandidate(driver, movie, candidate);
            if (verifier.pageMatchesMovie(driver, movie)) {
                log.info("Неоднозначный выбор по '{}' для фильма '{}': кандидат {} (открытие {}) "
                                 + "прошёл верификацию страницы — ставка с оговоркой",
                         query, movie.getName(), movie.getImdbId(), opened);
                return true;
            }
            log.info("Неоднозначный выбор по '{}' для фильма '{}': кандидат {} (открытие {}) не прошёл "
                             + "верификацию страницы — пробую следующего",
                     query, movie.getName(), movie.getImdbId(), opened);
            movie.setImdbId(null);
        }
        movie.setStatus(MovieStatus.NOT_FOUND);
        log.info("Ни один из {} точных кандидатов по '{}' для фильма '{}' не прошёл верификацию — "
                         + "оценка не ставится, imdb_id не записывается", opened, query, movie.getName());
        return false;
    }

    /**
     * Выбирает результат на странице поиска IMDB. Сначала точные (после нормализации)
     * совпадения названия с дедупом по tt-id. Одно — берём (обычный путь). Несколько:
     * кандидаты в порядке релевантности (порядок IMDb), но единственный матч с годом ±1
     * от года фильма — первым (если год различает). Любой выбор из нескольких помечается
     * как неоднозначный и проходит полную верификацию страницы в {@link #searchAndOpen}.
     * Точных нет — нестрогие стратегии: название+год → год → название. Ничего не совпало —
     * «не найден».
     */
    private SearchOutcome selectSearchResult(List<WebElement> results, MovieData movie) {
        if (results.isEmpty()) {
            return SearchOutcome.notFoundSearch();
        }

        List<WebElement> exactMatches = new ArrayList<>();
        Set<String> seenTtIds = new HashSet<>();
        for (WebElement el : results) {
            if (!isExactTitleMatch(el, movie)) {
                continue;
            }
            String ttId = extractTtIdFromHref(getHref(el));
            // Результат без читаемого tt-id — отдельный кандидат (консервативно:
            // лучше «не найден», чем оценка чужому фильму).
            String key = ttId != null ? ttId : "el-" + System.identityHashCode(el);
            if (!seenTtIds.add(key)) {
                continue; // тот же фильм (тот же tt-id) уже посчитан
            }
            exactMatches.add(el);
        }

        if (exactMatches.isEmpty()) {
            WebElement fuzzy = pickFuzzyResult(results, movie);
            return fuzzy != null ? SearchOutcome.found(fuzzy) : SearchOutcome.notFoundSearch();
        }
        if (exactMatches.size() == 1) {
            return SearchOutcome.found(exactMatches.get(0));
        }

        // ≥2 точных совпадений: порядок — релевантность IMDb; если год ±1 выделяет ровно
        // один матч, ставим его первым.
        List<WebElement> ordered = new ArrayList<>(exactMatches);
        if (KpYearValidator.isValidYear(movie.getYear())) {
            List<WebElement> yearMatches = new ArrayList<>();
            for (WebElement el : exactMatches) {
                int resultYear = extractResultYear(el);
                if (resultYear > 0 && Math.abs(movie.getYear() - resultYear) <= 1) {
                    yearMatches.add(el);
                }
            }
            if (yearMatches.size() == 1) {
                WebElement preferred = yearMatches.get(0);
                ordered.remove(preferred);
                ordered.add(0, preferred);
            }
        }
        return SearchOutcome.ambiguousFound(ordered);
    }

    /**
     * Исход выбора результата поиска: упорядоченные кандидаты (пусто — «не найден») и
     * признак того, что выбор был неоднозначным (несколько точных совпадений названия).
     */
    private record SearchOutcome(List<WebElement> candidates, boolean ambiguous) {

        static SearchOutcome ambiguousFound(List<WebElement> candidates) {
            return new SearchOutcome(candidates, true);
        }

        static SearchOutcome found(WebElement element) {
            return new SearchOutcome(List.of(element), false);
        }

        static SearchOutcome notFoundSearch() {
            return new SearchOutcome(List.of(), false);
        }

    }

}
