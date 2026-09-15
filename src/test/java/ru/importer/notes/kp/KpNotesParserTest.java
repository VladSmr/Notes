package ru.importer.notes.kp;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Парсинг списка фильмов КП: parseItem/parseDocument/parseTotalRatings,
 * склейка названия с годом (extract/stripTrailingGluedYear).
 */
class KpNotesParserTest extends KpTestSupport {

    @Test
    void parseItem_shouldExtractNameYearRatingAndId() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/12345/\">Начало</a>" +
                "<span class=\"year\">2010</span>" +
                "<span class=\"value\">8</span>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
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

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Без года", movie.getName());
        assertEquals(0, movie.getYear());
        assertEquals(0, movie.getKpRating());
        assertEquals(999L, movie.getKpId());
    }

    @Test
    void parseItem_shouldReturnNullWhenNoNameAndNoId() {
        String html = "<div class=\"item\"><span class=\"year\">2020</span></div>";
        assertNull(parser.parseItem(Jsoup.parse(html).select("div").first(), null));
    }

    @Test
    void parseItem_shouldHandleNegativeYearInput() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/1/\">Фильм</a>" +
                "<span class=\"year\">1999</span>" +
                "<span class=\"value\">10</span>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
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

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Начало", movie.getName());
        assertEquals("Inception", movie.getNameEn());
    }

    @Test
    void parseItem_shouldExtractYearFromTitle() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/456/\">Some Movie (1999)</a>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
        assertNotNull(movie);
        assertEquals("Some Movie", movie.getName());
        assertEquals(1999, movie.getYear());
    }

    @Test
    void parseItem_shouldNotTreatYearInTitleAsEnglishName() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/789/\">Inception (2010)</a>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div").first(), null);
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

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
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

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
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

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), 555L);
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

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), 555L);
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

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
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

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
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

        Integer total = parser.parseTotalRatings(Jsoup.parse(html));
        assertEquals(1530, total);
    }

    @Test
    void parseTotalRatings_shouldReturnNullWhenNoVotedWatchedLink() {
        String html = "<html><body>" +
                "<span class=\"styles_statValue__NuCuw\">5</span>" +
                "</body></html>";

        assertNull(parser.parseTotalRatings(Jsoup.parse(html)));
    }

    // ------------------------------------------------------------------
    // Склейка названия с годом («Мортал Комбат 22025»)
    // ------------------------------------------------------------------

    @Test
    void parseItem_shouldStripGluedYearWhenYearUnknown() {
        // Название и год слиплись без пробела, год нигде больше не найден —
        // хвост-год отрезается и используется как год фильма.
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/322/\">Мортал Комбат 22025, боевик</a>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), null);
        assertNotNull(movie);
        assertEquals("Мортал Комбат 2", movie.getName());
        assertEquals(2025, movie.getYear());
    }

    @Test
    void parseItem_shouldStripGluedYearWithoutGenreTail() {
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/322/\">Начало2010</a>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), null);
        assertNotNull(movie);
        assertEquals("Начало", movie.getName());
        assertEquals(2010, movie.getYear());
    }

    @Test
    void parseDocument_shouldStripGluedYearAfterMergeFromOtherContainer() {
        // Год подтягивается через merge из другого контейнера — склейка зачищается в итоге.
        String html = "<html><body>" +
                "<div class=\"cover\">" +
                "  <a href=\"/film/322/\"><img src=\"x.jpg\"></a>" +
                "  <span class=\"year\">2025</span>" +
                "</div>" +
                "<div class=\"info\">" +
                "  <a href=\"/film/322/\">Мортал Комбат 22025</a>" +
                "</div>" +
                "</body></html>";

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size());
        assertEquals("Мортал Комбат 2", movies.get(0).getName());
        assertEquals(2025, movies.get(0).getYear());
    }

    @Test
    void extractTrailingGluedYear_cases() {
        assertEquals(2025, GluedYearTitleCleaner.extractTrailingGluedYear("Мортал Комбат 22025"));
        assertEquals(2010, GluedYearTitleCleaner.extractTrailingGluedYear("Начало2010"));
        assertEquals(2021, GluedYearTitleCleaner.extractTrailingGluedYear("Сериал 2021-22"));
        assertEquals(0, GluedYearTitleCleaner.extractTrailingGluedYear("Матрица"));
        assertEquals(0, GluedYearTitleCleaner.extractTrailingGluedYear("Матрица 2, боевик"));
        assertEquals(0, GluedYearTitleCleaner.extractTrailingGluedYear(null));
    }

    @Test
    void stripTrailingGluedYear_safetyRules() {
        // Хвост совпадает с известным годом — отрезается.
        assertEquals("Мортал Комбат 2", GluedYearTitleCleaner.stripTrailingGluedYear("Мортал Комбат 22025", 2025));
        // Год неизвестен (0) — отрезаем: склейка с 4-значным годом очевидна.
        assertEquals("Мортал Комбат 2", GluedYearTitleCleaner.stripTrailingGluedYear("Мортал Комбат 22025", 0));
        // Хвост НЕ совпадает с известным годом — цифры являются частью названия, не трогаем.
        assertEquals("Космическая одиссея 2001", GluedYearTitleCleaner.stripTrailingGluedYear("Космическая одиссея 2001", 1968));
        // После отрезания ничего не остаётся (фильм называется «2012») — не трогаем.
        assertEquals("2012", GluedYearTitleCleaner.stripTrailingGluedYear("2012", 2012));
        // Хвоста-года нет — без изменений.
        assertEquals("Матрица", GluedYearTitleCleaner.stripTrailingGluedYear("Матрица", 1999));
        assertNull(GluedYearTitleCleaner.stripTrailingGluedYear(null, 1999));
    }

    // ------------------------------------------------------------------
    // Цифровой хвост из ≥5 цифр: шаг 1 — последние 4 цифры как год,
    // шаг 2 — первые 4 цифры хвоста (LONG_DIGIT_TAIL), иначе — как есть
    // ------------------------------------------------------------------

    @Test
    void extractTrailingGluedYear_longDigitTail_cases() {
        assertEquals(2026, GluedYearTitleCleaner.extractTrailingGluedYear("Рейс 2982026"));
        assertEquals(2025, GluedYearTitleCleaner.extractTrailingGluedYear("Название20251"));
        assertEquals(2025, GluedYearTitleCleaner.extractTrailingGluedYear("Хроники2025123456"));
        assertEquals(0, GluedYearTitleCleaner.extractTrailingGluedYear("Название12345"));
        assertEquals(0, GluedYearTitleCleaner.extractTrailingGluedYear("Кино123456"));
    }

    @Test
    void stripTrailingGluedYear_longDigitTail_safetyRules() {
        // Шаг 1: отрезаются только последние 4 цифры (валидный год), «298» остаётся.
        assertEquals("Рейс 298", GluedYearTitleCleaner.stripTrailingGluedYear("Рейс 2982026", 2026));
        assertEquals("Рейс 298", GluedYearTitleCleaner.stripTrailingGluedYear("Рейс 2982026", 0));

        // Шаг 2: отрезается весь N-цифровый хвост; extract и strip согласованы.
        assertEquals(2025, GluedYearTitleCleaner.extractTrailingGluedYear("Название20251"));
        assertEquals("Название", GluedYearTitleCleaner.stripTrailingGluedYear("Название20251", 2025));
        assertEquals("Название", GluedYearTitleCleaner.stripTrailingGluedYear("Название20251", 0));

        // Согласованность extract↔strip для N=10.
        assertEquals(2025, GluedYearTitleCleaner.extractTrailingGluedYear("Хроники2025123456"));
        assertEquals("Хроники", GluedYearTitleCleaner.stripTrailingGluedYear("Хроники2025123456", 2025));
        assertEquals("Хроники", GluedYearTitleCleaner.stripTrailingGluedYear("Хроники2025123456", 0));

        // knownYear-защита в fallback: первые 4 («2025») ≠ известный год (1968) — не трогаем.
        assertEquals("Название20251", GluedYearTitleCleaner.stripTrailingGluedYear("Название20251", 1968));
        assertEquals("Название12345", GluedYearTitleCleaner.stripTrailingGluedYear("Название12345", 0));
        assertEquals("Кино123456", GluedYearTitleCleaner.stripTrailingGluedYear("Кино123456", 0));
    }

    @Test
    void parseItem_shouldStripSevenDigitGluedTailWhenYearUnknown() {
        // 7-цифровая склейка: последние 4 цифры хвоста — валидный год.
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/298/\">Рейс 2982026</a>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), null);
        assertNotNull(movie);
        assertEquals("Рейс 298", movie.getName());
        assertEquals(2026, movie.getYear());
    }

    @Test
    void parseItem_shouldNotStripMeaningfulYearWhenRealYearKnown() {
        // «Космическая одиссея 2001» — 2001 часть названия; хвост-«2001» отрезать нельзя.
        String html = "<div class=\"item\">" +
                "<a class=\"name\" href=\"/film/555/\">Космическая одиссея 2001</a>" +
                "<span class=\"year\">1968</span>" +
                "</div>";

        MovieData movie = parser.parseItem(Jsoup.parse(html).select("div.item").first(), null);
        assertNotNull(movie);
        assertEquals("Космическая одиссея 2001", movie.getName());
        assertEquals(1968, movie.getYear());
    }

    @Test
    void parseDocument_shouldDeGlueCaptionTitleAndYear_realKpCardLayout() {
        // Реальная разметка карточки КП: заголовок и «год, жанр» в одной ссылке
        // БЕЗ ПРОБЕЛА, el.text() даёт склейку «...Prada 22026, драма» (такие склейки
        // попали в старые дампы). Парсер должен разклеить название и извлечь год.
        String html = "<html><body><div class=\"styles_item__S5nUo\">" +
                "<div class=\"styles_posterWrapper__mO7vb\">" +
                "<a href=\"/film/6373982/\" class=\"styles_posterLink__oK4I9\">" +
                "<img src=\"x.jpg\" alt=\"Дьявол носит Prada 2. 2026, драма\">" +
                "</a>" +
                "<div class=\"styles_overlaySlot__6mpXC\">" +
                "<span class=\"styles_value__NKB8e\">5</span>" +
                "</div>" +
                "</div>" +
                "<a href=\"/film/6373982/\" class=\"styles_captions__FtfU6\" tabindex=\"-1\">" +
                "<span class=\"styles_title__NNXAn\"><span><span>Дьявол носит Prada 2</span></span></span>" +
                "<span class=\"styles_subtitle__8QGYo\">2026, драма</span>" +
                "</a>" +
                "</div></body></html>";

        List<MovieData> movies = parser.parseDocument(Jsoup.parse(html));
        assertEquals(1, movies.size(), "постер и подпись — один фильм (дедуп по kp_id)");
        MovieData movie = movies.get(0);
        assertEquals(6373982L, movie.getKpId());
        assertEquals("Дьявол носит Prada 2", movie.getName(), "склейка с годом должна быть разклеена");
        assertEquals(2026, movie.getYear());
        assertEquals(5, movie.getKpRating());
    }
}
