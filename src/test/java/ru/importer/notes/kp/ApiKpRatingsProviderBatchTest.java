package ru.importer.notes.kp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-тест периодического колбэка «накоплено N фильмов» в ApiKpRatingsProvider.
 * Подменяет {@link ApiKpRatingsProvider#fetchPage}, чтобы не ходить в сеть.
 */
class ApiKpRatingsProviderBatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Провайдер с подменённым fetchPage: отдаёт страницы по {@code itemsPerPage} фильмов. */
    private static class StubApiProvider extends ApiKpRatingsProvider {
        private final ObjectMapper om;
        private final int itemsPerPage;
        private final int totalPages;
        private final AtomicInteger pagesRequested = new AtomicInteger();

        StubApiProvider(ObjectMapper om, int itemsPerPage, int totalPages) {
            super(om);
            this.om = om;
            this.itemsPerPage = itemsPerPage;
            this.totalPages = totalPages;
        }

        @Override
        protected JsonNode fetchPage(Long userId, String apiToken, int page) {
            pagesRequested.incrementAndGet();
            ObjectNode root = om.createObjectNode();
            root.put("totalPages", totalPages);
            ArrayNode items = root.putArray("items");
            for (int i = 0; i < itemsPerPage; i++) {
                ObjectNode item = items.addObject();
                item.put("kinopoiskId", (long) (page - 1) * itemsPerPage + i + 1);
                item.put("nameRu", "Фильм " + ((page - 1) * itemsPerPage + i + 1));
                item.put("nameEn", "Movie " + ((page - 1) * itemsPerPage + i + 1));
                item.put("year", "2000");
                item.put("type", "FILM");
                item.put("userRating", 5);
            }
            return root;
        }
    }

    @Test
    void fetchRatings_shouldInvokeCallbackEvery100Movies() {
        // 3 страницы по 100 фильмов = 300 фильмов; колбэк должен сработать на 100-м, 200-м и 300-м.
        StubApiProvider provider = new StubApiProvider(mapper, 100, 3);

        List<Integer> batchSizes = new ArrayList<>();
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, batch -> batchSizes.add(batch.size()));

        assertEquals(300, movies.size());
        assertEquals(List.of(100, 200, 300), batchSizes);
    }

    @Test
    void fetchRatings_shouldNotInvokeCallbackWhenBelow100() {
        StubApiProvider provider = new StubApiProvider(mapper, 50, 2); // 100 фильмов суммарно

        List<Integer> batchSizes = new ArrayList<>();
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, batch -> batchSizes.add(batch.size()));

        // На 100-м фильме колбэк сработает один раз (последний элемент второй страницы).
        assertEquals(100, movies.size());
        assertEquals(List.of(100), batchSizes);
    }

    @Test
    void fetchRatings_shouldWorkWithoutCallback() {
        StubApiProvider provider = new StubApiProvider(mapper, 100, 2);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);
        assertEquals(200, movies.size());
    }
}
