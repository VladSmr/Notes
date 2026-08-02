package ru.importer.notes.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

}
