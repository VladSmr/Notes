package ru.importer.notes.movie;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.springframework.ui.ExtendedModelMap;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * По завершении этапов (нормально, по ошибке или по остановке — через {@code finally}
 * фонового потока) WebDriver закрывается, освобождая профиль Chrome. Все зависимости
 * замоканы — реальный Chrome не запускается.
 */
class ProcessorDriverCloseTest {

    /** Ждёт завершения фонового потока этапа (progress.complete()) не дольше timeoutMs. */
    private static void awaitStageCompleted(ImportProgress progress, long timeoutMs) {
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

    /**
     * Нормальное завершение парсинга (способ api): после завершения драйвер закрыт,
     * слот координатора освобождён.
     */
    @Test
    void parsingCompleted_closesDriver() {
        AuthManager authManager = mock(AuthManager.class);
        ImdbNotesExporter exporter = mock(ImdbNotesExporter.class);
        LogFileService logFile = mock(LogFileService.class);
        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();

        KpRatingsProvider apiProvider = providerWithKey("api");
        when(apiProvider.fetchRatings(eq(7L), eq("token"), any(ImportProgress.class), any()))
                .thenReturn(List.of(new MovieData()));

        Processor processor = new Processor(authManager, exporter, List.of(apiProvider), logFile, progress, coordinator);

        String view = processor.startParsing(7L, "C:\\tmp", "api", "token", 25, new ExtendedModelMap());

        assertEquals("importing-parsing", view);
        awaitStageCompleted(progress, 5000);

        assertEquals("parsing", progress.getCompletedStage());
        assertFalse(coordinator.isRunning());
        verify(authManager).closeDriver();
    }

    /**
     * Остановка пользователем посреди парсинга: этап завершается с ошибкой
     * «Parsing stopped by user», но драйвер всё равно закрывается.
     */
    @Test
    void parsingAbortedByUser_stillClosesDriver() {
        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));
        ImdbNotesExporter exporter = mock(ImdbNotesExporter.class);
        LogFileService logFile = mock(LogFileService.class);
        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();

        KpRatingsProvider seleniumProvider = providerWithKey("selenium");
        when(seleniumProvider.fetchRatings(any(), any(), any(ImportProgress.class), any()))
                .thenAnswer(inv -> {
                    ImportProgress p = inv.getArgument(2);
                    p.abort();
                    return List.of(new MovieData());
                });

        Processor processor = new Processor(authManager, exporter, List.of(seleniumProvider), logFile, progress, coordinator);

        String view = processor.startParsing(7L, "C:\\tmp", "selenium", null, 25, new ExtendedModelMap());

        assertEquals("importing-parsing", view);
        awaitStageCompleted(progress, 5000);

        assertEquals("parsing", progress.getCompletedStage());
        AppResult result = progress.getResult();
        assertEquals("Parsing stopped by user.", result.getErrorMessage());
        assertFalse(coordinator.isRunning());
        verify(authManager).closeDriver();
    }

    /** Нормальное завершение проставления: драйвер закрыт, слот освобождён. */
    @Test
    void prosetCompleted_closesDriver() {
        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));
        when(authManager.isLoggedIn()).thenReturn(true);
        ImdbNotesExporter exporter = mock(ImdbNotesExporter.class);
        LogFileService logFile = mock(LogFileService.class);
        when(logFile.selectKpDumpFile()).thenReturn(true);

        KpRatingsProvider savedProvider = providerWithKey("saved");
        when(savedProvider.fetchRatings(eq(0L), isNull(), any(ImportProgress.class)))
                .thenReturn(List.of(new MovieData()));

        ImportProgress progress = new ImportProgress();
        ProcessCoordinator coordinator = new ProcessCoordinator();
        Processor processor = new Processor(authManager, exporter, List.of(savedProvider), logFile, progress, coordinator);

        String view = processor.startProset("C:\\tmp", new ExtendedModelMap());

        assertEquals("importing-proset", view);
        awaitStageCompleted(progress, 5000);

        assertEquals("proset", progress.getCompletedStage());
        assertFalse(coordinator.isRunning());
        verify(authManager).closeDriver();
    }

    private static KpRatingsProvider providerWithKey(String key) {
        KpRatingsProvider provider = mock(KpRatingsProvider.class);
        when(provider.getKey()).thenReturn(key);
        return provider;
    }

}
