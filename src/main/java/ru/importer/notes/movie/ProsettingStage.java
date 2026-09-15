package ru.importer.notes.movie;

import static ru.importer.notes.movie.Processor.ERROR;
import static ru.importer.notes.movie.Processor.ERROR_MESSAGE;
import static ru.importer.notes.movie.Processor.PARSER_SAVED;
import static ru.importer.notes.movie.Processor.STAGE_PROSET;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.ui.Model;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.util.ErrorFormatter;

/**
 * Этап «Проставление» (только IMDB, источник — kp-ratings.csv): подготовка браузера IMDB
 * и фоновое проставление оценок из дампа. Подготовка браузера сериализуется через
 * {@code browserLock} — общий с этапом «Парсинг» объект (см. {@link Processor}); слот
 * {@link ProcessCoordinator} занимается перед стартом и освобождается в {@code finally}
 * фонового потока.
 */
@Slf4j
class ProsettingStage {

    private final Processor processor;
    private final AuthManager authManager;
    private final LogFileService logFile;
    private final ImdbNotesExporter notesExporter;
    private final ImportProgress progress;
    private final ProcessCoordinator coordinator;
    private final KpDumpWriter dumpWriter;

    /** Общий с {@link Processor} объект синхронизации подготовки браузера (см. {@link Processor#browserLock}). */
    private final Object browserLock;

    ProsettingStage(Processor processor, AuthManager authManager, LogFileService logFile,
                    ImdbNotesExporter notesExporter, ImportProgress progress,
                    ProcessCoordinator coordinator, KpDumpWriter dumpWriter, Object browserLock) {
        this.processor = processor;
        this.authManager = authManager;
        this.logFile = logFile;
        this.notesExporter = notesExporter;
        this.progress = progress;
        this.coordinator = coordinator;
        this.dumpWriter = dumpWriter;
        this.browserLock = browserLock;
    }

    /** Подготовка проставления: директория, наличие дампа, браузер IMDB и страница входа. */
    String prepareProsetting(String logDirectory, Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute(ERROR_MESSAGE, "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            return ERROR;
        }
        if (logDirectory == null || logDirectory.isBlank()) {
            model.addAttribute(ERROR_MESSAGE, "empty log directory");
            return ERROR;
        }
        logFile.setLogDir(logDirectory);
        if (!logFile.existsKpDump()) {
            model.addAttribute(ERROR_MESSAGE, "kp-ratings.csv not found in the log directory");
            return ERROR;
        }
        synchronized (browserLock) {
            WebDriver driver = authManager.getDriver();
            if (driver == null) {
                driver = authManager.openBrowserAndWaitLogin();
            }
            driver.get("https://www.imdb.com");
        }
        model.addAttribute("logDirectory", logDirectory);
        return "login-imdb";
    }

    /**
     * Запускает этап «Проставление» в фоновом потоке; при отсутствии входа в IMDB
     * возвращает на страницу входа. Слот координатора занимается только после всех
     * проверок; освобождается в {@code finally} фонового потока.
     */
    String startProset(String logDirectory, Model model) {
        if (logDirectory == null || logDirectory.isBlank()) {
            model.addAttribute(ERROR_MESSAGE, "empty log directory");
            log.error("startProset: пустая директория логов");
            return ERROR;
        }
        logFile.setLogDir(logDirectory);
        if (!logFile.existsKpDump()) {
            model.addAttribute(ERROR_MESSAGE, "kp-ratings.csv not found in the log directory");
            log.error("startProset: kp-ratings.csv не найден в {}", logDirectory);
            return ERROR;
        }
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            model.addAttribute(ERROR_MESSAGE, "Браузер не открыт — вернитесь назад и повторите подготовку");
            log.error("startProset: браузер не открыт");
            return ERROR;
        }
        if (!authManager.isLoggedIn()) {
            log.error("startProset: пользователь не залогинен в IMDB");
            model.addAttribute("logDirectory", logDirectory);
            model.addAttribute("loginError", "Вы не вошли в аккаунт IMDB. Войдите в браузере и нажмите «Да, я залогинился» снова.");
            return "login-imdb";
        }

        KpRatingsProvider provider = processor.resolveProvider(PARSER_SAVED);
        if (provider == null) {
            model.addAttribute(ERROR_MESSAGE, "Не найден провайдер для способа saved");
            log.error("startProset: не найден провайдер для способа saved");
            return ERROR;
        }

        if (!coordinator.tryBegin(STAGE_PROSET)) {
            model.addAttribute(ERROR_MESSAGE, "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            log.error("startProset: процесс уже идёт (этап: {})", coordinator.getStage());
            return ERROR;
        }

        log.info("Запускаю проставление оценок на IMDB из дампа в директории: {}", logDirectory);
        new Thread(() -> runProsetAsync(driver, provider), "proset-thread").start();
        return "importing-proset";
    }

    private void runProsetAsync(WebDriver driver, KpRatingsProvider provider) {
        List<MovieData> movies = null;
        try {
            log.info("=== Проставление начато ===");
            movies = provider.fetchRatings(0L, null, progress);
            if (movies.isEmpty()) {
                log.error("Проставление остановлено: дамп пуст");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("kp-ratings.csv is empty or has no rows.");
                progress.complete(STAGE_PROSET, errorResult);
                return;
            }
            List<MovieData> imported = movies;
            notesExporter.evaluate(movies, driver, progress, () -> dumpWriter.saveKpDumpSafely(imported));

            dumpWriter.saveKpDumpSafely(movies);
            log.info("Итоговый дамп со статусами сохранён в kp-ratings.csv");

            AppResult appResult = processor.buildResult(movies);
            progress.complete(STAGE_PROSET, appResult);
            log.info("=== Проставление завершено ===");
        } catch (Exception e) {
            log.error("Проставление провалилось: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    dumpWriter.saveKpDumpSafely(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Prosetting failed: " + e.getMessage());
            errorResult.setErrorDetails(ErrorFormatter.format(e));
            progress.complete(STAGE_PROSET, errorResult);
        } finally {
            // Браузер закрывается в любом исходе, чтобы профиль Chrome не остался заблокированным.
            synchronized (browserLock) {
                authManager.closeDriver();
            }
            coordinator.finish();
        }
    }

}
