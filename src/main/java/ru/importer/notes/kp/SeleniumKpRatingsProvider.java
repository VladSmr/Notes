package ru.importer.notes.kp;

import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.movie.ImportProgress;

/** Парсинг оценок КП через Selenium: залогиненная сессия браузера + страницы фильмов
 *  для оригинальных названий и валидации годов. */
@Slf4j
@Service
public class SeleniumKpRatingsProvider implements KpRatingsProvider {

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
        // getNotes наполняет список по страницам; колбэк — после каждой страницы.
        List<MovieData> movies = notesImporter.getNotes(driver, userId, progress, onBatch);

        // Знаменатель фазы 2 (original titles) известен только после фазы 1 — пересчитываем:
        // страницы фазы 1 + missing-фильмы с URL (без URL — без advance, в знаменатель не входят).
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
            // onBatch передаём дальше: дамп сохраняется каждые 5 фильмов и в конце фазы.
            notesImporter.fetchOriginalTitles(movies, driver, progress, onBatch);
            log.info("Оригинальные названия загружены.");
        }

        // Фаза 3: валидация невалидных годов — после фазы 2, чтобы не ходить на страницу
        // фильма дважды. Знаменатель пересчитывается по факту (после фазы 2 известен
        // достоверно); фильмы без URL пропускаются без advance и в знаменатель не входят.
        if (!(progress != null && progress.isAborted())) {
            long invalidYears = movies.stream()
                    .filter(m -> !KpYearValidator.isValidYear(m.getYear()))
                    .filter(m -> m.getKpUrl() != null || m.getKpId() != null)
                    .count();
            if (invalidYears > 0) {
                log.info("Валидация невалидных годов со страниц фильмов: {}", invalidYears);
                if (progress != null) {
                    progress.resetTotal(progress.getCurrent() + (int) invalidYears);
                }
                // onBatch передаём дальше: дамп сохраняется по ходу фазы.
                notesImporter.fixInvalidYears(movies, driver, progress, onBatch);
            }
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
