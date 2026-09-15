package ru.importer.notes.log;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class LogFileServiceTest {

    @TempDir
    Path tempDir;

    private final LogFileService logFile = new LogFileService();

    @BeforeEach
    void setUp() {
        logFile.setLogDir(tempDir.toString());
    }

    @Test
    void saveKpDump_shouldCreateCsvWithHeader() throws IOException {
        logFile.saveKpDump(
                "\"Начало\",2010,8,https://www.kinopoisk.ru/film/12345/",
                "\"Бойцовский клуб\",1999,9,https://www.kinopoisk.ru/film/361/"
        );

        byte[] bytes = Files.readAllBytes(tempDir.resolve("kp-ratings.csv"));
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        assertEquals(3, lines.length);
        assertEquals("title;original_title;english_title;year;rating;kp_id;imdb_id;status;error",
                lines[0].replace("\uFEFF", "").trim());
        assertTrue(lines[1].contains("Начало"));
        assertTrue(lines[1].contains("2010"));
        assertTrue(lines[1].contains("8"));
    }

    @Test
    void saveKpDump_shouldOverwriteExistingFile() throws IOException {
        logFile.saveKpDump("old data");
        logFile.saveKpDump("\"New\";2020;7;http://kp.ru/film/1/");

        byte[] bytes = Files.readAllBytes(tempDir.resolve("kp-ratings.csv"));
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("New"));
    }

    @Test
    void saveKpDump_shouldFailAndKeepOldDumpWhenTmpWriteFails() throws IOException {
        // Сбой записи PrintWriter не бросает: println/print глотают IOException и
        // выставляют только внутренний флаг, видимый через checkError(). Реалистичный
        // триггер (диск переполнен, ошибка носителя) в Windows-тесте невоспроизводим,
        // поэтому подменяем создание writer'а через подкласс LogFileService:
        // tmp-файл создаётся (как при начале записи), но любой сброс буфера на диск
        // падает — эмуляция переполнения диска сразу после создания tmp.
        logFile.saveKpDump("old;row;keep;2000;5;1;;;");
        Path dump = tempDir.resolve("kp-ratings.csv");
        Path tmp = tempDir.resolve("kp-ratings.csv.tmp");
        String before = Files.readString(dump, StandardCharsets.UTF_8);

        LogFileService failing = new LogFileService() {
            @Override
            PrintWriter newTmpWriter(Path tmpFile) throws IOException {
                Writer out = new OutputStreamWriter(
                        new FileOutputStream(tmpFile.toFile(), false), StandardCharsets.UTF_8) {
                    @Override
                    public void write(char[] cbuf, int off, int len) throws IOException {
                        throw new IOException("No space left on device (эмуляция)");
                    }
                };
                return new PrintWriter(new BufferedWriter(out), false);
            }
        };
        failing.setLogDir(tempDir.toString());

        KpDumpWriteException ex = assertThrows(KpDumpWriteException.class,
                () -> failing.saveKpDump("new;row;data;2001;6;2;;;"));
        // PrintWriter глотает исходное сообщение сбоя («No space left on device»):
        // наружу виден только флаг, поэтому saveKpDump создаёт свой IOException
        // с сообщением ветки checkError (не ветки move).
        assertTrue(ex.getCause() instanceof IOException);
        assertTrue(ex.getCause().getMessage()
                .contains("Не удалось записать данные во временный файл дампа"));
        // Rename не выполнен: старый дамп остался нетронутым.
        assertEquals(before, Files.readString(dump, StandardCharsets.UTF_8));
        // Неполный tmp остался (не переименован) — будет перезаписан при ретрае.
        assertTrue(Files.exists(tmp));
        assertEquals(0, Files.size(tmp));
    }

    @Test
    void setKpDumpName_shouldWriteToUserAndMethodNamedFile() throws IOException {
        // Имя дампа = kp-ratings-{userId}-{метод}.csv; старый файл не создаётся.
        logFile.setKpDumpName(123, "api");
        logFile.saveKpDump("a;b;c;2000;5;1;;;");

        assertTrue(Files.exists(tempDir.resolve("kp-ratings-123-api.csv")));
        assertFalse(Files.exists(tempDir.resolve("kp-ratings.csv")));
        // Временный файл потреблён атомарным rename: его имя привязано к имени дампа.
        assertFalse(Files.exists(tempDir.resolve("kp-ratings-123-api.csv.tmp")));
    }

    @Test
    void setKpDumpName_seleniumMethod_overwritesSameFile() throws IOException {
        logFile.setKpDumpName(321, "selenium");
        logFile.saveKpDump("old;row;one;2000;5;1;;;");
        logFile.saveKpDump("new;row;two;2001;6;2;;;");

        List<String> lines = Files.readAllLines(tempDir.resolve("kp-ratings-321-selenium.csv"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("new"));
        assertFalse(lines.get(1).contains("old"));
    }

    @Test
    void existsKpDump_shouldFollowSelectedName() {
        logFile.setKpDumpName(123, "api");
        assertFalse(logFile.existsKpDump());
        logFile.saveKpDump("a;b;c;2000;5;1;;;");
        assertTrue(logFile.existsKpDump());
    }

    @Test
    void selectKpDumpFile_shouldPickNewestByModifiedTime() throws IOException {
        writeDumpFile("kp-ratings-111-api.csv", "old;row;one;2000;5;1;;;",
                System.currentTimeMillis() - 3_600_000);
        writeDumpFile("kp-ratings-222-selenium.csv", "new;row;two;2001;6;2;;;",
                System.currentTimeMillis());

        assertTrue(logFile.selectKpDumpFile());
        assertEquals("kp-ratings-222-selenium.csv", logFile.getKpDumpFileName());
    }

    @Test
    void selectKpDumpFile_shouldReadContentOfNewestDump() throws IOException {
        writeDumpFile("kp-ratings-111-api.csv", "old;row;one;2000;5;1;;;",
                System.currentTimeMillis() - 3_600_000);
        writeDumpFile("kp-ratings-222-selenium.csv", "new;row;two;2001;6;2;;;",
                System.currentTimeMillis());

        assertTrue(logFile.selectKpDumpFile());
        List<String[]> rows = logFile.readKpDump();
        assertEquals(1, rows.size());
        assertEquals("new", rows.get(0)[0]);
    }

    @Test
    void selectKpDumpFile_shouldFallbackToLegacyName() throws IOException {
        logFile.saveKpDump("legacy;row;name;2000;5;1;;;");

        assertTrue(logFile.selectKpDumpFile());
        assertEquals("kp-ratings.csv", logFile.getKpDumpFileName());
        List<String[]> rows = logFile.readKpDump();
        assertEquals(1, rows.size());
        assertEquals("legacy", rows.get(0)[0]);
    }

    @Test
    void selectKpDumpFile_shouldReturnFalseWhenNoDump() {
        assertFalse(logFile.selectKpDumpFile());
    }

    @Test
    void selectKpDumpFile_shouldIgnoreTmpAndForeignFiles() throws IOException {
        writeDumpFile("kp-ratings-123-api.csv.tmp", "tmp;not;dump;2000;5;1;;;",
                System.currentTimeMillis());
        Files.createFile(tempDir.resolve("other.csv"));
        logFile.saveKpDump("legacy;row;name;2000;5;1;;;");

        assertTrue(logFile.selectKpDumpFile());
        assertEquals("kp-ratings.csv", logFile.getKpDumpFileName());
    }

    /** Пишет файл дампа: заголовок + одна строка данных, с явным временем изменения. */
    private void writeDumpFile(String fileName, String row, long lastModified) throws IOException {
        Path file = Files.createFile(tempDir.resolve(fileName));
        Files.writeString(file, "title;original_title;english_title;year;rating;kp_id;imdb_id;status;error\n"
                + row + "\n");
        assertTrue(file.toFile().setLastModified(lastModified), "Не удалось задать время изменения " + fileName);
    }

}
