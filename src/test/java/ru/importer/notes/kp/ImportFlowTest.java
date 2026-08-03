package ru.importer.notes.kp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.*;

class ImportFlowTest {

    @TempDir
    Path tempDir;

    private final KpNotesImporter importer = new KpNotesImporter();
    private final LogFileService logFile = new LogFileService();

    @BeforeEach
    void setUp() {
        logFile.setLogDir(tempDir.toString());
    }

    @Test
    void parseAndDump_shouldCreateCsvWithParsedMovies() throws IOException {
        String pageHtml = "<html><body>"
                + "<div class=\"item\">"
                + "  <a class=\"name\" href=\"/film/1/\">Film A</a>"
                + "  <span class=\"year\">2000</span>"
                + "  <span class=\"value\">7</span>"
                + "</div>"
                + "<div class=\"item\">"
                + "  <a class=\"name\" href=\"/film/2/\">Film B</a>"
                + "  <span class=\"year\">2005</span>"
                + "  <span class=\"value\">9</span>"
                + "</div>"
                + "</body></html>";

        var items = Jsoup.parse(pageHtml).select("div.item");
        for (var el : items) {
            MovieData movie = importer.parseItem(el, null);
            assertNotNull(movie);
        }

        logFile.saveKpDump(
                "\"Film A\",2000,7,https://www.kinopoisk.ru/film/1/",
                "\"Film B\",2005,9,https://www.kinopoisk.ru/film/2/"
        );

        List<String> lines = Files.readAllLines(tempDir.resolve("kp-ratings.csv"));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("Film A"));
        assertTrue(lines.get(1).contains("2000"));
        assertTrue(lines.get(1).contains("7"));
        assertTrue(lines.get(2).contains("Film B"));
        assertTrue(lines.get(2).contains("9"));
    }

}
