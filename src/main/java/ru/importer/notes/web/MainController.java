package ru.importer.notes.web;

import lombok.extern.slf4j.Slf4j;
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
import ru.importer.notes.movie.ImportProgress;
import ru.importer.notes.movie.Processor;

@Controller
@Slf4j
@RequestMapping("/")
public class MainController {

    private final Processor processor;
    private final ImportProgress progress;

    public MainController(Processor processor, ImportProgress progress) {
        this.processor = processor;
        this.progress = progress;
    }

    @GetMapping("/main")
    public String main() {
        return "main";
    }

    @PostMapping("/submit")
    public String submit(InputData inputData, Model model) {
        return processor.openBrowser(inputData, model);
    }

    @PostMapping("/start-import")
    public String startImport(@RequestParam Long kpUserId,
                              @RequestParam String logDirectory,
                              @RequestParam String parserType,
                              @RequestParam(required = false) String apiToken,
                              @RequestParam(required = false) Boolean useSavedDump) {
        return processor.startImport(kpUserId, logDirectory, parserType, apiToken, useSavedDump);
    }

    @GetMapping("/progress")
    @ResponseBody
    public SseEmitter progress() {
        return progress.subscribe();
    }

    @PostMapping("/stop")
    @ResponseBody
    public String stop() {
        progress.abort();
        return "stopped";
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
