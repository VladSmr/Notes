package ru.importer.notes.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.InputData;
import ru.importer.notes.log.LogBuffer;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;
import ru.importer.notes.movie.ProcessCoordinator;
import ru.importer.notes.movie.Processor;
import ru.importer.notes.util.ErrorFormatter;
import ru.importer.notes.util.WorkingDir;

@Controller
@RequestMapping("/")
public class MainController implements ErrorController {

    private final Processor processor;
    private final ImportProgress progress;
    private final LogBuffer logBuffer;
    private final LogFileService logFile;
    private final ProcessCoordinator coordinator;

    public MainController(Processor processor, ImportProgress progress, LogBuffer logBuffer,
                          LogFileService logFile, ProcessCoordinator coordinator) {
        this.processor = processor;
        this.progress = progress;
        this.logBuffer = logBuffer;
        this.logFile = logFile;
        this.coordinator = coordinator;
    }

    /** Точка входа: выбор этапа — «Парсинг» или «Проставление». */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("processRunning", coordinator.isRunning());
        model.addAttribute("processStage", coordinator.getStage());
        return "home";
    }

    /** Выбор способа ПАРСИНГА (selenium | api). */
    @GetMapping("/main")
    public String main(Model model) {
        model.addAttribute("processRunning", coordinator.isRunning());
        model.addAttribute("processStage", coordinator.getStage());
        return "main";
    }

    /** Форма проставления (источник — дамп kp-ratings-*.csv / kp-ratings.csv). */
    @GetMapping("/proset")
    public String proset(Model model) {
        model.addAttribute("processRunning", coordinator.isRunning());
        model.addAttribute("processStage", coordinator.getStage());
        model.addAttribute("defaultLogDir", WorkingDir.defaultWorkingDir());
        return "proset-form";
    }

    /** Шаг 1 (парсинг): пользователь выбрал способ — показываем форму с нужными полями. */
    @PostMapping("/method")
    public String method(@RequestParam String parserType, Model model) {
        String type = Processor.normalizeParserType(parserType);
        if (type == null || "saved".equals(type)) {
            model.addAttribute("errorMessage", "Invalid parser type");
            return "error";
        }
        model.addAttribute("parserType", type);
        model.addAttribute("defaultLogDir", WorkingDir.defaultWorkingDir());
        return "method-form";
    }

    /**
     * Возврат на форму данных парсинга (GET) — кнопки «Назад/Вернуться» после ошибок
     * и на странице «нет оценок». Неизвестный/неприменимый способ → selenium.
     */
    @GetMapping("/method-form")
    public String methodForm(@RequestParam(required = false) String parserType, Model model) {
        String type = Processor.normalizeParserType(parserType);
        if (type == null || "saved".equals(type)) {
            type = "selenium";
        }
        model.addAttribute("parserType", type);
        model.addAttribute("defaultLogDir", WorkingDir.defaultWorkingDir());
        return "method-form";
    }

    /** Шаг 2 (парсинг): отправка формы данных → подготовка парсинга (с проверкой перезаписи дампа). */
    @PostMapping("/submit")
    public String submit(InputData inputData, Model model) {
        return processor.prepareParsing(inputData, model);
    }

    /** Подтверждение перезаписи существующего дампа → открытие браузера КП. */
    @PostMapping("/submit-confirmed")
    public String submitConfirmed(InputData inputData, Model model) {
        return processor.confirmParsingOverwrite(inputData, model);
    }

    /** Запуск этапа «Парсинг». */
    @PostMapping("/start-parsing")
    public String startParsing(@RequestParam(required = false) Long kpUserId,
                               @RequestParam String logDirectory,
                               @RequestParam String parserType,
                               @RequestParam(required = false) String apiToken,
                               @RequestParam(required = false) Integer totalRatings,
                               Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute("errorMessage", "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            return "error";
        }
        return processor.startParsing(kpUserId, logDirectory, parserType, apiToken, totalRatings, model);
    }

    /** Результат этапа «Парсинг». Показывается только если последний завершённый этап — парсинг. */
    @GetMapping("/parsing-result")
    public String parsingResult(Model model) {
        AppResult result = progress.getResult();
        if (result == null || !"parsing".equals(progress.getCompletedStage())) {
            return "redirect:/main";
        }
        if (result.getErrorMessage() != null) {
            model.addAttribute("errorMessage", result.getErrorMessage());
            model.addAttribute("errorDetails", result.getErrorDetails());
            return "error";
        }
        model.addAttribute("result", result);
        model.addAttribute("kpDumpFile", logFile.getKpDumpFileName());
        return "parsing-success";
    }

    /** Подготовка этапа «Проставление»: проверка дампа, открытие браузера IMDB. */
    @PostMapping("/proset-submit")
    public String prosetSubmit(@RequestParam String logDirectory, Model model) {
        return processor.prepareProsetting(logDirectory, model);
    }

    /** Запуск этапа «Проставление» (с проверкой входа в IMDB). */
    @PostMapping("/start-proset")
    public String startProset(@RequestParam String logDirectory, Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute("errorMessage", "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            return "error";
        }
        return processor.startProset(logDirectory, model);
    }

    /** Результат этапа «Проставление». Показывается только если последний завершённый этап — проставление. */
    @GetMapping("/result")
    public String result(Model model) {
        AppResult result = progress.getResult();
        if (result == null || !"proset".equals(progress.getCompletedStage())) {
            return "redirect:/main";
        }
        if (result.getErrorMessage() != null) {
            model.addAttribute("errorMessage", result.getErrorMessage());
            model.addAttribute("errorDetails", result.getErrorDetails());
            return "error";
        }
        model.addAttribute("result", result);
        model.addAttribute("kpDumpFile", logFile.getKpDumpFileName());
        return "success";
    }

    /**
     * Страница ошибки: GET с параметром {@code message} ({@code /notes/error?message=...}) или
     * ERROR-диспатч контейнера (класс реализует {@link ErrorController}, поэтому
     * исключение достаётся из request-атрибута и попадает в модель как errorDetails).
     * Маппинг без ограничения HTTP-метода: ERROR-диспатч сохраняет метод исходного запроса.
     */
    @RequestMapping("/error")
    public String error(@RequestParam(required = false) String message,
                        HttpServletRequest request, Model model) {
        Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (exception != null) {
            // Короткое сообщение в alert, полный лог — ниже в <pre>.
            String shortMessage = exception.getMessage();
            if (shortMessage == null || shortMessage.isBlank()) {
                shortMessage = exception.getClass().getName();
            }
            model.addAttribute("errorMessage", shortMessage);
            model.addAttribute("errorDetails", ErrorFormatter.format(exception));
        } else if (message != null && !message.isBlank()) {
            model.addAttribute("errorMessage", message);
        } else if (statusCode != null) {
            model.addAttribute("errorMessage", "Ошибка (HTTP " + statusCode + ")");
        } else {
            model.addAttribute("errorMessage", "Что-то пошло не так");
        }
        return "error";
    }

    @GetMapping("/progress")
    @ResponseBody
    public SseEmitter progress() {
        return progress.subscribe();
    }

    /** Поток лога приложения (последние 1000 записей + новые) для страниц прогресса. */
    @GetMapping("/log")
    @ResponseBody
    public SseEmitter log() {
        return logBuffer.subscribe();
    }

    @PostMapping("/stop")
    @ResponseBody
    public String stop() {
        progress.abort();
        return "stopped";
    }

    @PostMapping("/pause")
    @ResponseBody
    public String pause() {
        progress.pause("paused-user");
        return "paused";
    }

    @PostMapping("/resume")
    @ResponseBody
    public String resume() {
        progress.resume();
        return "resumed";
    }

    /** Смена директории дампа во время паузы «нужен новый путь»; будит фоновый поток. */
    @PostMapping("/change-log-dir")
    @ResponseBody
    public String changeLogDir(@RequestParam String logDirectory) {
        if (!progress.isPaused() || !"need-new-dir".equals(progress.getPausedStatus())) {
            return "not-paused";
        }
        if (logDirectory == null || logDirectory.isBlank()) {
            return "empty";
        }
        processor.changeLogDir(logDirectory);
        return "changed";
    }

}
