package ru.importer.notes.movie;

import org.springframework.stereotype.Component;

/**
 * Синглтон-координатор процессов. Гарантирует, что парсинг и проставление
 * не выполняются одновременно: оба этапа запускаются в отдельных фоновых потоках,
 * и перед стартом каждый обязан занять «слот» через {@link #tryBegin(String)}.
 * Пока слот занят — запуск второго процесса отклоняется.
 */
@Component
public class ProcessCoordinator {

    private volatile boolean running = false;
    private volatile String stage = null;

    /**
     * Освобождает слот по завершении процесса (в finally).
     */
    public synchronized void finish() {
        running = false;
        stage = null;
    }

    /**
     * @return имя текущего этапа ("parsing" | "proset") или null, если процесс не идёт
     */
    public String getStage() {
        return stage;
    }

    /**
     * @return true, если парсинг или проставление сейчас выполняется (слот занят);
     * false, если ни один процесс не идёт
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Пытается занять слот для запуска процесса.
     *
     * @param stage имя этапа ("parsing" | "proset")
     *
     * @return true, если слот свободен и занят; false, если процесс уже идёт
     */
    public synchronized boolean tryBegin(String stage) {
        if (running) {
            return false;
        }
        running = true;
        this.stage = stage;
        return true;
    }

}
