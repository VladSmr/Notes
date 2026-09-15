package ru.importer.notes.imdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты ImdbNotesExporter на моках WebDriver (без реального Chrome):
 * верификация прямого захода по imdbId и гейт типа страницы по JSON-LD @type —
 * как для тайтл-страницы, так и для страницы, открытой из выдачи поиска.
 */
class ImdbPageVerificationTest extends ImdbTestSupport {

    // ------------------------------------------------------------------
    // Стратегия PENDING + imdbId: верификация прямого захода, невалидный формат id
    // ------------------------------------------------------------------

    @Test
    void evaluate_pendingRow_pageOfAnotherMovie_resetsImdbIdAndSearches() {
        // PENDING + валидный tt-id, но открылась страница чужого фильма (API КП
        // иногда отдаёт неверный imdbId): сброс id и поиск по названию.
        MovieData movie = completeMovie();
        movie.setImdbId("tt0118767");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn("Some Other Movie (1999) - IMDb");

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver, times(1)).get("https://www.imdb.com/title/tt0118767/");
        assertNull(movie.getImdbId(), "imdbId чужого фильма должен быть сброшен");
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus(),
                "поиск пуст — NOT_FOUND: оценка по чужому id не ставилась");
    }

    @Test
    void evaluate_pendingRow_pageTitleYearMismatches_treatedAsAnotherMovie() {
        // Название страницы совпадает, но год другой — по такому id может скрываться другой фильм.
        MovieData movie = completeMovie(); // год 2022
        movie.setImdbId("tt13622970");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn("She-Hulk: Attorney at Law (TV Series 2019– ) - IMDb");

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertNull(movie.getImdbId(), "imdbId с чужим годом должен быть сброшен");
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
    }

    // ------------------------------------------------------------------
    // Верификация типа тайтла (JSON-LD @type) + допуск года ±1
    // ------------------------------------------------------------------

    @Test
    void extractJsonLdType_parsesStringArrayGraphAndToleratesMissingOrBroken() {
        assertEquals("Movie", ImdbPageVerifier.extractJsonLdType(
                "<html><script type=\"application/ld+json\">{\"@context\":\"https://schema.org\","
                        + "\"@type\":\"Movie\",\"name\":\"Undertone\",\"alternateName\":\"Полутон\","
                        + "\"datePublished\":\"2026-04-30\"}</script></html>"));
        // @type массивом (вариант разметки): допустимый элемент массива побеждает
        assertEquals("TVSeries", ImdbPageVerifier.extractJsonLdType(
                "<script type=\"application/ld+json\">{\"@type\":[\"TVSeries\",\"VideoObject\"]}</script>"));
        // @type внутри @graph
        assertEquals("PodcastEpisode", ImdbPageVerifier.extractJsonLdType(
                "<script type=\"application/ld+json\">{\"@graph\":"
                        + "[{\"@type\":\"PodcastEpisode\",\"name\":\"Undertone (2025)\"}]}</script>"));
        // Посторонний скрипт (BreadcrumbList) рядом с Movie: допустимый тип побеждает
        assertEquals("Movie", ImdbPageVerifier.extractJsonLdType(
                "<script type=\"application/ld+json\">{\"@type\":\"BreadcrumbList\"}</script>"
                        + "<script type=\"application/ld+json\">{\"@type\":\"Movie\"}</script>"));
        // Нет JSON-LD / битый JSON / null — проверка типа недоступна (фолбэк на title)
        assertNull(ImdbPageVerifier.extractJsonLdType("<html><body>no scripts</body></html>"));
        assertNull(ImdbPageVerifier.extractJsonLdType(
                "<script type=\"application/ld+json\">{не-json</script>"));
        assertNull(ImdbPageVerifier.extractJsonLdType(null));
    }

    @Test
    void pageMatchesMovie_imdbYearWithinOne_stillOurMovie() {
        // «Коммерсант»: год КП 2025, на IMDB 2026 — расхождение на 1 допускается.
        MovieData movie = completeMovie();
        movie.setName("Komersant");
        movie.setNameOriginal(null);
        movie.setNameEn("Komersant");
        movie.setYear(2025);

        WebDriver driver = titlePageDriver("Komersant (2026) - IMDb", pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Movie\",\"name\":\"Komersant\","
                        + "\"datePublished\":\"2026-04-30\"}"));

        assertTrue(verifier.pageMatchesMovie(driver, movie),
                "расхождение года на 1 (КП 2025, IMDB 2026) — это наш фильм");
    }

    @Test
    void pageMatchesMovie_imdbYearBeyondOne_anotherMovie() {
        MovieData movie = completeMovie();
        movie.setName("Komersant");
        movie.setNameOriginal(null);
        movie.setNameEn("Komersant");
        movie.setYear(2025);

        WebDriver driver = titlePageDriver("Komersant (2030) - IMDb", pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Movie\",\"name\":\"Komersant\","
                        + "\"datePublished\":\"2030-04-30\"}"));

        assertFalse(verifier.pageMatchesMovie(driver, movie),
                "расхождение года больше 1 — imdbId ведёт на другой фильм");
    }

    @Test
    void pageMatchesMovie_podcastEpisode_matchingTitle_rejected() {
        // Реальный кейс tt41621104: PodcastEpisode «Undertone (2025)», id которого API КП
        // выдал настоящим фильмам. Название совпадает буквально — отсечь может только @type.
        MovieData movie = completeMovie();
        movie.setName("Undertone");
        movie.setNameOriginal(null);
        movie.setNameEn("Undertone");
        movie.setYear(2025);

        WebDriver driver = titlePageDriver("Undertone (2025) - IMDb", pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"PodcastEpisode\","
                        + "\"name\":\"Undertone (2025)\",\"datePublished\":\"2026-03-25\"}"));

        assertFalse(verifier.pageMatchesMovie(driver, movie),
                "PodcastEpisode — гарантированно не наш фильм, даже при совпадающем названии");
    }

    @Test
    void pageMatchesMovie_movieType_accepted() {
        MovieData movie = completeMovie(); // She-Hulk 2022

        WebDriver driver = titlePageDriver("She-Hulk: Attorney at Law (2022) - IMDb", pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Movie\","
                        + "\"name\":\"She-Hulk: Attorney at Law\"}"));

        assertTrue(verifier.pageMatchesMovie(driver, movie), "@type Movie — верификация типа пройдена");
    }

    @Test
    void pageMatchesMovie_tvSeriesType_accepted() {
        // Сериалы из выгрузки пользователя проходят проверку типа через @type TVSeries.
        MovieData movie = completeMovie();
        movie.setName("Во все тяжкое");
        movie.setNameOriginal(null);
        movie.setNameEn("Breaking Bad");
        movie.setYear(2008);

        WebDriver driver = titlePageDriver("Breaking Bad (TV Series 2008–2013) - IMDb", pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"TVSeries\",\"name\":\"Breaking Bad\","
                        + "\"datePublished\":\"2008-01-20\"}"));

        assertTrue(verifier.pageMatchesMovie(driver, movie), "@type TVSeries — верификация типа пройдена");
    }

    @Test
    void pageMatchesMovie_otherNonFilmTypes_rejected() {
        MovieData movie = completeMovie();
        movie.setName("Undertone");
        movie.setNameOriginal(null);
        movie.setNameEn("Undertone");
        movie.setYear(2025);

        for (String type : new String[]{"TVEpisode", "VideoGame", "VideoGameSeries", "TVMovie"}) {
            WebDriver driver = titlePageDriver("Undertone (2025) - IMDb", pageWithJsonLd(
                    "{\"@type\":\"" + type + "\",\"name\":\"Undertone\"}"));
            assertFalse(verifier.pageMatchesMovie(driver, movie),
                    type + " — «прочее» по @type: гарантированно не наш фильм, нужен фолбэк на поиск");
        }
    }

    @Test
    void pageMatchesMovie_noJsonLd_fallsBackToTitleChecks() {
        // JSON-LD недоступен — прежняя проверка по <title>.
        MovieData movie = completeMovie();

        assertTrue(verifier.pageMatchesMovie(
                        titlePageDriver("She-Hulk: Attorney at Law (TV Series 2022– ) - IMDb",
                                "<html><body></body></html>"),
                        movie),
                "JSON-LD нет, title совпадает — наш фильм");
        assertFalse(verifier.pageMatchesMovie(
                        titlePageDriver("Some Other Movie (1999) - IMDb", null),
                        movie),
                "JSON-LD нет, title чужой — не наш фильм");
    }

    @Test
    void evaluate_pendingRow_podcastEpisodePage_fallsBackToSearch() {
        // Прямой заход PENDING+валидный id открыл подкаст-эпизод (название совпадает
        // буквально) — отсечение по типу, сброс id, фолбэк на поиск.
        MovieData movie = completeMovie();
        movie.setName("Undertone");
        movie.setNameOriginal(null);
        movie.setNameEn("Undertone");
        movie.setYear(2025);
        movie.setImdbId("tt41621104");

        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn("Undertone (2025) - IMDb");
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"PodcastEpisode\","
                        + "\"name\":\"Undertone (2025)\",\"datePublished\":\"2026-03-25\"}"));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        // Страница по id открыта ровно один раз (для проверки), повторно — нет.
        verify(driver, times(1)).get("https://www.imdb.com/title/tt41621104/");
        assertNull(movie.getImdbId(), "imdbId подкаст-эпизода сброшен");
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus(),
                "поиск пуст — NOT_FOUND: оценка эпизоду не ставилась");
    }

    @Test
    void evaluate_pendingRow_seriesJsonLdTvSeries_processedDirectly() {
        // Сериал из выгрузки: @type TVSeries проходит верификацию — прямой заход
        // не превращается в фолбэк на поиск.
        MovieData movie = completeMovie();
        movie.setName("Во все тяжкое");
        movie.setNameOriginal(null);
        movie.setNameEn("Breaking Bad");
        movie.setYear(2008);
        movie.setImdbId("tt0903747");

        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn("Breaking Bad (TV Series 2008–2013) - IMDb");
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"TVSeries\",\"name\":\"Breaking Bad\","
                        + "\"datePublished\":\"2008-01-20\"}"));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver).get("https://www.imdb.com/title/tt0903747/");
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertEquals("tt0903747", movie.getImdbId(), "imdbId сериала не сбрасывается");
    }

    // ------------------------------------------------------------------
    // Тип страницы, открытой из выдачи поиска (единственный точный матч — эпизод)
    // ------------------------------------------------------------------

    @Test
    void evaluate_searchPath_podcastEpisodeExactMatch_notFoundWithoutRating() {
        // Реальный кейс tt41621104: сам фильм в выдаче отсутствует, единственный точный
        // матч — эпизод подкаста с тем же названием. Гейт типа отсекает: сброс id + NOT_FOUND.
        MovieData movie = completeMovie();
        movie.setName("Undertone");
        movie.setNameOriginal(null);
        movie.setNameEn("Undertone");
        movie.setYear(2025);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("Undertone (2025)");
        when(result.getAttribute("href")).thenReturn("/title/tt41621104/");

        WebDriver driver = mockDriver(List.of(result));
        // <title> страницы эпизода может совпадать с фильмом — на этом пути не читается:
        when(driver.getTitle()).thenReturn("Undertone (2025) - IMDb");
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"PodcastEpisode\","
                        + "\"name\":\"Undertone (2025)\",\"datePublished\":\"2026-03-25\"}"));

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus(),
                "единственный точный матч — эпизод подкаста: NOT_FOUND, а не ставка/ошибка");
        assertNull(movie.getImdbId(), "imdb_id эпизода не должен записываться");
        // До ставки дело не дошло: виджет оценки даже не искался.
        verify(driver, never()).findElement(any());
        // На страницу эпизода переход был (после него — проверка типа и отказ).
        verify(driver, times(1)).get(org.mockito.ArgumentMatchers.contains("/title/tt41621104"));
    }

    @Test
    void evaluate_searchPath_movieType_ratingProceedsNormally() {
        // @type Movie — гейт типа пропускает, доходим до попытки ставки.
        MovieData movie = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(result.getAttribute("href")).thenReturn("/title/tt13622970/?ref_=fn_tt_tt");

        WebDriver driver = mockDriver(List.of(result));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Movie\","
                        + "\"name\":\"She-Hulk: Attorney at Law\"}"));
        when(driver.getTitle()).thenReturn("She-Hulk: Attorney at Law (2022) - IMDb");

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        // Ставка ДОЛЖНА была начаться; на моках она неизбежно падает → ERROR.
        assertEquals(MovieStatus.ERROR, movie.getStatus(),
                "Movie — верификация типа пройдена, дошли до попытки ставки");
        assertEquals("tt13622970", movie.getImdbId(), "imdb_id Movie не сбрасывается");
        verify(driver, atLeastOnce()).findElement(any());
        verify(driver, atLeastOnce()).get(org.mockito.ArgumentMatchers.contains("/title/tt13622970"));
    }

    @Test
    void evaluate_searchPath_tvSeriesType_ratingProceedsNormally() {
        // Сериалы проходят гейт типа через @type TVSeries — без ложного NOT_FOUND.
        MovieData movie = completeMovie();
        movie.setName("Во все тяжкое");
        movie.setNameOriginal(null);
        movie.setNameEn("Breaking Bad");
        movie.setYear(2008);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("Breaking Bad (TV Series 2008–2013)");
        when(result.getAttribute("href")).thenReturn("/title/tt0903747/");

        WebDriver driver = mockDriver(List.of(result));
        when(driver.getPageSource()).thenReturn(pageWithJsonLd(
                "{\"@context\":\"https://schema.org\",\"@type\":\"TVSeries\",\"name\":\"Breaking Bad\"}"));
        when(driver.getTitle()).thenReturn("Breaking Bad (TV Series 2008–2013) - IMDb");

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.ERROR, movie.getStatus(),
                "TVSeries — верификация типа пройдена, дошли до попытки ставки");
        assertEquals("tt0903747", movie.getImdbId());
    }

    @Test
    void evaluate_searchPath_noJsonLd_previousBehavior_ratingStillAttempted() {
        // JSON-LD отсутствует вовсе — легитимный вариант, гейт не блокирует ставку.
        MovieData movie = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(result.getAttribute("href")).thenReturn("/title/tt13622970/");

        WebDriver driver = mockDriver(List.of(result));
        when(driver.getPageSource()).thenReturn("<html><body>no ld+json</body></html>");
        when(driver.getTitle()).thenReturn("She-Hulk: Attorney at Law (2022) - IMDb");

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.ERROR, movie.getStatus(),
                "JSON-LD нет — прежнее поведение: дошли до попытки ставки");
        assertEquals("tt13622970", movie.getImdbId());
    }
}
