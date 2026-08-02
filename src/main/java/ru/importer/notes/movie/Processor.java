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

@Service
public class Processor {

    private static final Logger log = LoggerFactory.getLogger(Processor.class);

    private static final String ERROR = "error";
    private static final String ERROR_MESSAGE = "errorMessage";
    private static final String PARSER_SELENIUM = "selenium";
    private static final String PARSER_API = "api";
    private static final String PARSER_SAVED = "saved";
    private final AuthManager authManager;
    private final LogFileService logFile;
    private final ImdbNotesExporter notesExporter;
    private final List<KpRatingsProvider> kpProviders;
    private final ImportProgress progress;

    public Processor(AuthManager authManager, ImdbNotesExporter notesExporter,
                     List<KpRatingsProvider> kpProviders, LogFileService logFile,
                     ImportProgress progress) {
        this.authManager = authManager;
        this.notesExporter = notesExporter;
        this.kpProviders = kpProviders;
        this.logFile = logFile;
        this.progress = progress;
    }

    private AppResult buildResult(List<MovieData> movies) {
        AppResult r = new AppResult();
        r.setSuccess(true);
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
     * Открывает браузер, переходит на страницу оценок КП (или IMDB при API-парсинге),
     * затем показывает страницу для ручного входа на IMDB.
     *
     * @param inputData данные формы (KP userId + logDirectory + способ парсинга + токен)
     * @param model     модель для шаблона
     *
     * @return имя шаблона
     */
    public String openBrowser(InputData inputData, Model model) {
        ValidateResult result = validateInputData(inputData);
        if (result.isHasError()) {
            model.addAttribute(ERROR_MESSAGE, result.getErrorMessage());
            return ERROR;
        }

        String parserType = normalizeParserType(inputData.getParserType());
        if (parserType == null) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            return ERROR;
        }

        logFile.setLogDir(inputData.getLogDirectory());

        KpRatingsProvider provider = resolveEffectiveProvider(parserType, inputData.getUseSavedDump());

        if (authManager.getDriver() == null) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();

            if (PARSER_API.equals(parserType) || PARSER_SAVED.equals(provider.getKey())) {
                driver.get("https://www.imdb.com");
            } else {
                String kpUrl = "https://www.kinopoisk.ru/user/" + inputData.getKpUserId() + "/votes/";
                driver.get(kpUrl);
            }
        }

        model.addAttribute("kpUserId", inputData.getKpUserId());
        model.addAttribute("logDirectory", inputData.getLogDirectory());
        model.addAttribute("parserType", parserType);
        model.addAttribute("apiToken", inputData.getApiToken() != null ? inputData.getApiToken() : "");
        model.addAttribute("useSavedDump", Boolean.TRUE.equals(inputData.getUseSavedDump()));

        Integer totalRatings = null;
        if (provider != null) {
            totalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
        }
        model.addAttribute("totalRatings", totalRatings);
        return "login-sites";
    }

    private void runImportAsync(long kpUserId, WebDriver driver, KpRatingsProvider provider, String apiToken) {
        List<MovieData> movies = null;
        try {
            log.info("=== Импорт начат ===");
            log.info("Пользователь КП: {}, способ: {}", kpUserId, provider.getKey());
            log.info("Парсинг оценок с Кинопоиска...");

            movies = provider.fetchRatings(kpUserId, apiToken, progress);
            log.info("Загружено фильмов: {}", movies.size());

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
                saveKpDump(movies);
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

            notesExporter.evaluate(movies, driver, progress);

            saveKpDump(movies);
            log.info("Итоговый дамп со статусами сохранён в kp-ratings.csv");

            AppResult appResult = buildResult(movies);
            progress.complete(appResult);
            log.info("=== Импорт завершён ===");
        } catch (Exception e) {
            log.error("Импорт провалился: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    saveKpDump(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Import failed: " + e.getMessage());
            progress.complete(errorResult);
        }
    }

    private void saveKpDump(List<MovieData> movies) {
        String[] lines = movies.stream()
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

                                   if (name == null || name.isBlank()) {
                                       log.error("Ошибка парсинга КП: пустое название (kpId={})", m.getKpId());
                                   } else if (year == null || year.isBlank()) {
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
        logFile.saveKpDump(lines);
    }

    /**
     * Запускает импорт: загрузка данных из дампа (если включено и дамп есть), иначе парсинг КП
     * выбранным способом, затем проставление оценок на IMDB. Выполняется в фоновом потоке,
     * прогресс публикуется через ImportProgress.
     *
     * @param kpUserId     идентификатор пользователя КП
     * @param logDirectory директория для файлов результатов
     * @param parserType   способ парсинга КП (selenium | api)
     * @param apiToken     API-токен Кинопоиска (для api)
     * @param useSavedDump использовать сохранённый kp-ratings.csv, если он есть
     *
     * @return имя шаблона с результатами или ошибки
     */
    public String startImport(Long kpUserId, String logDirectory, String parserType, String apiToken,
                              Boolean useSavedDump) {
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

        KpRatingsProvider provider = resolveEffectiveProvider(type, useSavedDump);
        if (provider == null) {
            log.error("startImport: не найден провайдер для способа: {}", type);
            return ERROR;
        }

        if (PARSER_API.equals(provider.getKey()) && (apiToken == null || apiToken.isBlank())) {
            log.error("startImport: для API-парсинга не указан токен");
            return ERROR;
        }

        long userId = kpUserId;
        String token = apiToken;
        log.info("Запускаю импорт для пользователя КП {}, способ {}, лог-директория: {}",
                kpUserId, provider.getKey(), logDirectory);
        new Thread(() -> runImportAsync(userId, driver, provider, token), "import-thread").start();

        return "importing";
    }

    /**
     * Выбирает провайдер: если включён useSavedDump и в директории есть kp-ratings.csv,
     * данные берутся из дампа, иначе — выбранным способом (api/selenium).
     */
    private KpRatingsProvider resolveEffectiveProvider(String parserType, Boolean useSavedDump) {
        if (Boolean.TRUE.equals(useSavedDump) && logFile.existsKpDump()) {
            log.info("Использую сохранённый дамп kp-ratings.csv");
            return resolveProvider(PARSER_SAVED);
        }
        return resolveProvider(parserType);
    }

    private KpRatingsProvider resolveProvider(String parserType) {
        return kpProviders.stream()
                .filter(p -> p.getKey().equals(parserType))
                .findFirst()
                .orElse(null);
    }

    private String normalizeParserType(String parserType) {
        if (parserType == null) {
            return null;
        }
        String t = parserType.trim().toLowerCase();
        if (PARSER_SELENIUM.equals(t) || PARSER_API.equals(t)) {
            return t;
        }
        return null;
    }

    private ValidateResult validateInputData(InputData inputData) {
        ValidateResult result = new ValidateResult();
        if (inputData.getKpUserId() == null) {
            result.setErrorMessage("empty KP user ID");
            result.setHasError(true);
        }
        if (inputData.getLogDirectory() == null || inputData.getLogDirectory().isBlank()) {
            result.setErrorMessage("empty log directory");
            result.setHasError(true);
        }
        return result;
    }

}
