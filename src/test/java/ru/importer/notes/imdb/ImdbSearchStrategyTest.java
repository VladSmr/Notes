package ru.importer.notes.imdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты ImdbNotesExporter на моках WebDriver (без реального Chrome):
 * стратегия поиска на IMDB — запрос с/без года, нормализация названия,
 * переход по href вместо клика, анти-неоднозначность и stale-выдача.
 */
class ImdbSearchStrategyTest extends ImdbTestSupport {

    // ------------------------------------------------------------------
    // Стратегия при невалидном годе: поиск без года + анти-неоднозначность
    // ------------------------------------------------------------------

    @Test
    void buildSearchQuery_validYear_includesYear() {
        assertEquals("She-Hulk: Attorney at Law 2022", search.buildSearchQuery(completeMovie()));
    }

    @Test
    void buildSearchQuery_invalidYear_queryWithoutYear() {
        // Год 0 — поиск только по названию, без хвоста «0».
        MovieData zeroYear = completeMovie();
        zeroYear.setYear(0);
        assertEquals("She-Hulk: Attorney at Law", search.buildSearchQuery(zeroYear));

        // Мусорный год (вне 1890–2050) — тоже не попадает в запрос.
        MovieData garbageYear = completeMovie();
        garbageYear.setYear(2101);
        assertEquals("She-Hulk: Attorney at Law", search.buildSearchQuery(garbageYear));
    }

    @Test
    void evaluate_zeroYearSearch_singleExactResult_navigatesToTitle() {
        // Невалидный год (0): запрос без года; один точный результат → переход.
        MovieData movie = completeMovie();
        movie.setYear(0);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(result.getAttribute("href")).thenReturn("/title/tt13622970/?ref_=fn_tt_tt");

        WebDriver driver = mockDriver(List.of(result));
        WebDriverWait wait = mock(WebDriverWait.class);
        when(wait.until(any())).thenThrow(new TimeoutException());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        // Запрос без года: в find-URL нет закодированного « 0» на конце.
        String queryPart = URLEncoder.encode("She-Hulk: Attorney at Law", StandardCharsets.UTF_8);
        verify(driver).get(org.mockito.ArgumentMatchers.contains(queryPart));
        verify(driver, atLeastOnce()).get(org.mockito.ArgumentMatchers.contains("/title/tt13622970"));
        assertEquals("tt13622970", movie.getImdbId());
    }

    @Test
    void evaluate_searchWithoutYear_twoExactTitleMatches_verificationFails_notFound() {
        // ≥2 точных совпадений, год 0 (не различает): берём первый по релевантности
        // и прогоняем полную верификацию. У мок-страницы нет <title> — верификация
        // не проходит → NOT_FOUND без ставки и без imdb_id.
        MovieData movie = completeMovie();
        movie.setYear(0); // поиск без года, дизамбигуация по году недоступна
        movie.setName("Оно");
        movie.setNameEn("Оно");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement it1990 = mock(WebElement.class);
        when(it1990.getText()).thenReturn("Оно (1990)");
        when(it1990.getAttribute("href")).thenReturn("/title/tt0091986/");
        WebElement it2017 = mock(WebElement.class);
        when(it2017.getText()).thenReturn("Оно (2017)");
        when(it2017.getAttribute("href")).thenReturn("/title/tt1396484/");

        WebDriver driver = mockDriver(List.of(it1990, it2017));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        assertNull(movie.getImdbId(), "imdb_id при непрошедшей верификации не записывается");
        // Первый по релевантности матч (Оно 1990) был открыт именно для верификации.
        verify(driver, atLeastOnce()).get(org.mockito.ArgumentMatchers.contains("/title/tt0091986"));
    }

    // ------------------------------------------------------------------
    // Смягчённая анти-неоднозначность: дизамбигуация по году / первый матч
    // ------------------------------------------------------------------

