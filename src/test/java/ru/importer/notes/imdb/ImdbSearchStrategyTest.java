package ru.importer.notes.imdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void evaluate_searchWithoutYear_twoExactTitleMatches_notFoundWithoutRating() {
        // ≥2 точных совпадений названия («Оно» 1990/2017) → неоднозначно → NOT_FOUND
        // без ставки и без imdb_id — защита от одноимённого ремейка.
        MovieData movie = completeMovie();
        movie.setYear(0); // поиск без года
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
        assertNull(movie.getImdbId(), "imdb_id при неоднозначности не записывается");
        // На страницы фильмов не переходили — оценку ставить некому.
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
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
}
