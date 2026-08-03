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
import ru.importer.notes.kp.KpNotesImporter;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

@Service
public class Processor {

    private static final Logger log = LoggerFactory.getLogger(Processor.class);

    private static final String ERROR = "error";
    private static final String ERROR_MESSAGE = "errorMessage";
    private static final String PARSER_SELENIUM = "selenium";
    private static final String PARSER_API = "api";
    private static final String PARSER_HYBRID = "hybrid";
    private static final String PARSER_SAVED = "saved";
    private final AuthManager authManager;
    private final LogFileService logFile;
    private final ImdbNotesExporter notesExporter;
    private final List<KpRatingsProvider> kpProviders;
    private final KpNotesImporter notesImporter;
    private final ImportProgress progress;

    public Processor(AuthManager authManager, ImdbNotesExporter notesExporter,
                     List<KpRatingsProvider> kpProviders, LogFileService logFile,
                     KpNotesImporter notesImporter, ImportProgress progress) {
        this.authManager = authManager;
        this.notesExporter = notesExporter;
        this.kpProviders = kpProviders;
        this.logFile = logFile;
        this.notesImporter = notesImporter;
        this.progress = progress;
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

    /**
     * Открывает браузер (при способах с парсингом КП — проверяет реальное число оценок
     * на странице КП), затем показывает страницу для ручного входа на IMDB.
     *
     * @param inputData данные формы (KP userId + logDirectory + способ парсинга + токен)
     * @param model     модель для шаблона
     *
     * @return имя шаблона
     */
    public String openBrowser(InputData inputData, Model model) {
        String parserType = normalizeParserType(inputData.getParserType());
        if (parserType == null) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            return ERROR;
        }

        ValidateResult result = validateInputData(inputData, parserType);
        if (result.isHasError()) {
            model.addAttribute(ERROR_MESSAGE, result.getErrorMessage());
            return ERROR;
        }

        logFile.setLogDir(inputData.getLogDirectory());

        KpRatingsProvider provider = resolveProviderChecked(parserType);
        if (provider == null) {
            model.addAttribute(ERROR_MESSAGE,
                    "kp-ratings.csv not found in the log directory (для способа «Сохранённый дамп» нужен существующий дамп)");
            return ERROR;
        }

        Integer realTotalRatings = null;
        if (authManager.getDriver() == null) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();

            if (PARSER_SELENIUM.equals(parserType)) {
                realTotalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
            } else if (PARSER_SAVED.equals(parserType)) {
                driver.get("https://www.imdb.com");
            } else {
                realTotalRatings = notesImporter.fetchTotalRatings(driver, inputData.getKpUserId());
                driver.get("https://www.imdb.com");
            }
        }

        model.addAttribute("kpUserId", inputData.getKpUserId());
        model.addAttribute("logDirectory", inputData.getLogDirectory());
        model.addAttribute("parserType", parserType);
        model.addAttribute("apiToken", inputData.getApiToken() != null ? inputData.getApiToken() : "");
        model.addAttribute("realTotalRatings", realTotalRatings);

