package ru.importer.notes.movie;

import java.util.List;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieData.MovieStatus;
import ru.importer.notes.dto.ValidateResult;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

/**
 * Оркестратор двух независимых этапов работы приложения:
 *
 * <ul>
 *   <li><b>«Парсинг»</b> ({@code STAGE_PARSING}) — получение оценок пользователя с Кинопоиска
 *       одним из способов (selenium | api) и сохранение их в дамп {@code kp-ratings.csv}.
 *       IMDB на этом этапе не участвует.</li>
 *   <li><b>«Проставление»</b> ({@code STAGE_PROSET}) — чтение дампа {@code kp-ratings.csv}
 *       (провайдер {@code saved}) и проставление оценок на IMDB. Кинопоиск не участвует.</li>
 * </ul>
 *
 * Оба этапа выполняются в отдельных фоновых потоках. Одновременный запуск парсинга и
 * проставления запрещён: перед стартом каждого этапа занимается «слот» координатора
 * {@link ProcessCoordinator#tryBegin(String)}, а освобождается надёжно в {@code finally}
 * фонового потока. Общий прогресс и результат этапов хранятся в {@link ImportProgress}
 * (с пометкой этапа-производителя {@code completedStage}). Подготовка браузера между этапами
 * сериализуется через {@link #browserLock}, т.к. {@code AuthManager} держит один {@code WebDriver}.
 */
@Service
public class Processor {

    private static final Logger log = LoggerFactory.getLogger(Processor.class);

    private static final String ERROR = "error";
    private static final String ERROR_MESSAGE = "errorMessage";
    private static final String PARSER_SELENIUM = "selenium";
    private static final String PARSER_API = "api";
    private static final String PARSER_SAVED = "saved";

    private static final String STAGE_PARSING = "parsing";
    private static final String STAGE_PROSET = "proset";

    private final AuthManager authManager;
    private final LogFileService logFile;
    private final ImdbNotesExporter notesExporter;
    private final List<KpRatingsProvider> kpProviders;
    private final ImportProgress progress;
    private final ProcessCoordinator coordinator;

    /**
     * Сериализует подготовку браузера (открытие/навигацию) между этапами. AuthManager
     * держит ОДИН WebDriver, поэтому одновременные подготовительные фазы парсинга и
     * проставления не должны переплетаться (иначе драйвер может быть переприсвоен посреди процесса).
     */
    private final Object browserLock = new Object();

    public Processor(AuthManager authManager, ImdbNotesExporter notesExporter,
                     List<KpRatingsProvider> kpProviders, LogFileService logFile,
                     ImportProgress progress,
                     ProcessCoordinator coordinator) {
        this.authManager = authManager;
        this.notesExporter = notesExporter;
        this.kpProviders = kpProviders;
        this.logFile = logFile;
        this.progress = progress;
        this.coordinator = coordinator;
    }

    private AppResult buildResult(List<MovieData> movies) {
        AppResult r = new AppResult();
        r.setTotalMovies(movies.size());
        r.setMovies(movies);
        r.setRated((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.RATED).count());
        r.setNotFound((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.NOT_FOUND).count());
        r.setSkippedSame((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.SKIPPED_SAME).count());
        r.setSkippedDifferent((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.SKIPPED_DIFFERENT).count());
        r.setErrors((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.ERROR).count());
        return r;
    }

    private String escapeCsv(String value) {
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ------------------------------------------------------------------
    // ЭТАП «ПАРСИНГ» (только Кинопоиск -> kp-ratings.csv)
    // ------------------------------------------------------------------

    /**
     * Шаг подготовки парсинга: валидация, установка директории логов.
     * Если kp-ratings.csv уже существует — показываем предупреждение о перезаписи
     * (подтверждение), иначе сразу открываем браузер КП (если нужен) и страницу входа.
     */
    public String prepareParsing(InputData inputData, Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute(ERROR_MESSAGE, "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения, прежде чем запускать новый.");
            return ERROR;
        }
        String parserType = validateAndSetupParsing(inputData, model);
        if (parserType == null) {
            return ERROR;
        }
        if (logFile.existsKpDump()) {
            log.warn("Парсинг: kp-ratings.csv уже существует — требуется подтверждение перезаписи");
            return "confirm-overwrite";
        }
        return openParsingBrowser(inputData, parserType, model);
    }

