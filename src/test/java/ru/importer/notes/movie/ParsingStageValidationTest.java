package ru.importer.notes.movie;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.client.HttpClientErrorException;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Предвалидация токена (Ф3а), фатальный финал (Ф3б) и запрет старта при 0 оценках (Ф4)
 * на этапе парсинга. Все зависимости замоканы — сеть/браузер не используются.
 */
class ParsingStageValidationTest {

    private static InputData apiInput(String token) {
        InputData in = new InputData();
        in.setParserType("api");
        in.setKpUserId(7L);
        in.setLogDirectory("C:\\tmp");
        in.setApiToken(token);
        return in;
    }

    private static KpRatingsProvider providerWithKey(String key) {
        KpRatingsProvider provider = mock(KpRatingsProvider.class);
        when(provider.getKey()).thenReturn(key);
        return provider;
    }

    private static Processor processor(KpRatingsProvider provider) {
        return new Processor(mock(AuthManager.class), mock(ImdbNotesExporter.class),
                             List.of(provider), mock(LogFileService.class),
                             new ImportProgress(), new ProcessCoordinator());
    }

    private static void awaitCompleted(ImportProgress progress, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (progress.getCompletedStage() == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Этап не завершился за отведённое время");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание этапа прервано", e);
            }
        }
    }

    /** Ф3а: невалидный токен (401) — остаёмся на форме данных, этап не стартует. */
    @Test
    void apiToken401_prepareParsing_returnsToFormAndDoesNotStart() {
        KpRatingsProvider api = providerWithKey("api");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .when(api).validateToken(eq(7L), eq("bad-token"));
        ProcessCoordinator coordinator = new ProcessCoordinator();
        Processor processor = new Processor(mock(AuthManager.class), mock(ImdbNotesExporter.class),
                                            List.of(api), mock(LogFileService.class),
                                            new ImportProgress(), coordinator);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor.prepareParsing(apiInput("bad-token"), model);

        assertEquals("method-form", view);
        assertEquals("Токен недействителен (401). Проверьте ключ КП", model.get("errorMessage"));
        assertEquals("api", model.get("parserType"), "введённые данные должны сохраниться для повторного ввода");
        assertFalse(coordinator.isRunning(), "слот координатора не должен заниматься");
        verify(api, never()).fetchRatings(any(), any(), any(), any());
        verify(api, never()).fetchTotalRatings(any(), any());
    }

    /** Ф3а (позитив): валидный токен — переходим на страницу входа как раньше. */
    @Test
    void apiTokenValid_prepareParsing_showsLoginKp() {
        KpRatingsProvider api = providerWithKey("api");
        when(api.fetchTotalRatings(7L, "token")).thenReturn(25);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor(api).prepareParsing(apiInput("token"), model);

        assertEquals("login-kp", view);
        verify(api).validateToken(7L, "token");
        assertEquals(25, model.get("totalRatings"));
    }

    /** Ф4: 0 оценок — страница «нет оценок», кнопка старта недоступна. */
    @Test
    void apiZeroRatings_prepareParsing_showsNoRatingsPage() {
        KpRatingsProvider api = providerWithKey("api");
        when(api.fetchTotalRatings(7L, "token")).thenReturn(0);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor(api).prepareParsing(apiInput("token"), model);

        assertEquals("no-ratings", view);
        assertEquals("api", model.get("parserType"));
        assertEquals(7L, model.get("kpUserId"));
        verify(api, never()).fetchRatings(any(), any(), any(), any());
    }

    /** Ф3б + Ф5: фатальная ошибка в процессе — финальный статус ошибки и свободный координатор. */
    @Test
    void fatalErrorDuringParsing_completesWithErrorAndFreesCoordinator() {
        KpRatingsProvider api = providerWithKey("api");
        when(api.fetchRatings(eq(7L), eq("token"), any(ImportProgress.class), any()))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();
        Processor processor = new Processor(mock(AuthManager.class), mock(ImdbNotesExporter.class),
                                            List.of(api), mock(LogFileService.class),
                                            progress, coordinator);

        String view = processor.startParsing(7L, "C:\\tmp", "api", "token", 25, new ExtendedModelMap());

        assertEquals("importing-parsing", view);
        awaitCompleted(progress, 5000);

        assertEquals("parsing", progress.getCompletedStage());
        AppResult result = progress.getResult();
        assertNotNull(result);
        assertTrue(result.getErrorMessage().contains("Токен недействителен (401). Проверьте ключ КП"),
                   "фактическое сообщение: " + result.getErrorMessage());
        assertFalse(coordinator.isRunning(), "после фатальной ошибки слот координатора должен быть свободен");
    }

    /** Ф3б (позитив): успешный финал сохраняет обычный результат. */
    @Test
    void successDuringParsing_completesWithResult() {
        KpRatingsProvider api = providerWithKey("api");
        MovieData movie = new MovieData();
        movie.setKpId(1L);
        when(api.fetchRatings(eq(7L), eq("token"), any(ImportProgress.class), any()))
                .thenReturn(List.of(movie));
        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();
        Processor processor = new Processor(mock(AuthManager.class), mock(ImdbNotesExporter.class),
                                            List.of(api), mock(LogFileService.class),
                                            progress, coordinator);

        processor.startParsing(7L, "C:\\tmp", "api", "token", 1, new ExtendedModelMap());
        awaitCompleted(progress, 5000);

        assertEquals("parsing", progress.getCompletedStage());
        assertNull(progress.getResult().getErrorMessage());
        assertFalse(coordinator.isRunning());
    }
}
