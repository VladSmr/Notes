package ru.importer.notes.imdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты ImdbNotesExporter: дедупликации по kp_id перед обработкой
 * и по imdb_id при загрузке дампа (глюк API КП), включая evaluate-интеграцию.
 */
class ImdbDedupTest extends ImdbTestSupport {

    // ------------------------------------------------------------------
    // Дедупликация по kp_id
    // ------------------------------------------------------------------

    @Test
    void dedupeByKpId_keepsFirstOccurrenceRemovesDuplicates() {
        MovieData first = completeMovie(); // kpId 12928878, RATED из прошлого прогона
        first.setStatus(MovieStatus.RATED);
        MovieData dup1 = completeMovie();
        dup1.setName("Женщина-Халк (дубль 1)");
        MovieData dup2 = completeMovie();
        dup2.setName("Женщина-Халк (дубль 2)");
        MovieData other = completeMovie();
        other.setKpId(42L);
        MovieData noId = new MovieData();
        noId.setName("Без kp_id");

        List<MovieData> movies = new ArrayList<>(List.of(first, dup1, dup2, other, noId));

        int removed = ImdbNotesExporter.dedupeByKpId(movies);

        assertEquals(2, removed);
        assertEquals(3, movies.size());
        assertSame(first, movies.get(0), "первый встретившийся остаётся");
        assertSame(other, movies.get(1));
        assertSame(noId, movies.get(2), "фильм без kp_id не удаляется");
    }

    @Test
    void evaluate_removesDuplicateKpIdRowsBeforeProcessing() {
        MovieData first = completeMovie();
        first.setStatus(MovieStatus.RATED);
        MovieData dup1 = completeMovie();
        MovieData dup2 = completeMovie();
        List<MovieData> movies = new ArrayList<>(List.of(first, dup1, dup2));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        assertEquals(1, movies.size());
        assertSame(first, movies.get(0));
        verify(driver, never()).get(anyString());
    }

    // ------------------------------------------------------------------
    // Дедупликация по imdb_id при загрузке дампа (глюк API КП)
    // ------------------------------------------------------------------

    @Test
    void resetDuplicatedImdbIds_bothRowsWithSameId_resetToPending() {
        // Кейс из выгрузки: двум разным фильмам API отдал один imdbId подкаст-эпизода.
        // RATED-строка без сброса никогда бы не перечиталась — сбрасываются ОБЕ строки.
        MovieData rated = completeMovie(); // kpId 12928878
        rated.setImdbId("tt41621104");
        rated.setStatus(MovieStatus.RATED);
        MovieData pending = completeMovie();
        pending.setKpId(42L);
        pending.setName("Коммерсант");
        pending.setImdbId("tt41621104");

        List<MovieData> movies = new ArrayList<>(List.of(rated, pending));

        int fixed = ImdbNotesExporter.resetDuplicatedImdbIds(movies);

        assertEquals(2, fixed, "исправлены обе строки с задублированным id");
        assertNull(rated.getImdbId());
        assertNull(pending.getImdbId());
        assertEquals(MovieStatus.PENDING, rated.getStatus(),
                "RATED-строка снята с «done» — переищется по названию");
        assertEquals(MovieStatus.PENDING, pending.getStatus());
    }

    @Test
    void resetDuplicatedImdbIds_uniqueNullBlankAndInvalidIds_untouched() {
        MovieData unique = completeMovie();
        unique.setImdbId("tt0118767");
        unique.setStatus(MovieStatus.RATED);
        MovieData nullId = completeMovie();
        nullId.setImdbId(null);
        MovieData blankId = completeMovie();
        blankId.setKpId(1L);
        blankId.setImdbId("   ");
        // Одинаковые, но невалидные по формату id дедупом не группируются.
        MovieData invalid1 = completeMovie();
        invalid1.setKpId(2L);
        invalid1.setImdbId("12345");
        MovieData invalid2 = completeMovie();
        invalid2.setKpId(3L);
        invalid2.setImdbId("12345");

        List<MovieData> movies = new ArrayList<>(List.of(unique, nullId, blankId, invalid1, invalid2));

        int fixed = ImdbNotesExporter.resetDuplicatedImdbIds(movies);

        assertEquals(0, fixed, "никто не затронут: дублируется только невалидный формат id");
        assertEquals("tt0118767", unique.getImdbId());
        assertEquals(MovieStatus.RATED, unique.getStatus());
        assertEquals("12345", invalid1.getImdbId());
        assertEquals("12345", invalid2.getImdbId());
    }

