package ru.importer.notes.movie;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.dto.ValidateResult;
import ru.importer.notes.imdb.ImdbNotesExporter;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.kp.KpRatingsProvider;
import ru.importer.notes.log.LogFileService;

/**
 * Оркестратор двух этапов: «Парсинг» (оценки КП → дамп kp-ratings-{userId}-{метод}.csv, IMDB не участвует)
 * и «Проставление» (чтение дампа → проставление оценок на IMDB). Логика этапов вынесена
 * в {@link ParsingStage} и {@link ProsettingStage}, запись дампа — в {@link KpDumpWriter};
 * публичный API сохранён для {@code MainController}. Этапы идут в фоновых потоках и не
 * одновременно: слот координатора {@link ProcessCoordinator} занимается перед стартом
 * и освобождается в {@code finally}. Подготовка браузера сериализуется через
 * {@link #browserLock}, по завершении этапа WebDriver закрывается.
 */
@Service
@Slf4j
public class Processor {

    static final String ERROR = "error";
    static final String ERROR_MESSAGE = "errorMessage";
    /**
     * Фильмов на странице оценок КП (совпадает с {@code KpNotesImporter.PER_PAGE}).
     */
    static final int KP_PER_PAGE = 20;
    static final String PARSER_API = "api";
    static final String PARSER_SAVED = "saved";
    static final String PARSER_SELENIUM = "selenium";
    static final String STAGE_PARSING = "parsing";
    static final String STAGE_PROSET = "proset";
    private final AuthManager authManager;
    /**
     * Сериализует подготовку браузера между этапами: AuthManager держит один WebDriver,
     * и одновременные подготовительные фазы не должны переплетаться. Единственный объект
     * передаётся обоим этапам; все захваты (подготовка браузера в {@code openParsingBrowser}
     * и {@code prepareProsetting}, закрытие драйвера в {@code finally} обоих раннеров)
     * выполняются синхронизациями на нём.
     */
    private final Object browserLock = new Object();
    private final ProcessCoordinator coordinator;
    private final KpDumpWriter dumpWriter;
    private final List<KpRatingsProvider> kpProviders;
    private final LogFileService logFile;
    private final ImdbNotesExporter notesExporter;
    private final ParsingStage parsingStage;
    private final ImportProgress progress;
    private final ProsettingStage prosettingStage;

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
        this.dumpWriter = new KpDumpWriter(logFile, progress);
        this.parsingStage = new ParsingStage(this, authManager, logFile, progress, coordinator,
                                             dumpWriter, browserLock);
        this.prosettingStage = new ProsettingStage(this, authManager, logFile, notesExporter, progress,
                                                   coordinator, dumpWriter, browserLock);
    }

    // ------------------------------------------------------------------
    // Публичный API (MainController) — делегирование этапам
    // ------------------------------------------------------------------

    /**
     * Классификация ошибок дампа; тело — в {@link KpDumpWriter#isPermissionOrDirError}.
     */
    static boolean isPermissionOrDirError(Exception e) {
        return KpDumpWriter.isPermissionOrDirError(e);
    }

    /**
     * Нормализует способ парсинга; null для неизвестного.
     */
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

    AppResult buildResult(List<MovieData> movies) {
        AppResult r = new AppResult();
        r.setTotalMovies(movies.size());
        r.setMovies(movies);
        r.setRated((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.RATED).count());
        r.setRatedAmbiguous((int) movies.stream()
                                        .filter(m -> m.getStatus() == MovieStatus.RATED_AMBIGUOUS).count());
        r.setNotFound((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.NOT_FOUND).count());
        r.setSkippedSame((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.SKIPPED_SAME).count());
        r.setSkippedDifferent((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.SKIPPED_DIFFERENT).count());
        r.setIncompleteData((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.INCOMPLETE_DATA).count());
        r.setErrors((int) movies.stream().filter(m -> m.getStatus() == MovieStatus.ERROR).count());
        return r;
    }

    /**
     * Применяет путь, введённый пользователем во время паузы «нужен новый путь» (см. {@link KpDumpWriter#changeLogDir}).
     */
    public void changeLogDir(String newDir) {
        dumpWriter.changeLogDir(newDir);
    }

    /**
     * Подтверждение перезаписи дампа: открывает браузер КП (если нужен) и страницу входа.
     */
    public String confirmParsingOverwrite(InputData inputData, Model model) {
        return parsingStage.confirmParsingOverwrite(inputData, model);
    }

    void logApiVsReal(int apiCount) {
        log.info("API-парсинг: вернул {} записей", apiCount);
        progress.advance(ImportProgress.PHASE_KP, "API вернул " + apiCount + " записей", "api");
    }

    // ------------------------------------------------------------------
    // Общие помощники этапов
    // ------------------------------------------------------------------

    /**
     * Предупреждения о неполных данных КП-парсинга — логгируются один раз после загрузки списка.
     */
    void logKpWarnings(List<MovieData> movies) {
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
                    log.warn("Внимание: у фильма '{}' ({} г., kpId={}) нет ни оригинального, ни английского названия",
                             name, year, m.getKpId());
                } else {
                    log.warn("Внимание: у фильма '{}' ({} г., kpId={}) нет оригинального названия — если страна не СНГ, это ошибка парсинга",
                             name, year, m.getKpId());
                }
            }
        }
    }

    /**
     * Подготовка парсинга; при существующем дампе kp-ratings-{userId}-{метод}.csv — запрос подтверждения перезаписи.
     */
    public String prepareParsing(InputData inputData, Model model) {
        return parsingStage.prepareParsing(inputData, model);
    }

    /**
     * Подготовка проставления: директория, наличие дампа, браузер IMDB и страница входа.
     */
    public String prepareProsetting(String logDirectory, Model model) {
        return prosettingStage.prepareProsetting(logDirectory, model);
    }

    /**
     * Выбирает провайдера по способу парсинга.
     */
    KpRatingsProvider resolveProvider(String parserType) {
        return kpProviders.stream()
                          .filter(p -> p.getKey().equals(parserType))
                          .findFirst()
                          .orElse(null);
    }

    /**
     * Запускает этап «Парсинг» в фоновом потоке; контракт — в {@link ParsingStage#startParsing}.
     */
    public String startParsing(Long kpUserId, String logDirectory, String parserType, String apiToken,
                               Integer totalRatings, Model model) {
        return parsingStage.startParsing(kpUserId, logDirectory, parserType, apiToken, totalRatings, model);
    }

    /**
     * Запускает этап «Проставление» в фоновом потоке; контракт — в {@link ProsettingStage#startProset}.
     */
    public String startProset(String logDirectory, Model model) {
        return prosettingStage.startProset(logDirectory, model);
    }

    ValidateResult validateInputData(InputData inputData, String parserType) {
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
