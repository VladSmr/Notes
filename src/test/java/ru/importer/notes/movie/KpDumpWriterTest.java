package ru.importer.notes.movie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.kp.CsvKpRatingsProvider;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KpDumpWriterTest {

    @TempDir
    Path tempDir;

    private final LogFileService logFile = new LogFileService();
    private KpDumpWriter writer;

    @BeforeEach
    void setUp() {
        logFile.setLogDir(tempDir.toString());
        writer = new KpDumpWriter(logFile, new ImportProgress());
    }

    private static MovieData movie(String name, int rating, Long kpId) {
        MovieData m = new MovieData();
        m.setName(name);
        m.setKpRating(rating);
        m.setKpId(kpId);
        return m;
    }

    @Test
    void saveKpDumpSafelyForParsing_shouldSkipRowsWithoutNameRatingOrKpId() throws IOException {
        // Путь ПАРСИНГА: строки-не-фильмы не должны попадать в дамп.
        writer.saveKpDumpSafelyForParsing(List.of(
                movie("Хороший", 8, 111L),
                movie("", 7, 222L),
                movie(null, 6, 333L),
                movie("Без рейтинга", 0, 444L),
                movie("Без kp_id", 5, null),
                movie("Отрицательный kp_id", 5, -1L),
                movie("Ещё хороший", 9, 555L)
        ));

        List<String[]> rows = logFile.readKpDump();
        assertEquals(2, rows.size(), "в дамп попали только валидные строки");
        assertEquals("Хороший", rows.get(0)[0]);
        assertEquals("111", rows.get(0)[5]);
        assertEquals("Ещё хороший", rows.get(1)[0]);
        assertEquals("555", rows.get(1)[5]);
    }

    @Test
    void saveKpDumpSafely_prosetting_shouldKeepIncompleteDataAndAllStatuses() throws IOException {
        // Путь ПРОСТАВЛЕНИЯ: строки уже валидны (в т.ч. INCOMPLETE_DATA с пустой оценкой),
        // фильтрация парсинга не должна их терять.
        MovieData rated = movie("Rated", 8, 1L);
        rated.setStatus(MovieStatus.RATED);
        MovieData ambiguous = movie("Ambiguous", 7, 2L);
        ambiguous.setStatus(MovieStatus.RATED_AMBIGUOUS);
        MovieData notFound = movie("NotFound", 6, 3L);
        notFound.setStatus(MovieStatus.NOT_FOUND);
        MovieData skippedSame = movie("SkippedSame", 5, 4L);
        skippedSame.setStatus(MovieStatus.SKIPPED_SAME);
        MovieData skippedDifferent = movie("SkippedDifferent", 4, 5L);
        skippedDifferent.setStatus(MovieStatus.SKIPPED_DIFFERENT);
        MovieData error = movie("Error", 3, 6L);
        error.setStatus(MovieStatus.ERROR);
        MovieData incomplete = movie("Incomplete", 0, 7L);
        incomplete.setStatus(MovieStatus.INCOMPLETE_DATA);

        writer.saveKpDumpSafely(List.of(rated, ambiguous, notFound, skippedSame,
                skippedDifferent, error, incomplete));

        byte[] bytes = Files.readAllBytes(tempDir.resolve("kp-ratings.csv"));
        assertEquals((byte) 0xEF, bytes[0], "BOM (UTF-8) на месте");
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);

        List<String[]> rows = logFile.readKpDump();
        assertEquals(7, rows.size(), "все строки-фильмы сохранены");
        for (String[] row : rows) {
            assertEquals(9, row.length, "формат — 9 колонок");
        }

        List<MovieData> readBack = new CsvKpRatingsProvider(logFile).fetchRatings(1L, null, null);
        assertEquals(7, readBack.size());
        assertEquals(MovieStatus.RATED, readBack.get(0).getStatus());
        assertEquals(MovieStatus.RATED_AMBIGUOUS, readBack.get(1).getStatus());
        assertEquals(MovieStatus.NOT_FOUND, readBack.get(2).getStatus());
        assertEquals(MovieStatus.SKIPPED_SAME, readBack.get(3).getStatus());
        assertEquals(MovieStatus.SKIPPED_DIFFERENT, readBack.get(4).getStatus());
        assertEquals(MovieStatus.ERROR, readBack.get(5).getStatus());
        assertEquals(MovieStatus.INCOMPLETE_DATA, readBack.get(6).getStatus(),
                "INCOMPLETE_DATA с пустой оценкой не потеряна");
        assertEquals(0, readBack.get(6).getKpRating());
        assertEquals(7L, readBack.get(6).getKpId());
        assertTrue(readBack.get(6).getStatus().isDone());
    }

    @Test
    void saveKpDumpSafely_preservesCorrectedGluedTitleAndYearOnRoundTrip() throws IOException {
        // Путь проставления: провайдер зачистил склейку при чтении — исправленные title/year
        // должны попасть в перезаписываемый дамп.
        logFile.saveKpDump("Волчья яма 22013;Wolf Creek 2;;2003;8;777;;;");

        List<MovieData> movies = new CsvKpRatingsProvider(logFile).fetchRatings(1L, null, null);
        writer.saveKpDumpSafely(movies);

        List<String[]> rows = logFile.readKpDump();
        assertEquals(1, rows.size());
        assertEquals("Волчья яма 2", rows.get(0)[0], "исправленное название записано в дамп");
        assertEquals("2013", rows.get(0)[3], "исправленный год записан в дамп");
    }

    @Test
    void saveKpDumpSafely_quotesValueWithCarriageReturn() throws IOException {
        // Мелочь ревью: значение с \r тоже должно квотироваться (иначе ломает CSV).
        MovieData cr = movie("Имя\rс CR", 5, 1L);
        writer.saveKpDumpSafely(List.of(cr));

        String content = Files.readString(tempDir.resolve("kp-ratings.csv"));
        assertTrue(content.contains("\"Имя\rс CR\""), "значение с \\r заквочено");
    }
}
