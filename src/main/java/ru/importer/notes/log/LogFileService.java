package ru.importer.notes.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LogFileService {

    private static final String KP_DUMP_FILE = "kp-ratings.csv";
    private Path logDir;

    private void checkDir() {
        if (logDir == null) {
            throw new IllegalStateException("Log directory not set. Call setLogDir() first.");
        }
    }

    /**
     * Сохраняет результаты импорта в CSV-файл (разделитель `;`,
     * BOM для корректного открытия в Excel в любом регионе).
     *
     * @param lines строки данных
     */
    public synchronized void saveKpDump(String... lines) {
        checkDir();
        Path file = logDir.resolve(KP_DUMP_FILE);
        try (PrintWriter pw = new PrintWriter(file.toFile(), "UTF-8")) {
            pw.print('\uFEFF');
            pw.println("title;original_title;english_title;year;rating;kp_id;imdb_id;status;error");
            for (String line : lines) {
                pw.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Есть ли сохранённый дамп оценок kp-ratings.csv в директории.
     */
    public synchronized boolean existsKpDump() {
        if (logDir == null) {
            return false;
        }
        return Files.exists(logDir.resolve(KP_DUMP_FILE));
    }

    /**
     * Читает сохранённый дамп kp-ratings.csv (без заголовка).
     *
     * @return строки данных или null, если файла нет
     */
    public synchronized List<String[]> readKpDump() {
        checkDir();
        Path file = logDir.resolve(KP_DUMP_FILE);
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

    /**
     * Устанавливает директорию для файлов результатов и создаёт её при необходимости.
     *
     * @param dir абсолютный или относительный путь к директории
     */
    public void setLogDir(String dir) {
        this.logDir = Paths.get(dir).toAbsolutePath();
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create log directory: " + logDir, e);
        }
    }

}
