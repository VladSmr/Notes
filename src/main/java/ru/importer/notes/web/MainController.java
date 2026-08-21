package ru.importer.notes.web;

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
import ru.importer.notes.movie.ImportProgress;
import ru.importer.notes.movie.ProcessCoordinator;
import ru.importer.notes.movie.Processor;

@Controller
@RequestMapping("/")
public class MainController {

    private final Processor processor;
    private final ImportProgress progress;
    private final LogBuffer logBuffer;
    private final ProcessCoordinator coordinator;

    public MainController(Processor processor, ImportProgress progress, LogBuffer logBuffer,
                          ProcessCoordinator coordinator) {
        this.processor = processor;
        this.progress = progress;
        this.logBuffer = logBuffer;
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

    /** Форма проставления (источник — kp-ratings.csv). */
    @GetMapping("/proset")
    public String proset(Model model) {
        model.addAttribute("processRunning", coordinator.isRunning());
        model.addAttribute("processStage", coordinator.getStage());
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
                               Model model) {
        if (coordinator.isRunning()) {
            model.addAttribute("errorMessage", "Процесс уже идёт (этап: " + coordinator.getStage()
                    + "). Дождитесь его завершения.");
            return "error";
        }
        return processor.startParsing(kpUserId, logDirectory, parserType, apiToken, model);
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
            return "error";
        }
        model.addAttribute("result", result);
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
            return "error";
        }
        model.addAttribute("result", result);
        return "success";
    }

    /** Страница ошибки для фоновых сбоев (редирект со страниц прогресса по ?message=...). */
    @GetMapping("/error")
    public String error(@RequestParam(required = false) String message, Model model) {
        model.addAttribute("errorMessage", message != null && !message.isBlank() ? message : "Что-то пошло не так");
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

    /**
     * Смена директории дампа во время паузы «нужен новый путь» (нет прав на запись /
     * директория недоступна). Применяет новый путь и будит фоновый поток, который
     * перезапишет накопленный дамп в новый путь.
     */
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
