package ru.importer.notes.kp;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridKpRatingsProviderTest {

    private static MovieData movie(Long kpId) {
        MovieData m = new MovieData();
        m.setKpId(kpId);
        return m;
    }

    @Test
    void merge_shouldDropRestDuplicatesByKpId() {
        MovieData a = movie(1L);
        MovieData b = movie(2L);
        MovieData bAgain = movie(2L);
        MovieData c = movie(3L);
        MovieData noId = new MovieData();

        List<MovieData> merged = HybridKpRatingsProvider.mergeApiAndRest(
                List.of(a, b), List.of(bAgain, c, noId));

        assertEquals(4, merged.size());
        assertEquals(a, merged.get(0));
        assertEquals(b, merged.get(1));
        assertEquals(c, merged.get(2));
        assertEquals(noId, merged.get(3));
    }

    @Test
    void merge_shouldKeepApiItemsWhenRestEmpty() {
        MovieData a = movie(1L);
        List<MovieData> merged = HybridKpRatingsProvider.mergeApiAndRest(List.of(a), List.of());
        assertEquals(1, merged.size());
        assertEquals(a, merged.get(0));
    }

    @Test
    void restStartPage_shouldStartAfterApiCoverage() {
        assertEquals(1, HybridKpRatingsProvider.restStartPage(0));
        assertEquals(1, HybridKpRatingsProvider.restStartPage(1));
        assertEquals(2, HybridKpRatingsProvider.restStartPage(20));
        assertEquals(50, HybridKpRatingsProvider.restStartPage(995));
        assertEquals(76, HybridKpRatingsProvider.restStartPage(1500));
    }

}
