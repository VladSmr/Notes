package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.movie.ImportProgress;

/**
 * Гибридный способ для пользователей с большим числом оценок: API отдаёт только
 * последние ~1500, поэтому первые страницы берём из API, а более старые оценки —
 * через Selenium со страницы {@code apiCount / PER_PAGE + 1}. Пересечения на границе
 * страниц отбрасываются по kpId.
 */
@Service
public class HybridKpRatingsProvider implements KpRatingsProvider {

    private static final Logger log = LoggerFactory.getLogger(HybridKpRatingsProvider.class);

    private final ApiKpRatingsProvider apiProvider;
    private final KpNotesImporter notesImporter;
    private final AuthManager authManager;

    public HybridKpRatingsProvider(ApiKpRatingsProvider apiProvider, KpNotesImporter notesImporter,
                                   AuthManager authManager) {
        this.apiProvider = apiProvider;
        this.notesImporter = notesImporter;
        this.authManager = authManager;
    }

    @Override
    public String getKey() {
        return "hybrid";
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Не указан API-токен Кинопоиска");
        }

        List<MovieData> fromApi = apiProvider.fetchRatings(userId, apiToken, progress);
        if (progress != null && progress.isAborted()) {
            return fromApi;
        }

        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            throw new IllegalStateException("Browser is not open");
        }

        int startPage = restStartPage(fromApi.size());
        log.info("Гибрид: API вернул {} оценок, добираю остальные через Selenium со страницы {}",
                fromApi.size(), startPage);
        List<MovieData> rest = notesImporter.getNotes(driver, userId, progress, startPage);
        List<MovieData> merged = mergeApiAndRest(fromApi, rest);
        log.info("Гибрид: всего фильмов: {} (API: {} + Selenium: {})", merged.size(), fromApi.size(), rest.size());

        long missingOriginals = merged.stream()
                .filter(m -> m.getNameEn() == null || m.getNameEn().isBlank())
                .count();
        if (missingOriginals > 0 && !(progress != null && progress.isAborted())) {
            log.info("Гибрид: загрузка оригинальных названий со страниц фильмов: {}", missingOriginals);
            notesImporter.fetchOriginalTitles(merged, driver, progress);
        }
        return merged;
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        Integer apiTotal = apiProvider.fetchTotalRatings(userId, apiToken);
        if (apiTotal != null) {
            return apiTotal;
        }
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            return null;
        }
        return notesImporter.fetchTotalRatings(driver, userId);
    }

    /** Страница, с которой Selenium продолжает после API: первая, ещё не покрытая API. */
    static int restStartPage(int apiCount) {
        return apiCount / KpNotesImporter.PER_PAGE + 1;
    }

    /** Объединяет список API (новые оценки) с хвостом из Selenium, отбрасывая дубликаты по kpId. package-private для тестов. */
    static List<MovieData> mergeApiAndRest(List<MovieData> fromApi, List<MovieData> rest) {
        List<MovieData> merged = new ArrayList<>(fromApi);
        Set<Long> seen = new HashSet<>();
        for (MovieData m : fromApi) {
            if (m.getKpId() != null) {
                seen.add(m.getKpId());
            }
        }
        for (MovieData m : rest) {
            if (m.getKpId() != null && seen.contains(m.getKpId())) {
                continue;
            }
            if (m.getKpId() != null) {
                seen.add(m.getKpId());
            }
            merged.add(m);
        }
        return merged;
    }

}
