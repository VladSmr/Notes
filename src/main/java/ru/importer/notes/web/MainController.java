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
import ru.importer.notes.movie.Processor;

@Controller
@RequestMapping("/")
public class MainController {

    private final Processor processor;
    private final ImportProgress progress;
    private final LogBuffer logBuffer;

    public MainController(Processor processor, ImportProgress progress, LogBuffer logBuffer) {
        this.processor = processor;
        this.progress = progress;
        this.logBuffer = logBuffer;
    }

    @GetMapping("/main")
    public String main() {
        return "main";
    }

    @PostMapping("/submit")
    public String submit(InputData inputData, Model model) {
        return processor.openBrowser(inputData, model);
    }

    /**
     * Шаг 1: пользователь выбрал способ получения данных — показываем форму
     * с полями, нужными именно для него.
     */
    @PostMapping("/method")
    public String method(@RequestParam String parserType, Model model) {
        String type = Processor.normalizeParserType(parserType);
        if (type == null) {
            model.addAttribute("errorMessage", "Invalid parser type");
            return "error";
        }
        model.addAttribute("parserType", type);
        return "method-form";
    }

    @PostMapping("/start-import")
    public String startImport(@RequestParam(required = false) Long kpUserId,
                              @RequestParam String logDirectory,
                              @RequestParam String parserType,
                              @RequestParam(required = false) String apiToken,
                              @RequestParam(required = false) Long realTotalRatings) {
        return processor.startImport(kpUserId, logDirectory, parserType, apiToken, realTotalRatings);
    }

    @GetMapping("/progress")
    @ResponseBody
    public SseEmitter progress() {
        return progress.subscribe();
    }

    /** Поток лога приложения (последние 1000 записей + новые) для страницы импорта. */
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

    @GetMapping("/result")
    public String result(Model model) {
        AppResult result = progress.getResult();
        if (result == null) {
            return "redirect:/main";
        }
        if (result.getErrorMessage() != null) {
            model.addAttribute("errorMessage", result.getErrorMessage());
            return "error";
        }
        model.addAttribute("result", result);
        return "success";
    }

}
