package ru.importer.notes.kp;

import java.util.List;
import java.util.function.Consumer;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.movie.ImportProgress;

/** Парсинг оценок КП через Selenium: залогиненная сессия браузера + страницы фильмов для оригинальных названий. */
@Service
public class SeleniumKpRatingsProvider implements KpRatingsProvider {

    private static final Logger log = LoggerFactory.getLogger(SeleniumKpRatingsProvider.class);

    private final AuthManager authManager;
    private final KpNotesImporter notesImporter;

    public SeleniumKpRatingsProvider(AuthManager authManager, KpNotesImporter notesImporter) {
        this.authManager = authManager;
        this.notesImporter = notesImporter;
    }

    @Override
    public String getKey() {
        return "selenium";
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress) {
        return fetchRatings(userId, apiToken, progress, null);
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress,
                                        Consumer<List<MovieData>> onBatch) {
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            throw new IllegalStateException("Browser is not open");
        }
        // getNotes наполняет список по страницам (≈20 фильмов) — колбэк вызывается после
        // каждой страницы, чтобы промежуточный дамп появлялся по ходу парсинга.
        List<MovieData> movies = notesImporter.getNotes(driver, userId, progress, onBatch);

        // Знаменатель фазы 2 (original titles) зависит от фактического числа missing-фильмов,
        // которое известно только после фазы 1. Пересчитываем полный знаменатель:
        //   total = (единицы фазы 1 = число просканированных страниц = progress.getCurrent())
        //         + (число missing-фильмов с URL, по которым фаза 2 сделает advance).
        // Это приводит знаменатель в соответствие с реальным числом advance, чтобы current
        // достигал total ровно в конце (инвариант детерминированной полосы). Фильмы без URL
        // в фазе 2 пропускаются БЕЗ advance (см. KpNotesImporter.fetchOriginalTitles), поэтому
        // в знаменатель не входят.
        if (progress != null) {
            long missingWithUrl = movies.stream()
                    .filter(m -> m.getNameEn() == null || m.getNameEn().isBlank())
                    .filter(m -> m.getKpUrl() != null || m.getKpId() != null)
                    .count();
            int phase1Units = progress.getCurrent(); // число просканированных страниц
            progress.resetTotal(phase1Units + (int) missingWithUrl);
        }

        long missingOriginals = movies.stream()
                .filter(m -> m.getNameEn() == null || m.getNameEn().isBlank())
                .count();
        if (missingOriginals > 0 && !(progress != null && progress.isAborted())) {
            log.info("Загрузка оригинальных названий со страниц фильмов: {}", missingOriginals);
            // onBatch передаём дальше: fetchOriginalTitles сохраняет промежуточный дамп
            // каждые 5 фильмов и в конце фазы (в т.ч. при остановке пользователем).
            notesImporter.fetchOriginalTitles(movies, driver, progress, onBatch);
            log.info("Оригинальные названия загружены.");
        }
        return movies;
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            return null;
        }
        return notesImporter.fetchTotalRatings(driver, userId);
    }

}
