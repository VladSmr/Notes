package ru.importer.notes.kp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.movie.ImportProgress;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KpNotesImporterTest {

    private final KpNotesImporter importer = new KpNotesImporter();

    @Test
    void parseItem_shouldExtractNameYearRatingAndId() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/12345/\">Начало</a>" +
                "<span class=\"year\">2010</span>" +
                "<span class=\"value\">8</span>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Начало", movie.getName());
        assertNull(movie.getNameEn());
        assertEquals(2010, movie.getYear());
        assertEquals(8, movie.getKpRating());
        assertEquals(12345L, movie.getKpId());
    }

    @Test
    void parseItem_shouldHandleMissingFields() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/999/\">Без года</a>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Без года", movie.getName());
        assertEquals(0, movie.getYear());
        assertEquals(0, movie.getKpRating());
        assertEquals(999L, movie.getKpId());
    }

    @Test
    void parseItem_shouldReturnNullWhenNoNameAndNoId() {
        String html = "<div class=\"item\"><span class=\"year\">2020</span></div>";
        assertNull(importer.parseItem(Jsoup.parse(html).select("div").first(), null));
    }

    @Test
    void parseItem_shouldHandleNegativeYearInput() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/1/\">Фильм</a>" +
                "<span class=\"year\">1999</span>" +
                "<span class=\"value\">10</span>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals(1999, movie.getYear());
        assertEquals(10, movie.getKpRating());
    }

    @Test
    void parseItem_shouldExtractEnglishTitle() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/123/\">Начало (Inception)</a>" +
                "<span class=\"year\">2010</span>" +
                "<span class=\"value\">8</span>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Начало", movie.getName());
        assertEquals("Inception", movie.getNameEn());
    }

    @Test
    void parseItem_shouldExtractYearFromTitle() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/456/\">Some Movie (1999)</a>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Some Movie", movie.getName());
        assertEquals(1999, movie.getYear());
    }

    @Test
    void parseItem_shouldNotTreatYearInTitleAsEnglishName() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/789/\">Inception (2010)</a>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Inception", movie.getName());
        assertNull(movie.getNameEn());
        assertEquals(2010, movie.getYear());
    }

    @Test
    void parseDocument_shouldDeduplicatePosterAndTitleLinks() {
        String html = "<html><body>" +
                "<div class=\"item\">" +
                "  <a class=\"cover\" href=\"/film/12345/\"><img src=\"x.jpg\"></a>" +
                "  <a class=\"name\" href=\"/film/12345/\">Начало (Inception) (2010)</a>" +
                "  <span class=\"value\">8</span>" +
                "</div>" +
                "</body></html>";

        List<MovieData> movies = importer.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size());
        MovieData movie = movies.get(0);
        assertEquals("Начало", movie.getName());
        assertEquals("Inception", movie.getNameEn());
        assertEquals(2010, movie.getYear());
        assertEquals(8, movie.getKpRating());
        assertEquals(12345L, movie.getKpId());
    }

    @Test
    void parseDocument_shouldMergeDataFromSeparateContainers() {
        String html = "<html><body>" +
                "<div class=\"cover\">" +
                "  <a href=\"/film/777/\"><img src=\"x.jpg\"></a>" +
                "  <span class=\"value\">9</span>" +
                "</div>" +
                "<div class=\"info\">" +
                "  <a href=\"/film/777/\">Криминальное чтиво (Pulp Fiction) (1994)</a>" +
                "</div>" +
                "</body></html>";

        List<MovieData> movies = importer.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size());
        MovieData movie = movies.get(0);
        assertEquals("Криминальное чтиво", movie.getName());
        assertEquals("Pulp Fiction", movie.getNameEn());
        assertEquals(1994, movie.getYear());
        assertEquals(9, movie.getKpRating());
        assertEquals(777L, movie.getKpId());
    }

    @Test
    void parseItem_shouldHandleTitleBlockWithGenreAndGluedYear() {
        String html = "<div class=\"item\">" +
                "<a class=\"cover\" href=\"/film/555/\"><img src=\"x.jpg\"></a>" +
                "<div class=\"userFilm__title\">Матрица<span class=\"year\">1999</span>, боевик</div>" +
                "<span class=\"value\">7</span>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div.item").first(), 555L);
        assertNotNull(movie);
        assertEquals("Матрица", movie.getName());
        assertEquals(1999, movie.getYear());
        assertEquals(7, movie.getKpRating());
        assertEquals(555L, movie.getKpId());
    }

    @Test
    void parseItem_shouldKeepYearOnlyInRussianTitleWithoutParens() {
        String html = "<div class=\"item\">" +
                "<a class=\"cover\" href=\"/film/555/\"><img src=\"x.jpg\"></a>" +
                "<div class=\"userFilm__title\">Космическая одиссея 2001<span class=\"year\">1968</span>, фантастика</div>" +
                "</div>";

        MovieData movie = importer.parseItem(Jsoup.parse(html).select("div.item").first(), 555L);
        assertNotNull(movie);
        assertEquals("Космическая одиссея 2001", movie.getName());
        assertEquals(1968, movie.getYear());
    }

    @Test
    void parseDocument_shouldFindFilmsWithLinksWithoutTrailingSlash() {
        String html = "<html><body>" +
                "<div class=\"item\">" +
                "  <a class=\"cover\" href=\"/film/111\"><img src=\"x.jpg\"></a>" +
                "  <div class=\"userFilm__title\">Фильм<span>2010</span>, драма</div>" +
                "</div>" +
                "</body></html>";

        List<MovieData> movies = importer.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size());
        assertEquals(111L, movies.get(0).getKpId());
        assertEquals("Фильм", movies.get(0).getName());
        assertEquals(2010, movies.get(0).getYear());
    }

    @Test
    void parseDocument_shouldParseSeriesLinksToo() {
        String html = "<html><body>" +
                "<div class=\"item\">" +
                "  <a class=\"cover\" href=\"/series/7385223/\"><img src=\"x.jpg\"></a>" +
                "  <a class=\"captions\" href=\"/series/7385223/\">Сериал<span>2026</span>, драма</a>" +
                "  <span class=\"value\">8</span>" +
                "</div>" +
                "</body></html>";

        List<MovieData> movies = importer.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size());
        MovieData movie = movies.get(0);
        assertEquals(7385223L, movie.getKpId());
        assertEquals("Сериал", movie.getName());
        assertEquals(2026, movie.getYear());
        assertEquals(8, movie.getKpRating());
        assertEquals("https://www.kinopoisk.ru/series/7385223/", movie.getKpUrl());
    }

    @Test
    void parseTotalRatings_shouldReadCountFromVotedWatchedLink() {
        String html = "<html><body>" +
                "<div class=\"styles_footer__XRVRD\"><div class=\"styles_root__22peO styles_stats__qELXI\">" +
                "  <button><span class=\"styles_statValue__NuCuw\">5</span></button>" +
                "  <button><span class=\"styles_statValue__NuCuw\">8</span></button>" +
                "  <a href=\"/user/4845070/movies/voted-watched/\"><span class=\"styles_statValue__NuCuw\">1530</span></a>" +
                "</div></div>" +
                "</body></html>";

        Integer total = importer.parseTotalRatings(Jsoup.parse(html));
        assertEquals(1530, total);
    }

    @Test
    void parseTotalRatings_shouldReturnNullWhenNoVotedWatchedLink() {
        String html = "<html><body>" +
                "<span class=\"styles_statValue__NuCuw\">5</span>" +
                "</body></html>";

        assertNull(importer.parseTotalRatings(Jsoup.parse(html)));
    }

    // ------------------------------------------------------------------
    // fetchOriginalTitles: onBatch каждые 5 фильмов + в конце, заполнение года, остановка
    // ------------------------------------------------------------------

    private WebDriver mockDriverWithOriginalTitleAndYear() {
        WebDriver driver = mock(WebDriver.class);
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(driver.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(timeouts.implicitlyWait(any(Duration.class))).thenReturn(timeouts);

        WebElement titleEl = mock(WebElement.class);
        when(titleEl.getText()).thenReturn("Original Title");
        when(driver.findElements(any(By.class))).thenReturn(Collections.singletonList(titleEl));

        // Страница фильма с годом в CSS-module классе (как на реальном КП).
        when(driver.getPageSource()).thenReturn(
                "<html><body><span class=\"styles_year__abc\">2021</span></body></html>");
        return driver;
    }

    private List<MovieData> moviesWithoutEn(int count) {
        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            movies.add(m);
        }
        return movies;
    }

    @Test
    void fetchOriginalTitles_shouldFillOriginalTitleAndYearAndCallOnBatchEveryFivePlusFinal() {
        List<MovieData> movies = moviesWithoutEn(12);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // 12 фильмов: onBatch после 5-го и 10-го (2 вызова) + финальный в конце (1) = 3.
        assertEquals(3, batches.get());

        for (MovieData m : movies) {
            assertEquals("Original Title", m.getNameEn(), "original title для " + m.getName());
            assertEquals(2021, m.getYear(), "год для " + m.getName());
        }
    }

    @Test
    void fetchOriginalTitles_shouldNotOverwriteExistingYear() {
        List<MovieData> movies = moviesWithoutEn(3);
        movies.get(0).setYear(1999); // уже проставлен — не должен перезаписываться

        WebDriver driver = mockDriverWithOriginalTitleAndYear();
        importer.fetchOriginalTitles(movies, driver, null, list -> { });

        assertEquals(1999, movies.get(0).getYear());
        assertEquals(2021, movies.get(1).getYear());
        assertEquals(2021, movies.get(2).getYear());
    }

    @Test
    void fetchOriginalTitles_shouldCallOnBatchExactlyOnceWhenFewerThanFive() {
        List<MovieData> movies = moviesWithoutEn(3);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // Меньше 5 — промежуточных вызовов нет, только финальный.
        assertEquals(1, batches.get());
    }

    @Test
    void parseYearDigits_shouldTakeFirstFourDigitYearNotGlueAllDigits() {
        // Элемент содержит год И длительность — наивная склейка дала бы «2021230».
        assertEquals(2021, importer.parseYearDigits("2021 · 2 ч 30 мин"));
        assertEquals(1999, importer.parseYearDigits("1999"));
        assertEquals(2021, importer.parseYearDigits("Премьера: 2021, длительность 2 ч"));
    }

    @Test
    void parseYearDigits_shouldRejectYearsOutsideRange() {
        // Годы вне диапазона 1900–2100 (мусор: даты рождения, будущие даты) — не принимаем.
        assertEquals(0, importer.parseYearDigits("1899"));
        assertEquals(0, importer.parseYearDigits("2101"));
        assertEquals(0, importer.parseYearDigits("1234"));
        assertEquals(0, importer.parseYearDigits(""));
        assertEquals(0, importer.parseYearDigits(null));
    }

    @Test
    void fetchOriginalTitles_shouldSkipMovieWithoutUrlWithoutDoneAndStillSaveDump() {
        // Фильм без kpId и без kpUrl -> url == null -> пропускается без done++ и без advance,
        // но финальный дамп всё равно сохраняется.
        MovieData noUrl = new MovieData();
        noUrl.setName("Без ссылки");
        // kpId == null, kpUrl == null

        MovieData normal = new MovieData();
        normal.setKpId(1L);
        normal.setName("Фильм 1");

        List<MovieData> movies = new ArrayList<>();
        movies.add(noUrl);
        movies.add(normal);

        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // Фильм без URL не обработан: original title и год не проставлены.
        assertNull(noUrl.getNameEn());
        assertEquals(0, noUrl.getYear());
        // Обычный фильм обработан.
        assertEquals("Original Title", normal.getNameEn());
        assertEquals(2021, normal.getYear());

        // Финальный дамп сохранён (done = 1 > 0).
        assertEquals(1, batches.get());
    }

    @Test
    void progress_unitsScheme_apiUsesCeilPagesAsDenominator() {
        // API: знаменатель = ceil(N/20) (только парсинг страниц), каждая страница = 1 единица.
        // Некратное N: 101 оценок -> ceil(101/20) = 6 страниц.
        int n = 101;
        int totalUnits = (n + 19) / 20; // ceil(N/20) = 6
        ImportProgress progress = new ImportProgress();
        progress.init(totalUnits);

        for (int p = 0; p < totalUnits; p++) {
            progress.advance(ImportProgress.PHASE_KP, "Страница " + (p + 1), "api");
        }
        assertEquals(totalUnits, progress.getCurrent());
        assertEquals(totalUnits, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_unitsScheme_phase2DenominatorMatchesActualMissing() {
        // Вариант A: знаменатель фазы 2 = фактическое число missing-фильмов с URL, а не N.
        // Здесь N = 100, но missing = 20 (у большинства фильмов nameEn уже заполнен при парсинге).
        int n = 100;
        int pagesCeil = (n + 19) / 20; // ceil(100/20) = 5
        int missing = 20;

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil); // предварительный знаменатель фазы 1

        // Фаза 1: по 1 единице за страницу (pagesCeil = 5).
        for (int p = 0; p < pagesCeil; p++) {
            progress.advance(ImportProgress.PHASE_KP, "Страница " + (p + 1), "kp");
        }
        assertEquals(pagesCeil, progress.getCurrent());

        // После фазы 1 знаменатель пересчитывается под фактическое число missing (как это
        // делает SeleniumKpRatingsProvider): total = pagesScanned + missing.
        int totalUnits = progress.getCurrent() + missing;
        progress.resetTotal(totalUnits);
        assertEquals(totalUnits, progress.getTotal());
        assertEquals(pagesCeil, progress.getCurrent(), "resetTotal не должен сбрасывать накопленное");

        // Фаза 2: по 1 единице за каждый missing-фильм (20).
        for (int i = 0; i < missing; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
        }
        assertEquals(totalUnits, progress.getCurrent(), "current должен достигать total ровно в конце");
        assertEquals(totalUnits, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal(),
                "current не должен превышать total");

        // Лишние advance после завершения всех фаз не должны перевалить current за total.
        progress.advance(ImportProgress.PHASE_KP, "лишний", "extra");
        assertEquals(totalUnits, progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_resetTotal_shouldClampCurrentWhenNewTotalSmaller() {
        // Гарантия инварианта: current никогда не превышает total даже при уменьшении знаменателя.
        ImportProgress progress = new ImportProgress();
        progress.init(10);
        for (int i = 0; i < 7; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фаза 1", "kp");
        }
        assertEquals(7, progress.getCurrent());

        progress.resetTotal(5); // новый знаменатель меньше накопленного current
        assertEquals(5, progress.getTotal());
        assertEquals(5, progress.getCurrent(), "current клампится до нового total");
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_resetTotal_shouldKeepCurrentWhenNewTotalLarger() {
        ImportProgress progress = new ImportProgress();
        progress.init(3);
        progress.advance(ImportProgress.PHASE_KP, "Страница 1", "kp");
        progress.advance(ImportProgress.PHASE_KP, "Страница 2", "kp");
        assertEquals(2, progress.getCurrent());

        progress.resetTotal(2 + 4); // фаза 2 добавит 4 missing-фильма
        assertEquals(6, progress.getTotal());
        assertEquals(2, progress.getCurrent(), "накопленные единицы фазы 1 сохраняются");

        for (int i = 0; i < 4; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
        }
        assertEquals(6, progress.getCurrent());
        assertEquals(6, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void fetchOriginalTitles_shouldSaveAccumulatedDumpWhenAbortedMidWay() {
        List<MovieData> movies = moviesWithoutEn(10);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        // Останавливаемся после обработки 2 фильмов (на третьей итерации isAborted -> true).
        ImportProgress abortedAfterTwo = new ImportProgress() {
            private int advances;
            private boolean aborted;

            @Override
            public void advance(String phase, String movieName, String status) {
                advances++;
                if (advances >= 2) {
                    aborted = true;
                }
            }

            @Override
            public boolean isAborted() {
                return aborted;
            }
        };

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, abortedAfterTwo, list -> batches.incrementAndGet());

        // Обработаны первые 2 фильма (они получают original title и год), остальные — нет.
        assertEquals("Original Title", movies.get(0).getNameEn());
        assertEquals("Original Title", movies.get(1).getNameEn());
        assertNull(movies.get(2).getNameEn());

        // Накопленный дамп сохранён в конце фазы (done=2 > 0) — финальный onBatch вызван.
        assertEquals(1, batches.get());
    }

    // ------------------------------------------------------------------
    // SeleniumKpRatingsProvider: знаменатель фазы 2 = фактическое число missing (вариант A)
    // ------------------------------------------------------------------

    /**
     * Полный сценарий selenium-парсинга: N=100, но missing=20 (< N). Знаменатель должен
     * пересчитываться после фазы 1 под фактическое число missing, чтобы current достигал
     * total ровно в конце (бар 100%), а не застревал на плато ниже total.
     */
    @Test
    void seleniumProvider_barReaches100PercentWhenMissingLessThanN() {
        int n = 100;
        int pagesCeil = (n + 19) / 20; // ceil(100/20) = 5
        int missing = 20; // у 80 фильмов nameEn уже заполнен при парсинге

        // 100 фильмов: 20 без nameEn (все с kpId -> url), 80 с nameEn.
        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            if (i > missing) {
                m.setNameEn("En " + i); // у большинства nameEn заполнен
            }
            movies.add(m);
        }

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        // Фаза 1: getNotes сканирует pagesCeil страниц, по 1 advance за страницу.
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int pg = 0; pg < pagesCeil; pg++) {
                p.advance(ImportProgress.PHASE_KP, "Страница " + (pg + 1), "kp");
            }
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());

        // Фаза 2: fetchOriginalTitles делает по 1 advance за каждый missing-фильм.
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int i = 0; i < missing; i++) {
                p.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
            }
            return null;
        }).when(notesImporter).fetchOriginalTitles(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));

        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil); // предварительный знаменатель фазы 1 (как в Processor)

        provider.fetchRatings(1L, null, progress);

        // Знаменатель пересчитан: pagesScanned(5) + missing(20) = 25.
        assertEquals(pagesCeil + missing, progress.getTotal());
        // current достиг total ровно в конце (бар 100%), не превышая его.
        assertEquals(progress.getTotal(), progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    /**
     * Missing-фильм без URL (нет ни kpId, ни kpUrl) в фазе 2 пропускается БЕЗ advance,
     * поэтому не должен попадать в знаменатель — иначе current застрянет ниже total.
     */
    @Test
    void seleniumProvider_missingWithoutUrlExcludedFromDenominator() {
        // 2 страницы (40 фильмов), но missing без URL не учитывается в знаменателе.
        int pagesCeil = 2;
        int missingWithUrl = 3;
        int missingWithoutUrl = 2;

        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            m.setNameEn("En " + i); // по умолчанию nameEn заполнен (не missing)
            movies.add(m);
        }
        // Помечаем 5 фильмов как missing (без nameEn); у 2 из них убираем и kpId, и kpUrl.
        for (int i = 0; i < missingWithUrl + missingWithoutUrl; i++) {
            movies.get(i).setNameEn(null);
        }
        movies.get(0).setKpId(null);
        movies.get(0).setKpUrl(null);
        movies.get(1).setKpId(null);
        movies.get(1).setKpUrl(null);

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int pg = 0; pg < pagesCeil; pg++) {
                p.advance(ImportProgress.PHASE_KP, "Страница " + (pg + 1), "kp");
            }
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());

        // Фаза 2 обрабатывает только missing-фильмы с URL (3), по 1 advance за каждый.
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int i = 0; i < missingWithUrl; i++) {
                p.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
            }
            return null;
        }).when(notesImporter).fetchOriginalTitles(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));

        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil);

        provider.fetchRatings(1L, null, progress);

        // Знаменатель = pagesScanned(2) + missingWithUrl(3) = 5 (без учёта missing без URL).
        assertEquals(pagesCeil + missingWithUrl, progress.getTotal());
        assertEquals(progress.getTotal(), progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

}
