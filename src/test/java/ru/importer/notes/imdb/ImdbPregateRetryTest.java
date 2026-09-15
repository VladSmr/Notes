package ru.importer.notes.imdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты ImdbNotesExporter на моках WebDriver (без реального Chrome):
 * суженный прегейт «неполных данных» и ретраи статусов при повторных прогонах
 * (NOT_FOUND/ERROR/RATED/PENDING, невалидный формат imdbId).
 */
class ImdbPregateRetryTest extends ImdbTestSupport {

    // ------------------------------------------------------------------
    // «Неполные данные» (суженный прегейт): только нет названия ИЛИ оценка 0
    // ------------------------------------------------------------------

    @Test
    void isIncompleteData_shouldDetectMissingTitleOrZeroRating() {
        MovieData noTitle = completeMovie();
        noTitle.setName(null);
        noTitle.setNameOriginal(null);
        noTitle.setNameEn(null);
        assertTrue(exporter.isIncompleteData(noTitle), "пустые все три названия");

        MovieData onlyEn = completeMovie();
        onlyEn.setName(null);
        onlyEn.setNameOriginal(null);
        assertFalse(exporter.isIncompleteData(onlyEn), "заполнен только nameEn — ок");

        MovieData zeroRating = completeMovie();
        zeroRating.setKpRating(0);
        assertTrue(exporter.isIncompleteData(zeroRating), "оценка = 0 (кнопки «Rate 0» на IMDB нет)");

        assertFalse(exporter.isIncompleteData(completeMovie()), "полные данные");
    }

    @Test
    void isIncompleteData_titleAndRatingPresent_zeroYearIsNotIncomplete() {
        // Прегейт сужен: невалидный год — вопрос стратегии поиска, а не «неполные данные».
        MovieData zeroYear = completeMovie();
        zeroYear.setYear(0);
        assertFalse(exporter.isIncompleteData(zeroYear),
                "название и оценка есть, год = 0 — на IMDB идти можно");
    }

    @Test
    void evaluate_incompleteData_movieGoesNowhereAndGetsIncompleteStatus() {
        // rating = 0 — источник кейса «Rate 0» (~25 ошибок).
        MovieData movie = completeMovie();
        movie.setKpRating(0);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.INCOMPLETE_DATA, movie.getStatus());
        assertEquals("неполные данные", movie.getStatusLabel());
        verify(driver, never()).get(anyString());
    }

    @Test
    void evaluate_incompleteData_notRetriedOnNextRun() {
        // INCOMPLETE_DATA входит в isDone: повторного прогона быть не должно.
        MovieData movie = completeMovie();
        movie.setStatus(MovieStatus.INCOMPLETE_DATA);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.INCOMPLETE_DATA, movie.getStatus());
        verify(driver, never()).get(anyString());
    }

    // ------------------------------------------------------------------
    // «Не найден» ретраится при повторном прогоне, как «ошибка»;
    // RATED/INCOMPLETE_DATA — завершённые, не ретраятся
    // ------------------------------------------------------------------

    @Test
    void evaluate_notFoundRow_processedAgainOnNextRun_searchesAndResetsImdbId() {
        // NOT_FOUND не «завершённый» статус: при повторном прогоне фильм ищется заново.
        MovieData movie = completeMovie();
        movie.setImdbId("tt8887776");
        movie.setStatus(MovieStatus.NOT_FOUND);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        // Поиск не находит результатов → снова NOT_FOUND — это норма, статус ретраится.
        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertNull(movie.getImdbId(), "imdb_id прошлого прогона должен сбрасываться");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
    }

    @Test
    void evaluate_notFoundRow_freshlyFoundOnRetry_goesToTitlePage() {
        // Повторный прогон NOT_FOUND-фильма: сброс imdb_id, поиск, переход на найденную
        // страницу по href (навигация заново, а не по старому id).
        MovieData movie = completeMovie();
        movie.setImdbId("tt8887776");
        movie.setStatus(MovieStatus.NOT_FOUND);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebElement result = mock(WebElement.class);
        when(result.getText()).thenReturn("She-Hulk: Attorney at Law (2022)");
        when(result.getAttribute("href")).thenReturn("/title/tt13622970/?ref_=fn_tt_tt");

        WebDriver driver = mockDriver(List.of(result));
        WebDriverWait wait = mock(WebDriverWait.class);
        when(wait.until(any())).thenThrow(new TimeoutException());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals("tt13622970", movie.getImdbId(), "imdb_id перезаписан свежим результатом поиска");
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        verify(driver, atLeastOnce()).get(org.mockito.ArgumentMatchers.contains("/title/tt13622970"));
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("tt8887776"));
    }

    @Test
    void evaluate_ratedRow_skippedOnNextRun() {
        // RATED — завершённый статус: повторной обработки нет.
        MovieData movie = completeMovie();
        movie.setImdbId("tt0118767");
        movie.setStatus(MovieStatus.RATED);
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(MovieStatus.RATED, movie.getStatus());
        assertEquals("tt0118767", movie.getImdbId(), "imdb_id у обработанного фильма не трогается");
        verify(driver, never()).get(anyString());
    }

    // ------------------------------------------------------------------
    // Сброс устаревшего imdb_id при повторной обработке ERROR/NOT_FOUND-строк
    // ------------------------------------------------------------------

    @Test
    void evaluate_rerunOfErrorRow_resetsStaleImdbIdAndSearchesAgain() {
        // imdb_id из CSV мог быть получен по ошибочному совпадению — сброс и поиск заново,
        // иначе оценка ушла бы на посторонний фильм.
        MovieData movie = completeMovie();
        movie.setImdbId("tt9990001");
        movie.setStatus(MovieStatus.ERROR);
        movie.setErrorMessage("старая ошибка");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertNull(movie.getImdbId(), "устаревший imdb_id должен быть сброшен");
        assertNull(movie.getErrorMessage(), "старый текст ошибки больше не актуален");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
    }

    @Test
    void evaluate_freshPendingRow_keepsApiImdbIdAndOpensDirectly() {
        // PENDING из свежего дампа: imdb_id от API, формат валиден — страница
        // открывается напрямую без поиска, <title> соответствует фильму.
        MovieData movie = completeMovie();
        movie.setImdbId("tt0118767");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn("She-Hulk: Attorney at Law (TV Series 2022– ) - IMDb");
        WebDriverWait wait = mock(WebDriverWait.class);
        when(wait.until(any())).thenThrow(new TimeoutException());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver).get("https://www.imdb.com/title/tt0118767/");
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertEquals("tt0118767", movie.getImdbId(), "imdbId от API не должен сбрасываться");
    }

    @Test
    void evaluate_pendingRow_invalidImdbIdFormat_goesToSearchWithoutOpeningTitlePage() {
        // imdbId не формата tt\d+ («12345») — страницу не открываем, сбрасываем и ищем.
        MovieData movie = completeMovie();
        movie.setImdbId("12345");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertNull(movie.getImdbId(), "невалидный формат id сбрасывается");
        assertEquals(MovieStatus.NOT_FOUND, movie.getStatus());
    }

    @Test
    void evaluate_pendingRow_imdbIdTtx_goesToSearch() {
        MovieData movie = completeMovie();
        movie.setImdbId("ttx");
        List<MovieData> movies = new ArrayList<>(List.of(movie));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
        verify(driver).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        assertNull(movie.getImdbId(), "невалидный формат id сбрасывается");
    }
}
