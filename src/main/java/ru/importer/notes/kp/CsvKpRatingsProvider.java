package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieData.MovieStatus;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

/**
 * Чтение оценок из ранее сохранённого дампа kp-ratings.csv вместо повторного парсинга КП/API.
 * Возвращает ВСЕ строки дампа (со статусами) — пропуск уже обработанных делает
 * ImdbNotesExporter в цикле обработки, чтобы при сохранении порций дамп никогда не урезался
 * и не терял ранее обработанные фильмы.
 */
@Service
public class CsvKpRatingsProvider implements KpRatingsProvider {

    private static final Logger log = LoggerFactory.getLogger(CsvKpRatingsProvider.class);

    private final LogFileService logFile;

    public CsvKpRatingsProvider(LogFileService logFile) {
        this.logFile = logFile;
    }

    @Override
    public String getKey() {
        return "saved";
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress) {
        List<String[]> rows = logFile.readKpDump();
        List<MovieData> movies = new ArrayList<>();
        if (rows == null) {
            return movies;
        }
        int done = 0;
        for (String[] row : rows) {
            MovieData m = parseRow(row);
            if (isDone(m)) {
                done++;
            }
            movies.add(m);
        }
        log.info("CSV: загружено фильмов из дампа: {} (из них уже обработанных: {})",
                movies.size(), done);
        return movies;
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        List<String[]> rows = logFile.readKpDump();
        return rows == null ? null : rows.size();
    }

    /** Фильм уже обработан в прошлом запуске — повторно проставлять не нужно. */
    private boolean isDone(MovieData m) {
        return m.getStatus() == MovieStatus.RATED
                || m.getStatus() == MovieStatus.NOT_FOUND
                || m.getStatus() == MovieStatus.SKIPPED_SAME
                || m.getStatus() == MovieStatus.SKIPPED_DIFFERENT;
    }

    private MovieData parseRow(String[] row) {
        MovieData m = new MovieData();
        m.setName(col(row, 0));
        m.setNameOriginal(col(row, 1));
        m.setNameEn(col(row, 2));
        m.setYear(parseInt(col(row, 3)));
        m.setKpRating(parseInt(col(row, 4)));
        m.setKpId(parseLong(col(row, 5)));
        m.setImdbId(col(row, 6));
        m.setStatus(MovieData.parseStatusLabel(col(row, 7)));
        m.setErrorMessage(col(row, 8));
        if (m.getName() == null) {
            m.setName(m.getNameOriginal() != null ? m.getNameOriginal() : m.getNameEn());
        }
        return m;
    }

    private static String col(String[] row, int index) {
        if (index >= row.length) {
            return null;
        }
        String value = row[index];
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
