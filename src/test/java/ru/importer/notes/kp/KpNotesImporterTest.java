package ru.importer.notes.kp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

}
