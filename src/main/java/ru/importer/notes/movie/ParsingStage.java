package ru.importer.notes.movie;

import static ru.importer.notes.movie.Processor.ERROR;
import static ru.importer.notes.movie.Processor.ERROR_MESSAGE;
import static ru.importer.notes.movie.Processor.KP_PER_PAGE;
import static ru.importer.notes.movie.Processor.PARSER_API;
import static ru.importer.notes.movie.Processor.PARSER_SAVED;
import static ru.importer.notes.movie.Processor.PARSER_SELENIUM;
import static ru.importer.notes.movie.Processor.STAGE_PARSING;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ui.Model;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.ValidateResult;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.util.ErrorFormatter;

/**
 * Этап «Парсинг» (только Кинопоиск -> дамп kp-ratings-{userId}-{метод}.csv): подготовка браузера КП,
 * запуск фонового парсинга выбранным способом и сохранение дампа. Подготовка браузера
 * сериализуется через {@code browserLock} — общий с этапом «Проставление» объект
 * (см. {@link Processor}); слот {@link ProcessCoordinator} занимается перед стартом
 * и освобождается в {@code finally} фонового потока.
 */
@Slf4j
class ParsingStage {

    private final Processor processor;
    private final AuthManager authManager;
    private final LogFileService logFile;
    private final ImportProgress progress;
    private final ProcessCoordinator coordinator;
    private final KpDumpWriter dumpWriter;

    /** Общий с {@link Processor} объект синхронизации подготовки браузера (см. {@link Processor#browserLock}). */
    private final Object browserLock;

    ParsingStage(Processor processor, AuthManager authManager, LogFileService logFile,
                 ImportProgress progress, ProcessCoordinator coordinator,
                 KpDumpWriter dumpWriter, Object browserLock) {
        this.processor = processor;
        this.authManager = authManager;
        this.logFile = logFile;
        this.progress = progress;
        this.coordinator = coordinator;
        this.dumpWriter = dumpWriter;
        this.browserLock = browserLock;
    }