        Integer totalRatings = null;
        if (PARSER_SELENIUM.equals(parserType)) {
            totalRatings = realTotalRatings;
        } else {
            totalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
        }
        model.addAttribute("totalRatings", totalRatings);
        return "login-sites";
    }

    private void runImportAsync(long kpUserId, WebDriver driver, KpRatingsProvider provider, String apiToken,
                                Long realTotalRatings) {
        List<MovieData> movies = null;
        try {
            log.info("=== Импорт начат ===");
            log.info("Пользователь КП: {}, способ: {}", kpUserId, provider.getKey());
            log.info("Парсинг оценок с Кинопоиска...");

            movies = provider.fetchRatings(kpUserId, apiToken, progress);
            log.info("Загружено фильмов: {}", movies.size());

            if (PARSER_API.equals(provider.getKey())) {
                logApiVsReal(movies.size(), realTotalRatings);
            }

            if (movies.isEmpty()) {
                log.error("Импорт остановлен: оценок не найдено");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("No ratings found for KP user " + kpUserId);
                progress.complete(errorResult);
                return;
            }

            if (progress.isAborted()) {
                log.info("Импорт остановлен пользователем после парсинга КП");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("Import stopped by user.");
                progress.complete(errorResult);
                return;
            }

            if (!PARSER_SAVED.equals(provider.getKey())) {
                logKpWarnings(movies);
                saveKpDumpSafely(movies);
                log.info("Дамп сохранён: {} фильмов в kp-ratings.csv", movies.size());
            }

            driver.get("https://www.imdb.com");
            if (!authManager.isLoggedIn()) {
                log.error("Пользователь не залогинен в IMDB");
                AppResult errorResult = new AppResult();
                errorResult.setErrorMessage("You are not logged in to IMDB.");
                progress.complete(errorResult);
                return;
            }
            log.info("Вход в IMDB подтверждён, начинаем импорт");

            List<MovieData> imported = movies;
            notesExporter.evaluate(movies, driver, progress, () -> saveKpDumpSafely(imported));

            saveKpDumpSafely(movies);
            log.info("Итоговый дамп со статусами сохранён в kp-ratings.csv");

            AppResult appResult = buildResult(movies);
            progress.complete(appResult);
            log.info("=== Импорт завершён ===");
        } catch (Exception e) {
            log.error("Импорт провалился: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    saveKpDumpSafely(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Import failed: " + e.getMessage());
            progress.complete(errorResult);
        }
    }

    private void saveKpDump(List<MovieData> movies) {
        logFile.saveKpDump(buildKpDumpLines(movies));
    }

    /**
     * Сохраняет дамп, не роняя импорт. Если файл занят (например, открыт в Excel):
     * попытка 1 — повтор через 5 с, попытка 2 — через 30 с, затем импорт встаёт на паузу,
     * а на вебе появляется кнопка «Продолжить». После её нажатия — одна немедленная
     * попытка; если файл всё ещё занят, снова пауза с сообщением, что файл ещё открыт.
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
                    log.warn("Импорт остановлен, дамп не сохранён: {}", e.getMessage());
                    return;
                }
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
                    log.warn("kp-ratings.csv всё ещё занят. Импорт на паузе: закройте файл и нажмите «Продолжить».");
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
     * Сравнивает число записей, вернувшихся из API, с реальным числом оценок на КП
     * (из Selenium-проверки при открытии браузера) — лог и сообщение на страницу импорта.
     */
    private void logApiVsReal(int apiCount, Long realTotalRatings) {
        if (realTotalRatings == null) {
            log.info("API-парсинг: вернул {} записей", apiCount);
            progress.advance(ImportProgress.PHASE_KP, "API вернул " + apiCount + " записей", "api");
            return;
        }
        String msg;
        if (apiCount < realTotalRatings) {
            log.warn("API-парсинг: вернул только {} записей из {} на КП — не хватает {} (API отдаёт лишь последние ~1500 оценок)",
                    apiCount, realTotalRatings, realTotalRatings - apiCount);
            msg = "API вернул " + apiCount + " из " + realTotalRatings + " оценок (не хватает "
                    + (realTotalRatings - apiCount) + ")";
        } else {
            log.info("API-парсинг: вернул {} записей из {} на КП", apiCount, realTotalRatings);
            msg = "API вернул " + apiCount + " из " + realTotalRatings + " оценок";
        }
        progress.advance(ImportProgress.PHASE_KP, msg, "api");
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
     * Запускает импорт: загрузка данных выбранным способом (selenium | api | hybrid | saved),
     * затем проставление оценок на IMDB. Выполняется в фоновом потоке,
     * прогресс публикуется через ImportProgress.
     *
     * @param kpUserId          идентификатор пользователя КП (не нужен для способа saved)
     * @param logDirectory      директория для файлов результатов
     * @param parserType        способ парсинга КП (selenium | api | hybrid | saved)
     * @param apiToken          API-токен Кинопоиска (для api и hybrid)
     * @param realTotalRatings  реальное число оценок на КП (из проверки при открытии браузера)
     *
     * @return имя шаблона с результатами или ошибки
     */
    public String startImport(Long kpUserId, String logDirectory, String parserType, String apiToken,
                              Long realTotalRatings) {
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            log.error("startImport: браузер не открыт");
            return ERROR;
        }

        String type = normalizeParserType(parserType);
        if (type == null) {
            log.error("startImport: неверный способ парсинга: {}", parserType);
            return ERROR;
        }

        logFile.setLogDir(logDirectory);

        KpRatingsProvider provider = resolveProviderChecked(type);
        if (provider == null) {
            log.error("startImport: не найден провайдер для способа: {}", type);
            return ERROR;
        }

        if ((PARSER_API.equals(type) || PARSER_HYBRID.equals(type)) && (apiToken == null || apiToken.isBlank())) {
            log.error("startImport: для способа {} не указан токен", type);
            return ERROR;
        }
        if (!PARSER_SAVED.equals(type) && kpUserId == null) {
            log.error("startImport: не указан ID пользователя КП");
            return ERROR;
        }

        long userId = kpUserId != null ? kpUserId : 0L;
        String token = apiToken;
        Long realTotal = realTotalRatings;
        log.info("Запускаю импорт для пользователя КП {}, способ {}, лог-директория: {}",
                kpUserId, provider.getKey(), logDirectory);
        new Thread(() -> runImportAsync(userId, driver, provider, token, realTotal), "import-thread").start();

        return "importing";
    }

    /**
     * Выбирает провайдер по способу; для «Сохранённый дамп» проверяет, что kp-ratings.csv существует.
     */
    private KpRatingsProvider resolveProviderChecked(String parserType) {
        if (PARSER_SAVED.equals(parserType) && !logFile.existsKpDump()) {
            log.warn("Способ «Сохранённый дамп»: kp-ratings.csv не найден в директории логов");
            return null;
        }
        return resolveProvider(parserType);
    }

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
        if (PARSER_SELENIUM.equals(t) || PARSER_API.equals(t) || PARSER_HYBRID.equals(t) || PARSER_SAVED.equals(t)) {
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
        if (!PARSER_SAVED.equals(parserType)) {
            if (inputData.getKpUserId() == null) {
                result.setErrorMessage("empty KP user ID");
                result.setHasError(true);
                return result;
            }
            if ((PARSER_API.equals(parserType) || PARSER_HYBRID.equals(parserType))
                    && (inputData.getApiToken() == null || inputData.getApiToken().isBlank())) {
                result.setErrorMessage("empty KP API token (нужен для способов API и «API + Browser»)");
                result.setHasError(true);
            }
        }
        return result;
    }

}
