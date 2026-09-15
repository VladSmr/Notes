package ru.importer.notes.movie;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.log.LogFileService;

/**
 * Запись дампа оценок КП (имя файла задаёт {@link LogFileService}: kp-ratings-{userId}-{метод}.csv):
 * построчный формат (разделитель «;», экранирование значений через {@link #escapeCsv}) и
 * устойчивое сохранение с ретраями и запросом нового пути ({@link #saveKpDumpSafely}).
 */
@Slf4j
@AllArgsConstructor
class KpDumpWriter {

    private final LogFileService logFile;
    private final ImportProgress progress;

    /**
     * Ошибка «директория недоступна / нет прав на запись» (в отличие от «файл занят»); идёт по cause-цепочке.
     */
    static boolean isPermissionOrDirError(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.nio.file.AccessDeniedException) {
                return true;
            }
            if (t instanceof java.io.FileNotFoundException) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (msg.contains("отказано") || msg.contains("access is denied")
                        || msg.contains("permission") || msg.contains("denied")) {
                    return true;
                }
                // FileNotFoundException без «отказано в доступе» — обычно файл не найден,
                // а не проблема прав; трактуем как «файл занят» (текущее поведение).
                return false;
            }
            if (t instanceof java.io.IOException) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (msg.contains("being used by another process")
                        || msg.contains("занят") || msg.contains("used by another")) {
                    return false;
                }
                if (msg.contains("отказано") || msg.contains("access is denied")
                        || msg.contains("permission") || msg.contains("denied")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private String[] buildKpDumpLines(List<MovieData> movies) {
        return movies.stream()
                     .map(m -> {
                         String name = m.getName() != null ? escapeCsv(m.getName()) : "";
                         String nameOriginal = m.getNameOriginal() != null ? escapeCsv(m.getNameOriginal()) : "";
                         String nameEn = m.getNameEn() != null ? escapeCsv(m.getNameEn()) : "";
                         String year = m.getYear() > 0 ? String.valueOf(m.getYear()) : "";
                         String rating = m.getKpRating() > 0 ? String.valueOf(m.getKpRating()) : "";
                         String kpId = m.getKpId() != null ? String.valueOf(m.getKpId()) : "";
                         String imdbId = m.getImdbId() != null ? escapeCsv(m.getImdbId()) : "";
                         String status = m.getStatus() != null && m.getStatus() != MovieStatus.PENDING
                                 ? m.getStatusLabel() : "";
                         String error = m.getErrorMessage() != null ? escapeCsv(m.getErrorMessage()) : "";

                         return String.join(";",
                                            name,
                                            nameOriginal,
                                            nameEn,
                                            year,
                                            rating,
                                            kpId,
                                            imdbId,
                                            status,
                                            error
                         );
                     })
                     .toArray(String[]::new);
    }

    /**
     * Применяет путь, введённый пользователем во время паузы «нужен новый путь», и будит фоновый поток.
     */
    void changeLogDir(String newDir) {
        if (newDir == null || newDir.isBlank()) {
            throw new IllegalArgumentException("Пустой путь к директории");
        }
        progress.setNewLogDir(newDir);
        progress.resume();
    }

    private String escapeCsv(String value) {
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Пауза со статусом «нужен новый путь»; ждёт путь от пользователя или остановку.
     *
     * @return новый путь или null, если процесс остановлен пользователем
     */
    private String requestNewDir() {
        while (true) {
            progress.pause("need-new-dir");
            progress.waitWhilePaused();
            if (progress.isAborted()) {
                return null;
            }
            String newDir = progress.consumeNewLogDir();
            if (newDir != null && !newDir.isBlank()) {
                return newDir;
            }
            // Пользователь нажал «Продолжить», не указав путь — снова запрашиваем путь.
        }
    }

    private void saveKpDump(List<MovieData> movies) {
        logFile.saveKpDump(buildKpDumpLines(movies));
    }

    /**
     * Сохраняет дамп, не роняя этап. «Файл занят другим процессом» (например, открыт
     * в Excel): 2 ретрая (5 с / 30 с), затем пауза с кнопкой «Продолжить». «Директория
     * недоступна / нет прав»: пауза {@code need-new-dir}, новый путь применяется через
     * {@code /change-log-dir} и запись повторяется.
     */
    void saveKpDumpSafely(List<MovieData> movies) {
        int attempt = 1;
        boolean justResumed = false;
        while (true) {
            try {
                saveKpDump(movies);
                return;
            } catch (Exception e) {
                if (progress.isAborted()) {
                    log.warn("Процесс остановлен, дамп не сохранён: {}", e.getMessage());
                    return;
                }
                if (isPermissionOrDirError(e)) {
                    log.warn("Директория дампа недоступна или нет прав на запись: {}", e.getMessage());
                    String newDir = requestNewDir();
                    if (newDir == null) {
                        // Пользователь остановил процесс во время ожидания нового пути.
                        return;
                    }
                    try {
                        logFile.setLogDir(newDir);
                    } catch (Exception dirErr) {
                        log.warn("Не удалось применить новый путь {}: {}", newDir, dirErr.getMessage());
                        continue;
                    }
                    // Повторяем запись накопленного списка в новый путь сразу.
                    justResumed = false;
                    attempt = 1;
                    continue;
                }
                // Файл занят другим процессом — текущее поведение с ретраями и паузой.
                if (justResumed) {
                    log.warn("Файл {} всё ещё занят после «Продолжить»: {}",
                             logFile.getKpDumpFileName(), e.getMessage());
                    progress.pause("paused-still-busy");
                    progress.waitWhilePaused();
                    if (progress.isAborted()) {
                        return;
                    }
                    continue;
                }
                long delay = attempt == 1 ? 5000 : 30000;
                log.warn("Не удалось сохранить {} (попытка {}): {} — повтор через {} с",
                         logFile.getKpDumpFileName(), attempt, e.getMessage(), delay / 1000);
                sleepUninterruptibly(delay);
                if (++attempt > 2) {
                    log.warn("{} всё ещё занят. Процесс на паузе: закройте файл и нажмите «Продолжить».",
                             logFile.getKpDumpFileName());
                    progress.pause("paused");
                    progress.waitWhilePaused();
                    if (progress.isAborted()) {
                        return;
                    }
                    justResumed = true;
                }
            }
        }
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
