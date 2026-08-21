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

        long missingOriginals = movies.stream()
                .filter(m -> m.getNameEn() == null || m.getNameEn().isBlank())
                .count();
        if (missingOriginals > 0 && !(progress != null && progress.isAborted())) {
            log.info("Загрузка оригинальных названий со страниц фильмов: {}", missingOriginals);
            notesImporter.fetchOriginalTitles(movies, driver, progress);
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
