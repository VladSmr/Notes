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
    private final Object resumeMonitor = new Object();
    private int current;
    private int total;
    private volatile boolean aborted;
    private volatile boolean paused;
    private volatile String pausedStatus;
    private AppResult result;

    public void init(int total) {
        this.current = 0;
        this.total = total;
        this.aborted = false;
        this.paused = false;
        this.result = null;
    }

    public void abort() {
        this.aborted = true;
        synchronized (resumeMonitor) {
            resumeMonitor.notifyAll();
        }
    }

    public boolean isAborted() {
        return aborted;
    }

    /** Ставит импорт на паузу и оповещает веб-страницу (status: "paused" или "paused-still-busy"). */
    public void pause(String pauseStatus) {
        synchronized (resumeMonitor) {
            paused = true;
            pausedStatus = pauseStatus;
        }
        broadcast(new ProgressEvent(null, current, total, null, pauseStatus, false, null, true));
    }

    /** Снимает паузу (кнопка «Продолжить») и будит заблокированный поток импорта. */
    public void resume() {
        synchronized (resumeMonitor) {
            paused = false;
            pausedStatus = null;
            resumeMonitor.notifyAll();
        }
        broadcast(new ProgressEvent(null, current, total, null, "resumed", false, null, false));
    }

    public boolean isPaused() {
        return paused;
    }

    /** Блокирует поток импорта, пока импорт на паузе (или пока не остановлен пользователем). */
    public void waitWhilePaused() {
        synchronized (resumeMonitor) {
            while (paused && !aborted) {
                try {
                    resumeMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public synchronized void advance(String phase, String movieName, String status) {
        this.current++;
        broadcast(new ProgressEvent(phase, current, total, movieName, status, false, null, false));
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
        broadcast(new ProgressEvent(null, current, total, null, "complete", true, result, false));
    }

    public AppResult getResult() {
        return result;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        if (paused) {
            try {
                emitter.send(new ProgressEvent(null, current, total, null,
                        pausedStatus != null ? pausedStatus : "paused", false, null, true));
            } catch (Exception ignored) {
            }
        }
        return emitter;
    }

}
