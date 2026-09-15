package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

/**
 * Источник этапа «Проставление»: читает оценки из выбранного дампа
 * (kp-ratings-{userId}-{метод}.csv или старый kp-ratings.csv; выбор файла — в
 * {@link LogFileService}, способ {@code saved}).
 * Возвращает ВСЕ строки дампа — пропуск обработанных делает ImdbNotesExporter,
 * чтобы дамп никогда не урезался и не терял ранее обработанные фильмы.
 */
@Slf4j
@Service
public class CsvKpRatingsProvider implements KpRatingsProvider {

    private final LogFileService logFile;

    public CsvKpRatingsProvider(LogFileService logFile) {
        this.logFile = logFile;
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
            if (m.getStatus().isDone()) {
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

}
