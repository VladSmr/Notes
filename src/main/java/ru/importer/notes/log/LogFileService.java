package ru.importer.notes.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LogFileService {

    /** Префикс имени дампа с id профиля КП и способом парсинга: kp-ratings-{userId}-{method}.csv. */
    private static final String KP_DUMP_PREFIX = "kp-ratings-";
    /** Старое имя дампа: пишется по умолчанию и служит fallback при чтении (совместимость со старыми дампами). */
    private static final String KP_DUMP_FILE = "kp-ratings.csv";
    private Path logDir;
    /**
     * Имя текущего файла дампа в {@link #logDir}: при парсинге задаётся через {@link #setKpDumpName},
     * при проставлении выбирается через {@link #selectKpDumpFile} (самый свежий).
     */
    private String dumpFileName = KP_DUMP_FILE;

    private void checkDir() {
        if (logDir == null) {
            throw new IllegalStateException("Log directory not set. Call setLogDir() first.");
        }
    }

    /**
     * Сохраняет результаты в CSV (разделитель {@code ;}, BOM для Excel).
     * Запись атомарная: во временный файл, затем rename — при сбое старый файл цел.
     *
     * @param lines строки данных
     */
    public synchronized void saveKpDump(String... lines) {
        checkDir();
        Path file = logDir.resolve(dumpFileName);
        Path tmp = logDir.resolve(dumpFileName + ".tmp");
        try (PrintWriter pw = newTmpWriter(tmp)) {
            pw.print('\uFEFF');
            pw.println("title;original_title;english_title;year;rating;kp_id;imdb_id;status;error");
            for (String line : lines) {
                pw.println(line);
            }
            // PrintWriter.println/print не бросают IOException: при сбое записи
            // (диск переполнен, ошибка носителя) выставляется только внутренний
            // флаг ошибки, который отдаёт checkError() (он же сбрасывает буфер
            // на диск). Без этой проверки неполный tmp был бы переименован,
            // затерев старый дамп.
            if (pw.checkError()) {
                throw new IOException("Не удалось записать данные во временный файл дампа: " + tmp);
            }
        } catch (IOException e) {
            throw new KpDumpWriteException("Не удалось записать временный файл дампа: " + tmp, e);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new KpDumpWriteException("Не удалось переместить временный файл дампа в " + file, e);
        }
    }

    /**
     * Создаёт writer временного файла дампа (вынесено отдельным методом,
     * чтобы тесты могли подменить writer и эмулировать сбой записи).
     */
    PrintWriter newTmpWriter(Path tmp) throws IOException {
        return new PrintWriter(tmp.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * Задаёт имя файла дампа для этапа «Парсинг»: kp-ratings-{userId}-{method}.csv.
     * Повторная запись того же пользователя/способа перезаписывает этот же файл.
     *
     * @param userId     id профиля КП (вводится на форме парсинга)
     * @param parserType нормализованный способ парсинга ({@code api} или {@code selenium})
     */
    public synchronized void setKpDumpName(long userId, String parserType) {
        this.dumpFileName = KP_DUMP_PREFIX + userId + "-" + parserType + ".csv";
    }

    /**
     * Выбирает файл дампа для этапа «Проставление»: самый свежий по времени изменения
     * файл по шаблону {@code kp-ratings-*.csv}; если таких нет — старое имя {@code kp-ratings.csv}.
     *
     * @return true, если дамп найден и выбран для последующего чтения и записи
     */
    public synchronized boolean selectKpDumpFile() {
        if (logDir == null) {
            return false;
        }
        Path selected = null;
        try (Stream<Path> files = Files.list(logDir)) {
            selected = files.filter(this::isNamedKpDump)
                            .max(Comparator.comparingLong((Path p) -> p.toFile().lastModified())
                                    .thenComparing(p -> p.getFileName().toString()))
                            .orElse(null);
        } catch (IOException e) {
            log.warn("Не удалось прочитать директорию дампа {}: {}", logDir, e.getMessage());
        }
        if (selected != null) {
            dumpFileName = selected.getFileName().toString();
            return true;
        }
        // Fallback: дампы старого формата без id и способа в имени.
        if (Files.exists(logDir.resolve(KP_DUMP_FILE))) {
            dumpFileName = KP_DUMP_FILE;
            return true;
        }
        return false;
    }

    /** Подходит ли файл под шаблон нового дампа kp-ratings-{userId}-{method}.csv (`.tmp` отсекается суффиксом). */
    private boolean isNamedKpDump(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(KP_DUMP_PREFIX) && name.endsWith(".csv");
    }

    /** Имя текущего файла дампа (для сообщений UI и лога). */
    public synchronized String getKpDumpFileName() {
        return dumpFileName;
    }

    /** Есть ли выбранный (или целевой для парсинга) дамп в директории. */
    public synchronized boolean existsKpDump() {
        if (logDir == null) {
            return false;
        }
        return Files.exists(logDir.resolve(dumpFileName));
    }

    /** Читает выбранный дамп (без заголовка); null, если файла нет. */
    public synchronized List<String[]> readKpDump() {
        checkDir();
        Path file = logDir.resolve(dumpFileName);
        if (!Files.exists(file)) {
            return null;
        }
        List<String[]> rows = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (i == 0 || lines.get(i).isBlank()) {
                    continue;
                }
                rows.add(parseCsvLine(lines.get(i)));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    /** Разбор строки CSV с поддержкой кавычек и экранирования "" внутри них. */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ';') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    /** Устанавливает директорию для файлов результатов и создаёт её при необходимости. */
    public void setLogDir(String dir) {
        this.logDir = Paths.get(dir).toAbsolutePath();
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create log directory: " + logDir, e);
        }
    }

}