    /** Подготовка парсинга; при существующем дампе kp-ratings-{userId}-{метод}.csv — запрос подтверждения перезаписи. */
    String prepareParsing(InputData inputData, Model model) {
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
            log.warn("Парсинг: дамп {} уже существует — требуется подтверждение перезаписи",
                     logFile.getKpDumpFileName());
            return "confirm-overwrite";
        }
        return openParsingBrowser(inputData, parserType, model);
    }

    /** Подтверждение перезаписи дампа: открывает браузер КП (если нужен) и страницу входа. */
    String confirmParsingOverwrite(InputData inputData, Model model) {
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
     * Общая для шагов парсинга валидация, установка директории логов и атрибутов модели.
     *
     * @return нормализованный способ парсинга или null (ошибка уже в модели)
     */
    private String validateAndSetupParsing(InputData inputData, Model model) {
        String parserType = Processor.normalizeParserType(inputData.getParserType());
        if (parserType == null || PARSER_SAVED.equals(parserType)) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            return null;
        }
        ValidateResult result = processor.validateInputData(inputData, parserType);
        if (result.isHasError()) {
            model.addAttribute(ERROR_MESSAGE, result.getErrorMessage());
            return null;
        }
        logFile.setLogDir(inputData.getLogDirectory());
        // Имя дампа с id профиля КП и способом: kp-ratings-{userId}-{метод}.csv.
        logFile.setKpDumpName(inputData.getKpUserId(), parserType);
        fillParsingAttrs(model, inputData, parserType);
        return parserType;
    }

    private void fillParsingAttrs(Model model, InputData inputData, String parserType) {
        model.addAttribute("kpUserId", inputData.getKpUserId());
        model.addAttribute("logDirectory", inputData.getLogDirectory());
        model.addAttribute("parserType", parserType);
        model.addAttribute("kpDumpFile", logFile.getKpDumpFileName());
        model.addAttribute("apiToken", inputData.getApiToken() != null ? inputData.getApiToken() : "");
    }

    /** Открывает браузер для парсинга (только selenium); IMDB на этом этапе не участвует. */
    private String openParsingBrowser(InputData inputData, String parserType, Model model) {
        KpRatingsProvider provider = processor.resolveProvider(parserType);

        Integer realTotalRatings = null;
        boolean needsBrowser = PARSER_SELENIUM.equals(parserType);
        if (needsBrowser) {
            synchronized (browserLock) {
                if (authManager.getDriver() == null) {
                    authManager.openBrowserAndWaitLogin();
                }
                // Счётчик оценок читаем всегда: N нужен для детерминированной полосы «N + N/20».
                realTotalRatings = provider.fetchTotalRatings(inputData.getKpUserId(), inputData.getApiToken());
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
     * и сохранение результата в дамп kp-ratings-{userId}-{метод}.csv. Слот координатора
     * занимается только после всех валидаций; освобождается в {@code finally} фонового потока.
     */
    String startParsing(Long kpUserId, String logDirectory, String parserType, String apiToken,
                        Integer totalRatings, Model model) {
        String type = Processor.normalizeParserType(parserType);
        if (type == null || PARSER_SAVED.equals(type)) {
            model.addAttribute(ERROR_MESSAGE, "Invalid parser type");
            log.error("startParsing: неверный способ парсинга: {}", parserType);
            return ERROR;
        }
        logFile.setLogDir(logDirectory);
        KpRatingsProvider provider = processor.resolveProvider(type);
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
        // Имя дампа kp-ratings-{userId}-{метод}.csv — для промежуточных и финальных сохранений.
        logFile.setKpDumpName(kpUserId, type);
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
        new Thread(() -> runParsingAsync(userId, provider, apiToken, totalRatings), "parsing-thread").start();
        return "importing-parsing";
    }

    private void runParsingAsync(long kpUserId, KpRatingsProvider provider, String apiToken, Integer totalRatings) {
        List<MovieData> movies = null;
        try {
            // Детерминированная полоса: 1 страница (20 фильмов) = 1 единица. Число missing-фильмов
            // фазы 2 известно только после фазы 1 — знаменатель пересчитает провайдер (resetTotal).
            // N неизвестно — totalUnits = 0, фронт показывает индетерминированную полосу.
            int totalUnits = 0;
            if (totalRatings != null && totalRatings > 0) {
                int n = totalRatings;
                // ceil(N/20): число страниц оценок КП (по KP_PER_PAGE фильмов на странице).
                int pagesCeil = (n + KP_PER_PAGE - 1) / KP_PER_PAGE;
                totalUnits = pagesCeil;
            }
            progress.init(totalUnits);
            log.info("=== Парсинг начат ===");
            log.info("Пользователь КП: {}, способ: {}", kpUserId, provider.getKey());

            movies = provider.fetchRatings(kpUserId, apiToken, progress, dumpWriter::saveKpDumpSafely);
            log.info("Загружено фильмов: {}", movies.size());

            if (PARSER_API.equals(provider.getKey())) {
                processor.logApiVsReal(movies.size());
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

            processor.logKpWarnings(movies);
            dumpWriter.saveKpDumpSafely(movies);
            log.info("Дамп сохранён: {} фильмов в {}", movies.size(), logFile.getKpDumpFileName());

            AppResult appResult = new AppResult();
            appResult.setTotalMovies(movies.size());
            appResult.setMovies(movies);
            progress.complete(STAGE_PARSING, appResult);
            log.info("=== Парсинг завершён ===");
        } catch (Exception e) {
            log.error("Парсинг провалился: {}", e.getMessage(), e);
            try {
                if (movies != null && !movies.isEmpty()) {
                    dumpWriter.saveKpDumpSafely(movies);
                }
            } catch (Exception ignored) {
            }
            AppResult errorResult = new AppResult();
            errorResult.setErrorMessage("Parsing failed: " + e.getMessage());
            errorResult.setErrorDetails(ErrorFormatter.format(e));
            progress.complete(STAGE_PARSING, errorResult);
        } finally {
            // Браузер закрывается в любом исходе, чтобы профиль Chrome не остался заблокированным.
            synchronized (browserLock) {
                authManager.closeDriver();
            }
            coordinator.finish();
        }
    }

}
