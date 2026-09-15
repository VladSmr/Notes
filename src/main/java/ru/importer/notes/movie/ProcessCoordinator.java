package ru.importer.notes.movie;

import org.springframework.stereotype.Component;

/**
 * Координатор процессов: парсинг и проставление не выполняются одновременно.
 * Перед стартом этап обязан занять «слот» через {@link #tryBegin(String)};
 * пока слот занят, второй запуск отклоняется.
 */
@Component
public class ProcessCoordinator {

    private volatile boolean running = false;
    private volatile String stage = null;

    /** Освобождает слот по завершении процесса (вызывается в finally). */
    public synchronized void finish() {
        running = false;
        stage = null;
    }

    /** Имя текущего этапа ("parsing" | "proset") или null, если процесс не идёт. */
    public String getStage() {
        return stage;
    }

    /** Идёт ли сейчас парсинг или проставление (слот занят). */
    public boolean isRunning() {
        return running;
    }

    /**
     * Занимает слот для запуска процесса.
     *
     * @return true, если слот был свободен и занят
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