    @Test
    void searchAndOpen_threeExactMatches_yearDistinguishes_choosesYearMatchAndFlagsAmbiguous() {
        // Midsommar 2019: три точных совпадения названия; год ±1 выделяет ровно одно
        // («Midsommar (2019)») — выбирается оно и помечается как неоднозначное.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2005)", "/title/tt1111111/"),
                result("Midsommar (2019)", "/title/tt2222222/"),
                result("Midsommar (2023)", "/title/tt3333333/")));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"Movie\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertTrue(ambiguous, "выбор из нескольких совпадений — неоднозначный");
        assertEquals("tt2222222", movie.getImdbId(), "год ±1 выделил единственный матч");
        assertEquals(MovieStatus.PENDING, movie.getStatus(), "верификация пройдена — статус не сброшен");
    }

    @Test
    void searchAndOpen_threeExactMatches_yearDoesNotDistinguish_choosesFirstAndFlagsAmbiguous() {
        // Год не различает (два матча с годом 2019): берём первый по релевантности
        // (порядок IMDb) и помечаем неоднозначным.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt1111111/?ref_=fn_t_1"),
                result("Midsommar (2019)", "/title/tt2222222/?ref_=fn_t_2"),
                result("Midsommar (2019)", "/title/tt3333333/?ref_=fn_t_3")));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"Movie\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertTrue(ambiguous, "выбор из нескольких совпадений — неоднозначный");
        assertEquals("tt1111111", movie.getImdbId(), "год не различает — берём первый матч");
    }

    @Test
    void searchAndOpen_ambiguousChoice_verificationFails_notFoundWithoutImdbId() {
        // Неоднозначный выбор, но открытая страница — не фильм (@type PodcastEpisode):
        // полная верификация отклоняет, imdb_id не записывается, NOT_FOUND.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt12624460/"),
                result("Midsommar (2019)", "/title/tt10597316/")));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"PodcastEpisode\",\"name\":\"Midsommar (2019)\"}"));

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous, "верификация не пройдена — не считаем ставку с оговоркой");
        assertNull(movie.getImdbId(), "imdb_id нефильма не записывается");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
    }

    @Test
    void searchAndOpen_singleExactMatch_notAmbiguous() {
        // Единственный точный матч — обычный путь без флага неоднозначности.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt8772262/")));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"Movie\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous, "один точный матч — не неоднозначность");
        assertEquals("tt8772262", movie.getImdbId());
    }

    @Test
    void ratedStatus_mapsAmbiguityFlag() {
        // Успешная ставка из неоднозначного выбора → RATED_AMBIGUOUS, иначе RATED.
        assertEquals(MovieStatus.RATED_AMBIGUOUS, ImdbNotesExporter.ratedStatus(true));
        assertEquals(MovieStatus.RATED, ImdbNotesExporter.ratedStatus(false));
    }

    /** Фильм Midsommar 2019 для тестов дизамбигуации (все три названия — «Midsommar»). */
    private MovieData midsommar() {
        MovieData movie = completeMovie();
        movie.setName("Midsommar");
        movie.setNameOriginal(null);
        movie.setNameEn("Midsommar");
        movie.setYear(2019);
        return movie;
    }

    /** Мок результата выдачи поиска с заданным текстом и href. */
    private WebElement result(String text, String href) {
        WebElement el = mock(WebElement.class);
        when(el.getText()).thenReturn(text);
        when(el.getAttribute("href")).thenReturn(href);
        return el;
    }

    @Test
    void normalizeTitle_stripsPunctuationTheAndCase() {
        assertEquals("she hulk attorney at law", ImdbNotesExporter.normalizeTitle("She-Hulk: Attorney at Law"));
        assertEquals("matrix", ImdbNotesExporter.normalizeTitle("The Matrix"));
        assertEquals("matrix", ImdbNotesExporter.normalizeTitle("  The, MATRIX!  "));
        assertEquals("оно", ImdbNotesExporter.normalizeTitle("«Оно»"));
        assertEquals("2001 a space odyssey", ImdbNotesExporter.normalizeTitle("2001: A Space Odyssey"));
        assertEquals("", ImdbNotesExporter.normalizeTitle(null));
        assertEquals("", ImdbNotesExporter.normalizeTitle("the"));
    }

    // ------------------------------------------------------------------
    // Переход по href вместо клика по результату поиска
    // ------------------------------------------------------------------

    @Test
    void evaluate_searchResult_navigatesByHrefWithoutClick() {
        MovieData movie = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(result.getAttribute("href")).thenReturn("/title/tt13622970/?ref_=fn_tt_tt");
        // Клик должен привести к «element click intercepted» → ERROR, а не RATED.
        doThrow(new RuntimeException("element click intercepted")).when(result).click();

        WebDriver driver = mockDriver(List.of(result));
        WebDriverWait wait = mock(WebDriverWait.class);
        when(wait.until(any())).thenThrow(new TimeoutException());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        // tt-id извлечён из href, страница открыта напрямую через driver.get.
        verify(driver, atLeastOnce()).get("https://www.imdb.com/title/tt13622970/?ref_=fn_tt_tt");
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        verify(result, never()).click();
        assertEquals("tt13622970", movie.getImdbId());
    }

    // ------------------------------------------------------------------
    // Мёртвый (stale) элемент в fuzzy-выдаче — фолбэк, а не ERROR
    // ------------------------------------------------------------------

    @Test
    void evaluate_fuzzyStaleResult_fallsBackToNotFound_notError() {
        // Единственный результат — stale-элемент: «не совпадение» → NOT_FOUND, а не ERROR.
        MovieData movie = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement stale = mock(WebElement.class);
        when(stale.getText()).thenThrow(new StaleElementReferenceException("stale"));

        WebDriver driver = mockDriver(List.of(stale));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus(),
                "stale-элемент в выдаче — «не совпадение», а не ошибка прогона");
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
    }

    // ------------------------------------------------------------------
    // Тот же tt дважды в выдаче — один кандидат (анти-неоднозначность)
    // ------------------------------------------------------------------

    @Test
    void evaluate_twoExactResultsWithSameTtId_countedOnce_notAmbiguous() {
        // Тот же tt дважды в выдаче считается ОДНИМ точным совпадением —
        // без этого ложная неоднозначность давала бы NOT_FOUND вместо ставки.
        MovieData movie = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement first = mock(WebElement.class);
        when(first.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(first.getAttribute("href")).thenReturn("/title/tt13622970/?ref_=fn_tt_tt");
        WebElement second = mock(WebElement.class);
        when(second.getText()).thenReturn("She-Hulk: Attorney at Law (TV Series 2022– )");
        when(second.getAttribute("href")).thenReturn("/title/tt13622970/");

        WebDriver driver = mockDriver(List.of(first, second));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        // Дедуп снял ложную неоднозначность: переход на страницу титула был.
        assertEquals("tt13622970", movie.getImdbId(), "тот же tt дважды — один кандидат, imdb_id записан");
        verify(driver, atLeastOnce()).get(org.mockito.ArgumentMatchers.contains("/title/tt13622970"));
        verify(first, never()).click();
        verify(second, never()).click();
    }

    // ------------------------------------------------------------------
    // Надёжный поиск: явное ожидание готовности выдачи + повторная навигация
    // ------------------------------------------------------------------

    /** Мок драйвера для тестов ожидания: без стаба findElements (его задаёт каждый тест). */
    private WebDriver bareMockDriver() {
        WebDriver driver = mock(WebDriver.class);
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(driver.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(timeouts.implicitlyWait(any(Duration.class))).thenReturn(timeouts);
        return driver;
    }

    @Test
    void readiness_requiresResultsSection_sidebarWrapperAloneDoesNotSatisfy() {
        // Major-1: общий IPC-класс ipc-title-link-wrapper встречается и в сайдбаре; без секции
        // результатов выдача НЕ считается готовой — иначе сайдбарный кандидат подставляется /
        // воспроизводится исходный баг «первый фильм NOT_FOUND».
        MovieData movie = completeMovie();
        WebDriver driver = bareMockDriver();

        AtomicInteger findNavigations = new AtomicInteger();
        doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("/find/?q=")) {
                findNavigations.incrementAndGet();
            }
            return null;
        }).when(driver).get(anyString());

        WebElement sidebar = result("Some Sidebar (1999)", "/title/tt0000001/");
        when(driver.findElements(any(By.class))).thenAnswer(inv -> {
            String css = inv.getArgument(0).toString();
            if (css.contains("find-results-section-title")) {
                return Collections.emptyList(); // секции результатов нет
            }
            if (css.contains("ipc-title-link-wrapper")) {
                return List.of(sidebar); // есть только сайдбарный IPC-класс
            }
            return Collections.emptyList();
        });

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertNull(movie.getImdbId(), "сайдбарный кандидат не должен подставляться");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        verify(driver, times(2)).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
    }

    @Test
    void collectTitleLinks_cascade_priority_sectionThenLegacyThenWide() {
        // Приоритет каскада: ссылки внутри секции → legacy-класс → широкий fallback.
        // Здесь секция результатов есть, но её wrapper-ссылок нет — срабатывает legacy,
        // широкий fallback не запрашивается.
        MovieData movie = midsommar();
        WebDriver driver = bareMockDriver();
        List<String> queried = new ArrayList<>();
        WebElement legacy = result("Midsommar (2019)", "/title/tt8772262/");
        WebElement sectionMarker = mock(WebElement.class);
        when(driver.findElements(any(By.class))).thenAnswer(inv -> {
            String css = inv.getArgument(0).toString();
            queried.add(css);
            if (css.contains("find-results-section-title") && css.contains("ipc-title-link-wrapper")) {
                return Collections.emptyList(); // в секции «Titles» wrapper-ссылок нет
            }
            if (css.contains("find-results-section-title")) {
                return List.of(sectionMarker); // маркер готовности — секция есть
            }
            if (css.contains("ipc-metadata-list-summary-item__t")) {
                return List.of(legacy);
            }
            if (css.contains("/title/tt")) {
                return List.of(mock(WebElement.class)); // широкий fallback не должен использоваться
            }
            return Collections.emptyList();
        });
        when(driver.getPageSource()).thenReturn(pageWithJsonLd("{\"@type\":\"Movie\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertEquals("tt8772262", movie.getImdbId(), "использован legacy-кандидат, а не широкий fallback");
        int sectionIdx = indexOfContaining(queried, "find-results-section-title");
        int legacyIdx = indexOfContaining(queried, "ipc-metadata-list-summary-item__t");
        int wideIdx = indexOfContaining(queried, "/title/tt");
        assertTrue(sectionIdx >= 0 && legacyIdx > sectionIdx,
                "legacy запрашивается после проверки секции: " + queried);
        assertEquals(-1, wideIdx,
                "широкий fallback не должен запрашиваться, пока есть legacy-результаты: " + queried);
    }

    /** Индекс первого элемента списка, содержащего подстроку, иначе -1. */
    private static int indexOfContaining(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void searchAndOpen_resultsAppearAfterRetryNavigation_succeeds() {
        // Готовая секции результатов появляется только со второй навигации (холодный IMDb):
        // первая попытка не дождалась — одна повторная навигация, затем результат найден.
        MovieData movie = completeMovie();
        WebDriver driver = bareMockDriver();

        WebElement result = result("She-Hulk: Attorney at Law (2022)", "/title/tt13622970/");
        AtomicInteger findNavigations = new AtomicInteger();
        doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("/find/?q=")) {
                findNavigations.incrementAndGet();
            }
            return null;
        }).when(driver).get(anyString());
        when(driver.findElements(any(By.class)))
                .thenAnswer(inv -> findNavigations.get() >= 2 ? List.of(result) : Collections.emptyList());
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"Movie\",\"name\":\"She-Hulk: Attorney at Law\"}"));
        when(driver.getTitle()).thenReturn("She-Hulk: Attorney at Law (2022) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertEquals("tt13622970", movie.getImdbId(), "после повторной навигации результат найден");
        verify(driver, times(2)).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
    }

    @Test
    void searchAndOpen_resultsNeverAppear_notFoundOnlyAfterRetry() {
        // Выдача так и не готова: ровно две навигации (первая + один повтор), затем NOT_FOUND.
        MovieData movie = completeMovie();
        WebDriver driver = bareMockDriver();
        when(driver.findElements(any(By.class))).thenReturn(Collections.emptyList());

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        assertNull(movie.getImdbId());
        verify(driver, times(2)).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
    }

    // ------------------------------------------------------------------
    // Перебор неоднозначных кандидатов до успешной верификации типа
    // ------------------------------------------------------------------

    @Test
    void searchAndOpen_ambiguousTwoEpisodesThenMovie_triesUntilMoviePasses() {
        // Три точных кандидата: первые два — эпизоды (не Movie/TVSeries), третий — фильм.
        // Перебор открывает следующего кандидата, фильм выбран и помечен неоднозначным.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt1111111/"),
                result("Midsommar (2019)", "/title/tt2222222/"),
                result("Midsommar (2019)", "/title/tt3333333/")));

        AtomicInteger titleOpens = new AtomicInteger();
        doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("/title/")) {
                titleOpens.incrementAndGet();
            }
            return null;
        }).when(driver).get(anyString());
        when(driver.getPageSource()).thenAnswer(inv -> titleOpens.get() >= 3
                ? pageWithJsonLd("{\"@type\":\"Movie\",\"name\":\"Midsommar\"}")
                : pageWithJsonLd("{\"@type\":\"TVEpisode\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertTrue(ambiguous, "фильм найден среди кандидатов — ставка с оговоркой");
        assertEquals("tt3333333", movie.getImdbId(), "выбран Movie-кандидат, а не эпизод");
        assertEquals(MovieStatus.PENDING, movie.getStatus(), "верификация пройдена — статус не сброшен");
    }

    @Test
    void searchAndOpen_ambiguousAllCandidatesEpisodes_notFound() {
        // Все точные кандидаты — эпизоды: ни один не прошёл верификацию → NOT_FOUND без ставки.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt1111111/"),
                result("Midsommar (2019)", "/title/tt2222222/")));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"TVEpisode\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertNull(movie.getImdbId(), "imdb_id нефильма не записывается");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
    }

    @Test
    void searchAndOpen_ambiguousLimit_opensAtMostFourCandidates_thenNotFound() {
        // Minor-2 (лимит перебора): 6 точных кандидатов-эпизодов, ни один не проходит верификацию.
        // Открытий должно быть ровно 1 + MAX_EXTRA_AMBIGUOUS_CANDIDATES (=3) = 4, затем NOT_FOUND.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt1111111/"),
                result("Midsommar (2019)", "/title/tt2222222/"),
                result("Midsommar (2019)", "/title/tt3333333/"),
                result("Midsommar (2019)", "/title/tt4444444/"),
                result("Midsommar (2019)", "/title/tt5555555/"),
                result("Midsommar (2019)", "/title/tt6666666/")));

        AtomicInteger titleOpens = new AtomicInteger();
        doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("/title/")) {
                titleOpens.incrementAndGet();
            }
            return null;
        }).when(driver).get(anyString());
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@type\":\"TVEpisode\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertFalse(ambiguous);
        assertNull(movie.getImdbId(), "ни один кандидат не прошёл верификацию — imdb_id не записан");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        assertEquals(4, titleOpens.get(), "ровно 1 + MAX_EXTRA_AMBIGUOUS_CANDIDATES открытий");
    }

    @Test
    void searchAndOpen_ambiguousMovieAtFourthOpening_winsWithinLimit() {
        // Minor-2: фильм стоит 4-м среди точных кандидатов-эпизодов и попадает в лимит (4 открытия)
        // — он выбран, перебор остановлен, ставка с оговоркой.
        MovieData movie = midsommar();
        WebDriver driver = mockDriver(List.of(
                result("Midsommar (2019)", "/title/tt1111111/"),
                result("Midsommar (2019)", "/title/tt2222222/"),
                result("Midsommar (2019)", "/title/tt3333333/"),
                result("Midsommar (2019)", "/title/tt4444444/"),
                result("Midsommar (2019)", "/title/tt5555555/"),
                result("Midsommar (2019)", "/title/tt6666666/")));

        AtomicInteger titleOpens = new AtomicInteger();
        doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("/title/")) {
                titleOpens.incrementAndGet();
            }
            return null;
        }).when(driver).get(anyString());
        when(driver.getPageSource()).thenAnswer(inv -> titleOpens.get() >= 4
                ? pageWithJsonLd("{\"@type\":\"Movie\",\"name\":\"Midsommar\"}")
                : pageWithJsonLd("{\"@type\":\"TVEpisode\",\"name\":\"Midsommar\"}"));
        when(driver.getTitle()).thenReturn("Midsommar (2019) - IMDb");

        boolean ambiguous = search.searchAndOpen(driver, movie);

        assertTrue(ambiguous, "фильм найден в пределах лимита — ставка с оговоркой");
        assertEquals("tt4444444", movie.getImdbId(), "выбран Movie-кандидат (4-е открытие)");
        assertEquals(MovieStatus.PENDING, movie.getStatus(), "верификация пройдена — статус не сброшен");
        assertEquals(4, titleOpens.get(), "перебор остановлен сразу после успеха");
    }
}
