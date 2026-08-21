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
    /** Новый путь для дампа, введённый пользователем во время паузы «нужен новый путь». */
    private volatile String newLogDir;
    private AppResult result;
    /** Этап, который произвёл текущий результат (см. {@link #complete(String, AppResult)}). */
    private volatile String completedStage;

    public void init(int total) {
        this.current = 0;
        this.total = total;
        this.aborted = false;
        this.paused = false;
        this.result = null;
        this.completedStage = null;
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

    /** Ставит текущий этап на паузу и оповещает веб-страницу (status: "paused" или "paused-still-busy"). */
    public void pause(String pauseStatus) {
        synchronized (resumeMonitor) {
            paused = true;
            pausedStatus = pauseStatus;
        }
        broadcast(new ProgressEvent(null, current, total, null, pauseStatus, false, null, true));
    }

    /** Снимает паузу (кнопка «Продолжить») и будит заблокированный поток этапа. */
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

    /** Текущий статус паузы (например, "paused", "paused-still-busy", "need-new-dir"); null, если не на паузе. */
    public String getPausedStatus() {
        return pausedStatus;
    }

    /**
     * Задаёт новый путь для дампа, введённый пользователем во время паузы «нужен новый путь».
     * Значение считывается фоновым потоком через {@link #consumeNewLogDir()} после {@link #resume()}.
     *
     * @param dir новый путь к директории дампа
     */
    public void setNewLogDir(String dir) {
        this.newLogDir = dir;
    }

    /**
     * Возвращает и сбрасывает введённый пользователем новый путь (однократное потребление).
     *
     * @return новый путь или null, если пользователь его не задал
     */
    public String consumeNewLogDir() {
        String dir = this.newLogDir;
        this.newLogDir = null;
        return dir;
    }

    /** Блокирует поток этапа, пока он на паузе (или пока не остановлен пользователем). */
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

    /**
     * Завершает процесс и сохраняет результат вместе с этапом-производителем.
     * Позволяет страницам результатов отличать результат парсинга от результата
     * проставления, даже если оба используют один общий {@link ImportProgress}.
     *
     * @param stage  этап-производитель ("parsing" | "proset") или null
     * @param result итоговый результат
     */
    public synchronized void complete(String stage, AppResult result) {
        this.result = result;
        this.completedStage = stage;
        broadcast(new ProgressEvent(null, current, total, null, "complete", true, result, false));
    }

    public AppResult getResult() {
        return result;
    }

    /** Этап, который произвёл текущий результат (null, если результат ещё не установлен). */
    public String getCompletedStage() {
        return completedStage;
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