    @Test
    void evaluate_duplicatedImdbIdRows_bothResetAndSearched() {
        // Интеграция: дедуп по imdb_id срабатывает при загрузке дампа ДО обработки.
        MovieData rated = completeMovie();
        rated.setImdbId("tt41621104");
        rated.setStatus(MovieStatus.RATED);
        MovieData pending = completeMovie();
        pending.setKpId(42L);
        pending.setName("Коммерсант");
        pending.setImdbId("tt41621104");

        List<MovieData> movies = new ArrayList<>(List.of(rated, pending));

        WebDriver driver = mockDriver(Collections.emptyList());

        exporter.evaluate(movies, driver, new ImportProgress(), () -> { });

        verify(driver, times(2)).get(org.mockito.ArgumentMatchers.contains("/find/?q="));
        verify(driver, never()).get(org.mockito.ArgumentMatchers.contains("/title/"));
        assertNull(rated.getImdbId());
        assertNull(pending.getImdbId());
        assertEquals(MovieStatus.NOT_FOUND, rated.getStatus());
        assertEquals(MovieStatus.NOT_FOUND, pending.getStatus());
    }

    // ------------------------------------------------------------------
    // Дедуп по imdb_id — легитимно общий id (одинаковые названия) не сбрасывается
    // ------------------------------------------------------------------

    @Test
    void resetDuplicatedImdbIds_sameNormalizedTitleRows_legitSharedIdNotReset() {
        // Легитимная двойная запись «фильм+сериал» (одинаковые названия): сброса нет —
        // иначе каждый прогон переискал бы и снова сбрасывал (бесконечная перекачка).
        MovieData film = completeMovie();
        film.setKpId(1L);
        film.setImdbId("tt13622970");
        film.setStatus(MovieStatus.RATED);
        MovieData series = completeMovie(); // то же название
        series.setKpId(2L);
        series.setImdbId("tt13622970");

        List<MovieData> movies = new ArrayList<>(List.of(film, series));

        int fixed = ImdbNotesExporter.resetDuplicatedImdbIds(movies);

        assertEquals(0, fixed, "одинаковые нормализованные названия — легитимно общий id");
        assertEquals("tt13622970", film.getImdbId());
        assertEquals(MovieStatus.RATED, film.getStatus(), "обработанная строка не сбрасывается в PENDING");
        assertEquals("tt13622970", series.getImdbId());
        assertEquals(MovieStatus.PENDING, series.getStatus());
    }

    @Test
    void resetDuplicatedImdbIds_differentNormalizedTitleRows_sharedIdStillReset() {
        // Под одним tt — два разных тайтла (глюк API КП): сбрасываются обе строки.
        MovieData rated = completeMovie(); // «Женщина-Халк: Адвокат»
        rated.setImdbId("tt41621104");
        rated.setStatus(MovieStatus.RATED);
        MovieData other = completeMovie();
        other.setKpId(42L);
        other.setName("Коммерсант");
        other.setImdbId("tt41621104");

        List<MovieData> movies = new ArrayList<>(List.of(rated, other));

        int fixed = ImdbNotesExporter.resetDuplicatedImdbIds(movies);

        assertEquals(2, fixed, "разные нормализованные названия — глюк, сбрасываются обе строки");
        assertNull(rated.getImdbId());
        assertEquals(MovieStatus.PENDING, rated.getStatus());
        assertNull(other.getImdbId());
        assertEquals(MovieStatus.PENDING, other.getStatus());
    }
}
