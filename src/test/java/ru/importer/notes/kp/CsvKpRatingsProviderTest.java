package ru.importer.notes.kp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.log.LogFileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvKpRatingsProviderTest {

    @TempDir
    Path tempDir;

    private final LogFileService logFile = new LogFileService();
    private final CsvKpRatingsProvider provider = new CsvKpRatingsProvider(logFile);

    @BeforeEach
    void setUp() {
        logFile.setLogDir(tempDir.toString());
    }

    @Test
    void fetchRatings_shouldParseDumpRows() {
        logFile.saveKpDump(
                "\"Терминатор\";\"Terminator\";\"The Terminator\";1984;8;123;tt0088247;ошибка;timeout",
                "\"Сага\";\"Saga\";;2012;7;456;;;"
        );

        List<MovieData> movies = provider.fetchRatings(1L, null, null);
        assertEquals(2, movies.size());

        MovieData first = movies.get(0);
        assertEquals("Терминатор", first.getName());
        assertEquals("Terminator", first.getNameOriginal());
        assertEquals("The Terminator", first.getNameEn());
        assertEquals(1984, first.getYear());
        assertEquals(8, first.getKpRating());
        assertEquals(123L, first.getKpId());
        assertEquals("tt0088247", first.getImdbId());
        assertEquals(MovieStatus.ERROR, first.getStatus());
        assertEquals("timeout", first.getErrorMessage());

        MovieData second = movies.get(1);
        assertEquals("Сага", second.getName());
        assertEquals(2012, second.getYear());
        assertEquals(7, second.getKpRating());
        assertEquals(456L, second.getKpId());
        assertNull(second.getImdbId());
        assertEquals(MovieStatus.PENDING, second.getStatus());
    }

    @Test
    void fetchRatings_shouldReturnAllRowsIncludingDone() {
        logFile.saveKpDump(
                "\"A\";;;2000;5;1;tt1;успешно;",
                "\"B\";;;2001;6;2;tt2;не найден;",
                "\"C\";;;2002;7;3;tt3;пропущено (уже стоит оценка);",
                "\"D\";;;2003;8;4;tt4;руками (оценки отличаются);",
                "\"E\";;;2004;9;5;tt5;ошибка;boom",
                "\"F\";;;2005;1;6;tt6;;"
        );

        List<MovieData> movies = provider.fetchRatings(1L, null, null);
        assertEquals(6, movies.size());
        assertEquals(MovieStatus.RATED, movies.get(0).getStatus());
        assertEquals(MovieStatus.NOT_FOUND, movies.get(1).getStatus());
        assertEquals(MovieStatus.SKIPPED_SAME, movies.get(2).getStatus());
        assertEquals(MovieStatus.SKIPPED_DIFFERENT, movies.get(3).getStatus());
        assertEquals(MovieStatus.ERROR, movies.get(4).getStatus());
        assertEquals("boom", movies.get(4).getErrorMessage());
        assertEquals(MovieStatus.PENDING, movies.get(5).getStatus());
    }

    @Test
    void fetchRatings_shouldHandleQuotedSemicolonsAndEscapedQuotes() {
        logFile.saveKpDump("\"Фильм; с точкой\";\"Title \"\"X\"\"\";;2001;5;;tt0000001;;");

        List<MovieData> movies = provider.fetchRatings(1L, null, null);
        assertEquals(1, movies.size());
        assertEquals("Фильм; с точкой", movies.get(0).getName());
        assertEquals("Title \"X\"", movies.get(0).getNameOriginal());
    }

    @Test
    void fetchRatings_shouldReturnEmptyWhenNoDump() {
        assertTrue(provider.fetchRatings(1L, null, null).isEmpty());
        assertNull(provider.fetchTotalRatings(1L, null));
    }

    @Test
    void fetchRatings_shouldReadNewestNamedDump() throws IOException {
        // Два дампа разных профилей: провайдер должен читать самый свежий по времени изменения.
        writeDumpFile("kp-ratings-111-api.csv", "Old;Old;;2000;5;1;;;",
                System.currentTimeMillis() - 3_600_000);
        writeDumpFile("kp-ratings-222-selenium.csv", "New;New;;2001;6;2;;;",
                System.currentTimeMillis());
        assertTrue(logFile.selectKpDumpFile());

        List<MovieData> movies = provider.fetchRatings(222L, null, null);
        assertEquals(1, movies.size());
        assertEquals("New", movies.get(0).getName());
    }

    /** Пишет файл дампа: заголовок + одна строка данных, с явным временем изменения. */
    private void writeDumpFile(String fileName, String row, long lastModified) throws IOException {
        Path file = Files.createFile(tempDir.resolve(fileName));
        Files.writeString(file, "title;original_title;english_title;year;rating;kp_id;imdb_id;status;error\n"
                + row + "\n");
        assertTrue(file.toFile().setLastModified(lastModified), "Не удалось задать время изменения " + fileName);
    }

    @Test
    void fetchTotalRatings_shouldCountRows() {
        logFile.saveKpDump("a;b;c;2000;5;1;;;", "d;e;f;2001;6;2;;;");
        assertEquals(Integer.valueOf(2), provider.fetchTotalRatings(1L, null));
    }

    @Test
    void fetchTotalRatings_shouldCountAllRowsEvenIfDone() {
        logFile.saveKpDump("a;b;c;2000;5;1;;успешно;", "d;e;f;2001;6;2;;ошибка;err");
        assertEquals(Integer.valueOf(2), provider.fetchTotalRatings(1L, null));
    }

    @Test
    void fetchRatings_shouldRoundTripIncompleteDataStatus() {
        // Новый статус «неполные данные»: фильм с пустым rating (кнопки «Rate 0» на IMDB нет)
        // не должен ретраиться при следующих прогонах.
        logFile.saveKpDump("Женщина-Халк: Адвокат;;She-Hulk: Attorney at Law;2022;;12928878;;неполные данные;");

        List<MovieData> movies = provider.fetchRatings(1L, null, null);
        assertEquals(1, movies.size());
        assertEquals(MovieStatus.INCOMPLETE_DATA, movies.get(0).getStatus());
        assertEquals("неполные данные", movies.get(0).getStatusLabel());
        assertEquals(0, movies.get(0).getKpRating());
    }

    @Test
    void parseStatusLabel_shouldMapIncompleteDataLabel() {
        assertEquals(MovieStatus.INCOMPLETE_DATA, MovieData.parseStatusLabel("неполные данные"));
        assertEquals(MovieStatus.PENDING, MovieData.parseStatusLabel("неизвестный статус"));
    }

    @Test
    void fetchRatings_shouldRoundTripRatedAmbiguousStatus() {
        // «Проставлено с оговоркой»: неоднозначный выбор при ставке. Статус завершённый —
        // при повторном прогоне строка не ретраится, лейбл читается обратно.
        logFile.saveKpDump("Midsommar;;Midsommar;2019;8;123;tt8772262;проставлено с оговоркой;");

        List<MovieData> movies = provider.fetchRatings(1L, null, null);
        assertEquals(1, movies.size());
        assertEquals(MovieStatus.RATED_AMBIGUOUS, movies.get(0).getStatus());
        assertEquals("проставлено с оговоркой", movies.get(0).getStatusLabel());
        assertTrue(movies.get(0).getStatus().isDone(), "строка done — повторно не ретраится");
    }

    @Test
    void parseStatusLabel_shouldMapRatedAmbiguousLabel() {
        assertEquals(MovieStatus.RATED_AMBIGUOUS, MovieData.parseStatusLabel("проставлено с оговоркой"));
        MovieData movie = new MovieData();
        movie.setStatus(MovieStatus.RATED_AMBIGUOUS);
        assertEquals("проставлено с оговоркой", movie.getStatusLabel());
    }

    @Test
    void movieStatus_isDone_singleSourceOfTruth() {
        // «Уже обработан» — единая логика в MovieStatus.isDone(), наборы статусов не расходятся.
        assertTrue(MovieStatus.RATED.isDone());
        assertTrue(MovieStatus.RATED_AMBIGUOUS.isDone(), "«проставлено с оговоркой» — завершённый статус");
        assertTrue(MovieStatus.SKIPPED_SAME.isDone());
        assertTrue(MovieStatus.SKIPPED_DIFFERENT.isDone());
        assertTrue(MovieStatus.INCOMPLETE_DATA.isDone());
        // Ретраятся при повторном прогоне:
        assertFalse(MovieStatus.NOT_FOUND.isDone(), "«не найден» ищется заново по названию+году");
        assertFalse(MovieStatus.ERROR.isDone());
        assertFalse(MovieStatus.PENDING.isDone());
    }

}
