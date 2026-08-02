package ru.importer.notes.movie;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.ProgressEvent;

@Component
public class ImportProgress {

    public static final String PHASE_KP = "kp";
    public static final String PHASE_IMDB = "imdb";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private int current;
    private int total;
    private volatile boolean aborted;
    private AppResult result;

    public void init(int total) {
        this.current = 0;
        this.total = total;
        this.aborted = false;
        this.result = null;
    }

    public void abort() {
        this.aborted = true;
    }

    public boolean isAborted() {
        return aborted;
    }

    public synchronized void advance(String phase, String movieName, String status) {
        this.current++;
        broadcast(new ProgressEvent(phase, current, total, movieName, status, false, null));
    }

    private void broadcast(ProgressEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
                if (event.isFinished()) {
                    emitter.complete();
                }
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public synchronized void complete(AppResult result) {
        this.result = result;
        broadcast(new ProgressEvent(null, current, total, null, "complete", true, result));
    }

    public AppResult getResult() {
        return result;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

}
