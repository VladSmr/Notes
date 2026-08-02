package ru.importer.notes.kp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Реальный запрос к kinopoiskapiunofficial.tech.
 * Токен: -Dkp.api.token=... (или переменная окружения KP_API_TOKEN).
 * Запуск: mvnw test -Dtest=ApiKpRatingsProviderRealTest -Dgroups=integration -DskipTests=false
 */
@Tag("integration")
class ApiKpRatingsProviderRealTest {

    @TempDir
    Path tempDir;

    private final ApiKpRatingsProvider provider = new ApiKpRatingsProvider(new ObjectMapper());

    private String token() {
        String t = System.getProperty("kp.api.token");
        if (t == null || t.isBlank()) {
            t = System.getenv("KP_API_TOKEN");
        }
        return t;
    }

    @Test
    void fetchRatings_shouldReturnRatingsForUser4845070() {
        String token = token();
        assertNotNull(token, "Set token via -Dkp.api.token=... or KP_API_TOKEN env var");

        List<MovieData> movies = provider.fetchRatings(4845070L, token, null);
        assertFalse(movies.isEmpty(), "User 4845070 should have at least one rated movie");

        System.out.println("TOTAL RATINGS for 4845070: " + movies.size());
        for (MovieData m : movies) {
            System.out.println(m.getKpRating() + " | " + m.getYear() + " | "
                    + m.getName() + " | " + m.getNameEn() + " | " + m.getKpUrl());
        }
    }

}
