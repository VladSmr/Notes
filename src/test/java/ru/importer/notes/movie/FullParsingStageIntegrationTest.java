package ru.importer.notes.movie;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openqa.selenium.WebDriver;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.client.HttpClientErrorException;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpQuotaExhaustedException;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Интеграционные тесты для способа «полный» (full): needsBrowser+токен,
 * no-ratings, нормализация и квота.
 */
class FullParsingStageIntegrationTest {

    private static InputData fullInput(String token) {
        InputData in = new InputData();
        in.setParserType("full");
        in.setKpUserId(7L);
        in.setLogDirectory("C:\\tmp");
        in.setApiToken(token);
        return in;
    }

    private static KpRatingsProvider fullProvider() {
        KpRatingsProvider p = mock(KpRatingsProvider.class);
        when(p.getKey()).thenReturn("full");
        return p;
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

    // ---- normalizeParserType ----

    @Test
    void normalizeParserType_full_returnsFull() {
        assertEquals("full", Processor.normalizeParserType("full"));
        assertEquals("full", Processor.normalizeParserType("Full"));
        assertEquals("full", Processor.normalizeParserType("  FULL  "));
    }

    @Test
    void normalizeParserType_unknown_returnsNull() {
        assertEquals("selenium", Processor.normalizeParserType("selenium"));
        assertEquals("api", Processor.normalizeParserType("api"));
        assertEquals("saved", Processor.normalizeParserType("saved"));
        assertEquals(null, Processor.normalizeParserType("unknown"));
        assertEquals(null, Processor.normalizeParserType(null));
    }

    // ---- validateInputData: full требует токен ----

    @Test
    void validateInputData_fullWithEmptyToken_hasError() {
        Processor p = processor(fullProvider());
        InputData in = fullInput("");
        in.setApiToken("");

        ru.importer.notes.dto.ValidateResult result = p.validateInputData(in, "full");

        assertTrue(result.isHasError());
        assertTrue(result.getErrorMessage().contains("нужен для способа full"));
    }

    @Test
    void validateInputData_fullWithNullToken_hasError() {
        Processor p = processor(fullProvider());
        InputData in = fullInput(null);

        ru.importer.notes.dto.ValidateResult result = p.validateInputData(in, "full");

        assertTrue(result.isHasError());
    }

    @Test
    void validateInputData_fullWithValidToken_noError() {
        Processor p = processor(fullProvider());

        ru.importer.notes.dto.ValidateResult result = p.validateInputData(fullInput("token"), "full");

        assertFalse(result.isHasError());
    }

    // ---- prepareParsing: валидация токена -> method-form без старта ----

    @Test
    void fullToken401_prepareParsing_returnsToFormWithoutStart() {
        KpRatingsProvider full = fullProvider();
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .when(full).validateToken(eq(7L), eq("bad-token"));
        ProcessCoordinator coordinator = new ProcessCoordinator();
        Processor processor = new Processor(mock(AuthManager.class), mock(ImdbNotesExporter.class),
                                            List.of(full), mock(LogFileService.class),
                                            new ImportProgress(), coordinator);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor.prepareParsing(fullInput("bad-token"), model);

        assertEquals("method-form", view);
        assertEquals("full", model.get("parserType"));
        assertFalse(coordinator.isRunning());
        verify(full, never()).fetchRatings(anyLong(), anyString(), any(), any());
        verify(full, never()).fetchTotalRatings(anyLong(), anyString());
    }

    @Test
    void fullTokenValid_prepareParsing_showsLoginKp() {
        KpRatingsProvider full = fullProvider();
        when(full.fetchTotalRatings(7L, "token")).thenReturn(25);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor(full).prepareParsing(fullInput("token"), model);

        assertEquals("login-kp", view);
        verify(full).validateToken(7L, "token");
        assertEquals(25, model.get("totalRatings"));
    }

    // ---- no-ratings для full ----

    @Test
    void fullZeroRatings_prepareParsing_showsNoRatingsPage() {
        KpRatingsProvider full = fullProvider();
        when(full.fetchTotalRatings(7L, "token")).thenReturn(0);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = processor(full).prepareParsing(fullInput("token"), model);

        assertEquals("no-ratings", view);
        assertEquals("full", model.get("parserType"));
        verify(full, never()).fetchRatings(anyLong(), anyString(), any(), any());
    }

    // ---- квота в процессе: координатор свободен + сообщение ----

    @Test
    void fullQuotaExhaustedDuringParsing_coordinatorFreeAndQuotaMessage() {
        KpRatingsProvider full = fullProvider();
        when(full.fetchRatings(eq(7L), eq("token"), any(ImportProgress.class), any()))
                .thenThrow(new KpQuotaExhaustedException("Квота исчерпана после 3 запросов (402)"));
        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();
        // Full требует браузер: мокаем AuthManager с WebDriver.
        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(Mockito.mock(WebDriver.class));
        // LogFileService: мокаем getKpDumpFileName для quotaErrorMessage().
        LogFileService logFile = mock(LogFileService.class);
        when(logFile.getKpDumpFileName()).thenReturn("kp-ratings-7-full.csv");
        Processor processor = new Processor(authManager, mock(ImdbNotesExporter.class),
                                            List.of(full), logFile,
                                            progress, coordinator);

        String view = processor.startParsing(7L, "C:\\tmp", "full", "token", 100, new ExtendedModelMap());

        assertEquals("importing-parsing", view);
        awaitCompleted(progress, 5000);

        assertEquals("parsing", progress.getCompletedStage());
        AppResult result = progress.getResult();
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Квота токена"),
                   "должно содержать текст о квоте, фактически: " + result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("kp-ratings-7-full.csv"),
                   "должно содержать имя дампа, фактически: " + result.getErrorMessage());
        // После завершения этапа координатор свободен (finish() вызван в finally).
        assertFalse(coordinator.isRunning());
    }
}