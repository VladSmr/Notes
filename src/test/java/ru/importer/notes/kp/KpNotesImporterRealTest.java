package ru.importer.notes.kp;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class KpNotesImporterRealTest {

    private final KpNotesImporter importer = new KpNotesImporter();

    @Test
    void getNotes_shouldReturnMoviesForUser52241377() {
        List<MovieData> movies = importer.getNotes(52241377L);
        assertNotNull(movies);
        assertFalse(movies.isEmpty(), "User 52241377 should have at least one rated movie");

        MovieData first = movies.get(0);
        assertNotNull(first.getName());
        assertTrue(first.getYear() > 1900);
        assertTrue(first.getKpRating() >= 1 && first.getKpRating() <= 10);
        assertNotNull(first.getKpId());
    }

    @Test
    void getNotes_shouldReturnMoviesForUser15112993() {
        List<MovieData> movies = importer.getNotes(15112993L);
        assertNotNull(movies);
        assertFalse(movies.isEmpty(), "User 15112993 should have at least one rated movie");

        MovieData first = movies.get(0);
        assertNotNull(first.getName());
        assertTrue(first.getYear() > 1900);
    }

    @Test
    void getNotes_shouldReturnMoviesForUser1608271() {
        List<MovieData> movies = importer.getNotes(1608271L);
        assertNotNull(movies);
        assertFalse(movies.isEmpty(), "User 1608271 should have at least one rated movie");
    }

    @Test
    void getNotes_shouldReturnMoviesForUser123234600() {
        List<MovieData> movies = importer.getNotes(123234600L);
        assertNotNull(movies);
        assertFalse(movies.isEmpty(), "User 123234600 should have at least one rated movie");
    }

    @Test
    void getNotes_shouldContainExpectedMovie() {
        List<MovieData> movies = importer.getNotes(52241377L);
        boolean found = movies.stream().anyMatch(m ->
                "Побег из Шоушенка".equals(m.getName()) || m.getKpId() == 5273L
        );
        assertTrue(found, "User 52241377 should have 'Побег из Шоушенка' (id 5273) in their ratings");
    }

}