    /**
     * Подтверждение перезаписи существующего дампа: открывает браузер КП (если нужен)
     * и страницу входа в Кинопоиск.
     */
    public String confirmParsingOverwrite(InputData inputData, Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute(ERROR_MESSAGE, "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            return ERROR;
        }
        String parserType = validateAndSetupParsing(inputData, model);
        if (parserType == null) {
            return ERROR;
        }
        return openParsingBrowser(inputData, parserType, model);
    }

    /**
     * Общая для обоих шагов парсинга валидация + установка директории логов + заполнение
     * атрибутов модели.
     *
     * @return нормализованный способ парсинга или null (ошибка уже записана в модель)
     */
    private String validateAndSetupParsing(InputData inputData, Model model) {
        String parserType = normalizeParserType(inputData.getParserType());
        if (parserType == null || PARSER_SAVED.equals(parserType)) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            return null;
        }
        ValidateResult result = validateInputData(inputData, parserType);
        if (result.isHasError()) {
            model.addAttribute(ERROR_MESSAGE, result.getErrorMessage());
            return null;
        }
        logFile.setLogDir(inputData.getLogDirectory());
        fillParsingAttrs(model, inputData, parserType);
        return parserType;
    }

    private void fillParsingAttrs(Model model, InputData inputData, String parserType) {
        model.addAttribute("kpUserId", inputData.getKpUserId());
        model.addAttribute("logDirectory", inputData.getLogDirectory());
        model.addAttribute("parserType", parserType);
        model.addAttribute("apiToken", inputData.getApiToken() != null ? inputData.getApiToken() : "");
    }

    /**
     * Открывает браузер для парсинга КП (только для selenium; для api браузер не нужен).
     * IMDB на этом этапе НЕ участвует — imdb.com не открывается, вход в IMDB не проверяется.
     */
    private String openParsingBrowser(InputData inputData, String parserType, Model model) {
        KpRatingsProvider provider = resolveProvider(parserType);

        Integer realTotalRatings = null;
        boolean needsBrowser = PARSER_SELENIUM.equals(parserType);
        if (needsBrowser) {
            synchronized (browserLock) {
                if (authManager.getDriver() == null) {
                    authManager.openBrowserAndWaitLogin();
                    realTotalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
                }
            }
        }

        Integer totalRatings;
        if (PARSER_SELENIUM.equals(parserType)) {
            totalRatings = realTotalRatings;
        } else {
            totalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
        }
        model.addAttribute("realTotalRatings", realTotalRatings);
        model.addAttribute("totalRatings", totalRatings);
        return "login-kp";
    }

    /**
     * Запускает этап «Парсинг» в фоновом потоке: парсинг КП выбранным способом
     * (selenium | api) и сохранение результата в kp-ratings.csv.
     * IMDB не участвует.
     *
     * Слот координатора занимается ТОЛЬКО после всех валидаций, непосредственно перед
     * стартом потока — ранние возвраты не трогают слот, а освобождение происходит
     * надёжно в {@code finally} фонового потока.
     */
    public String startParsing(Long kpUserId, String logDirectory, String parserType, String apiToken,
                               Model model) {
        String type = normalizeParserType(parserType);
        if (type == null || PARSER_SAVED.equals(type)) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            log.error("startParsing: неверный способ парсинга: {}", parserType);
            return ERROR;
        }
        logFile.setLogDir(logDirectory);
        KpRatingsProvider provider = resolveProvider(type);
        if (provider == null) {
            model.addAttribute(ERROR_MESSAGE, "Не найден провайдер для способа: " + type);
            log.error("startParsing: не найден провайдер для способа: {}", type);
            return ERROR;
        }
        if (PARSER_API.equals(type) && (apiToken == null || apiToken.isBlank())) {
            model.addAttribute(ERROR_MESSAGE, "Для способа " + type + " не указан токен");
            log.error("startParsing: для способа {} не указан токен", type);
            return ERROR;
        }
        if (kpUserId == null) {
            model.addAttribute(ERROR_MESSAGE, "Не указан ID пользователя КП");
            log.error("startParsing: не указан ID пользователя КП");
            return ERROR;
        }
        boolean needsBrowser = PARSER_SELENIUM.equals(type);
        if (needsBrowser && authManager.getDriver() == null) {
            model.addAttribute(ERROR_MESSAGE, "Браузер КП не открыт — вернитесь назад и повторите подготовку");
            log.error("startParsing: браузер КП не открыт");
            return ERROR;
        }

        if (!coordinator.tryBegin(STAGE_PARSING)) {
            model.addAttribute(ERROR_MESSAGE, "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            log.error("startParsing: процесс уже идёт (этап: {})", coordinator.getStage());
            return ERROR;
        }

        long userId = kpUserId;
        log.info("Запускаю парсинг для пользователя КП {}, способ {}, лог-директория: {}",
                kpUserId, provider.getKey(), logDirectory);
        new Thread(() -> runParsingAsync(userId, provider, apiToken), "parsing-thread").start();
        return "importing-parsing";
    }

    private void runParsingAsync(long kpUserId, KpRatingsProvider provider, String apiToken) {
        List<MovieData> movies = null;
        try {
            progress.init(0);
            log.info("=== Парсинг начат ===");
            log.info("Пользователь КП: {}, способ: {}", kpUserId, provider.getKey());

            movies = provider.fetchRatings(kpUserId, apiToken, progress, this::saveKpDumpSafely);
            log.info("Загружено фильмов: {}", movies.size());

            if (PARSER_API.equals(provider.getKey())) {
                logApiVsReal(movies.size());
            }

            if (movies.isEmpty()) {
                log.error("Парсинг остановлен: оценок не найдено");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("No ratings found for KP user " + kpUserId);
                progress.complete(STAGE_PARSING, errorResult);
                return;
            }
            if (progress.isAborted()) {
                log.info("Парсинг остановлен пользователем");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("Parsing stopped by user.");
                progress.complete(STAGE_PARSING, errorResult);
                return;
            }

            logKpWarnings(movies);
            saveKpDumpSafely(movies);
            log.info("Дамп сохранён: {} фильмов в kp-ratings.csv", movies.size());

            AppResult appResult = new AppResult();
            appResult.setTotalMovies(movies.size());
            appResult.setMovies(movies);
            progress.complete(STAGE_PARSING, appResult);
            log.info("=== Парсинг завершён ===");
        } catch (Exception e) {
            log.error("Парсинг провалился: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    saveKpDumpSafely(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Parsing failed: " + e.getMessage());
            progress.complete(STAGE_PARSING, errorResult);
        } finally {
            coordinator.finish();
        }
    }

    // ------------------------------------------------------------------
    // ЭТАП «ПРОСТАВЛЕНИЕ» (только IMDB, источник — kp-ratings.csv)
    // ------------------------------------------------------------------

    /**
     * Подготовка проставления: устанавливает директорию, проверяет наличие дампа,
     * открывает браузер IMDB и показывает страницу входа в IMDB.
     * Кинопоиск на этом этапе НЕ участвует.
     */
    public String prepareProsetting(String logDirectory, Model model) {
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
     * Запускает этап «Проставление» в фоновом потоке. Перед запуском проверяет вход в IMDB
     * через {@link AuthManager#isLoggedIn()}; если пользователь не залогинен — не начинает
     * проставление и возвращается на страницу входа с сообщением.
     *
     * Слот координатора занимается ТОЛЬКО после всех проверок, непосредственно перед
     * стартом потока — ранние возвраты не трогают слот, а освобождение происходит
     * надёжно в {@code finally} фонового потока.
     */
    public String startProset(String logDirectory, Model model) {
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

        KpRatingsProvider provider = resolveProvider(PARSER_SAVED);
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
            notesExporter.evaluate(movies, driver, progress, () -> saveKpDumpSafely(imported));

            saveKpDumpSafely(movies);
            log.info("Итоговый дамп со статусами сохранён в kp-ratings.csv");

            AppResult appResult = buildResult(movies);
            progress.complete(STAGE_PROSET, appResult);
            log.info("=== Проставление завершено ===");
        } catch (Exception e) {
            log.error("Проставление провалилось: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    saveKpDumpSafely(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Prosetting failed: " + e.getMessage());
            progress.complete(STAGE_PROSET, errorResult);
        } finally {
            coordinator.finish();
        }
    }

    // ------------------------------------------------------------------
    // Общие помощники
    // ------------------------------------------------------------------

    private void saveKpDump(List<MovieData> movies) {
        logFile.saveKpDump(buildKpDumpLines(movies));
    }

    /**
     * Сохраняет дамп, не роняя текущий этап. Различает две ситуации:
     * <ul>
     *   <li><b>«файл занят другим процессом»</b> (например, открыт в Excel) — попытка 1 —
     *       повтор через 5 с, попытка 2 — через 30 с, затем этап встаёт на паузу, а на вебе
     *       появляется кнопка «Продолжить». После её нажатия — одна немедленная попытка;
     *       если файл всё ещё занят, снова пауза с сообщением, что файл ещё открыт.</li>
     *   <li><b>«директория недоступна / нет прав на запись»</b> — этап встаёт на паузу со
     *       статусом {@code need-new-dir}, пользователь указывает новый путь (эндпоинт
     *       {@code /change-log-dir}), путь применяется и запись повторяется. Если и новый
     *       путь не подходит — снова запрос нового пути (цикл).</li>
     * </ul>
     * Метод работает и для парсинга, и для проставления (проставление вызывает его через
     * колбэк {@code ImdbNotesExporter}).
     */
    private void saveKpDumpSafely(List<MovieData> movies) {
        int attempt = 1;
        boolean justResumed = false;
        while (true) {
            try {
                saveKpDump(movies);
                return;
            } catch (Exception e) {
                if (progress.isAborted()) {
                    log.warn("Процесс остановлен, дамп не сохранён: {}", e.getMessage());
                    return;
                }
                if (isPermissionOrDirError(e)) {
                    log.warn("Директория дампа недоступна или нет прав на запись: {}", e.getMessage());
                    String newDir = requestNewDir();
                    if (newDir == null) {
                        // Пользователь остановил процесс во время ожидания нового пути.
                        return;
                    }
                    try {
                        logFile.setLogDir(newDir);
                    } catch (Exception dirErr) {
                        log.warn("Не удалось применить новый путь {}: {}", newDir, dirErr.getMessage());
                        continue;
                    }
                    // Повторяем запись накопленного списка в новый путь сразу.
                    justResumed = false;
                    attempt = 1;
                    continue;
                }
                // Файл занят другим процессом — текущее поведение с ретраями и паузой.
                if (justResumed) {
                    log.warn("Файл kp-ratings.csv всё ещё занят после «Продолжить»: {}", e.getMessage());
                    progress.pause("paused-still-busy");
                    progress.waitWhilePaused();
                    if (progress.isAborted()) {
                        return;
                    }
                    continue;
                }
                long delay = attempt == 1 ? 5000 : 30000;
                log.warn("Не удалось сохранить kp-ratings.csv (попытка {}): {} — повтор через {} с",
                        attempt, e.getMessage(), delay / 1000);
                sleepUninterruptibly(delay);
                if (++attempt > 2) {
                    log.warn("kp-ratings.csv всё ещё занят. Процесс на паузе: закройте файл и нажмите «Продолжить».");
                    progress.pause("paused");
                    progress.waitWhilePaused();
                    if (progress.isAborted()) {
                        return;
                    }
                    justResumed = true;
                }
            }
        }
    }

    /**
     * Ставит этап на паузу со статусом «нужен новый путь» и ждёт, пока пользователь введёт
     * новый путь (через {@link #changeLogDir(String)}) или остановит процесс.
     *
     * @return новый путь или null, если процесс остановлен пользователем
     */
    private String requestNewDir() {
        while (true) {
            progress.pause("need-new-dir");
            progress.waitWhilePaused();
            if (progress.isAborted()) {
                return null;
            }
            String newDir = progress.consumeNewLogDir();
            if (newDir != null && !newDir.isBlank()) {
                return newDir;
            }
            // Пользователь нажал «Продолжить», не указав путь — снова запрашиваем путь.
        }
    }

    /**
     * Применяет новый путь для дампа, введённый пользователем во время паузы «нужен новый путь».
     * Вызывается из эндпоинта {@code /change-log-dir}. Устанавливает путь и будит фоновый
     * поток, который перезапишет накопленный дамп в новый путь.
     *
     * @param newDir новый путь к директории дампа
     */
    public void changeLogDir(String newDir) {
        if (newDir == null || newDir.isBlank()) {
            throw new IllegalArgumentException("Пустой путь к директории");
        }
        progress.setNewLogDir(newDir);
        progress.resume();
    }

    /**
     * Определяет, является ли ошибка записи следствием «директория недоступна / нет прав
     * на запись» (в отличие от «файл занят другим процессом»). Проходит по всей цепочке
     * причин исключения.
     */
    static boolean isPermissionOrDirError(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.nio.file.AccessDeniedException) {
                return true;
            }
            if (t instanceof java.io.FileNotFoundException) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (msg.contains("отказано") || msg.contains("access is denied")
                        || msg.contains("permission") || msg.contains("denied")) {
                    return true;
                }
                // FileNotFoundException без «отказано в доступе» — обычно файл не найден,
                // а не проблема прав; трактуем как «файл занят» (текущее поведение).
                return false;
            }
            if (t instanceof java.io.IOException) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (msg.contains("being used by another process")
                        || msg.contains("занят") || msg.contains("used by another")) {
                    return false;
                }
                if (msg.contains("отказано") || msg.contains("access is denied")
                        || msg.contains("permission") || msg.contains("denied")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String[] buildKpDumpLines(List<MovieData> movies) {
        return movies.stream()
                     .map(m -> {
                         String name = m.getName() != null ? escapeCsv(m.getName()) : "";
                         String nameOriginal = m.getNameOriginal() != null ? escapeCsv(m.getNameOriginal()) : "";
                         String nameEn = m.getNameEn() != null ? escapeCsv(m.getNameEn()) : "";
                         String year = m.getYear() > 0 ? String.valueOf(m.getYear()) : "";
                         String rating = m.getKpRating() > 0 ? String.valueOf(m.getKpRating()) : "";
                         String kpId = m.getKpId() != null ? String.valueOf(m.getKpId()) : "";
                         String imdbId = m.getImdbId() != null ? escapeCsv(m.getImdbId()) : "";
                         String status = m.getStatus() != null && m.getStatus() != MovieStatus.PENDING
                                 ? m.getStatusLabel() : "";
                         String error = m.getErrorMessage() != null ? escapeCsv(m.getErrorMessage()) : "";

                         return String.join(";",
                                            name,
                                            nameOriginal,
                                            nameEn,
                                            year,
                                            rating,
                                            kpId,
                                            imdbId,
                                            status,
                                            error
                         );
                     })
                     .toArray(String[]::new);
    }

    /**
     * Диагностический лог для API-парсинга: сколько записей вернул API.
     */
    private void logApiVsReal(int apiCount) {
        log.info("API-парсинг: вернул {} записей", apiCount);
        progress.advance(ImportProgress.PHASE_KP, "API вернул " + apiCount + " записей", "api");
    }

    /** Предупреждения о неполных данных КП-парсинга — логгируются один раз после загрузки списка. */
    private void logKpWarnings(List<MovieData> movies) {
        for (MovieData m : movies) {
            String name = m.getName() != null ? m.getName() : "";
            String year = m.getYear() > 0 ? String.valueOf(m.getYear()) : "";
            String nameOriginal = m.getNameOriginal();
            String nameEn = m.getNameEn();
            if (name.isBlank()) {
                log.error("Ошибка парсинга КП: пустое название (kpId={})", m.getKpId());
            } else if (year.isBlank()) {
                log.error("Ошибка парсинга КП: у фильма '{}' (kpId={}) не спарсен год", name, m.getKpId());
            }
            if (nameOriginal == null || nameOriginal.isBlank()) {
                if (nameEn == null || nameEn.isBlank()) {
                    log.warn("Внимание: у фильма '{}' ({} г., kpId={}) нет ни оригинального, ни английского названия — если страна не СНГ, это ошибка парсинга",
                            name, year, m.getKpId());
                } else {
                    log.warn("Внимание: у фильма '{}' ({} г., kpId={}) нет оригинального названия — если страна не СНГ, это ошибка парсинга",
                            name, year, m.getKpId());
                }
            }
        }
    }

    /**
     * Выбирает провайдера по способу.
     */
    private KpRatingsProvider resolveProvider(String parserType) {
        return kpProviders.stream()
                .filter(p -> p.getKey().equals(parserType))
                .findFirst()
                .orElse(null);
    }

    /** Нормализует способ парсинга; возвращает null для неизвестного. package-private для контроллера. */
    public static String normalizeParserType(String parserType) {
        if (parserType == null) {
            return null;
        }
        String t = parserType.trim().toLowerCase();
        if (PARSER_SELENIUM.equals(t) || PARSER_API.equals(t) || PARSER_SAVED.equals(t)) {
            return t;
        }
        return null;
    }

    private ValidateResult validateInputData(InputData inputData, String parserType) {
        ValidateResult result = new ValidateResult();
        if (inputData.getLogDirectory() == null || inputData.getLogDirectory().isBlank()) {
            result.setErrorMessage("empty log directory");
            result.setHasError(true);
            return result;
        }
        if (inputData.getKpUserId() == null) {
            result.setErrorMessage("empty KP user ID");
            result.setHasError(true);
            return result;
        }
        if (PARSER_API.equals(parserType)
                && (inputData.getApiToken() == null || inputData.getApiToken().isBlank())) {
            result.setErrorMessage("empty KP API token (нужен для способа API)");
            result.setHasError(true);
        }
        return result;
    }

}
