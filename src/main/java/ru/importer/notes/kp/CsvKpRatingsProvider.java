package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

/** Чтение оценок из ранее сохранённого дампа kp-ratings.csv вместо повторного парсинга КП/API. */
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
        for (String[] row : rows) {
            MovieData m = new MovieData();
            m.setName(col(row, 0));
            m.setNameOriginal(col(row, 1));
            m.setNameEn(col(row, 2));
            m.setYear(parseInt(col(row, 3)));
            m.setKpRating(parseInt(col(row, 4)));
            m.setKpId(parseLong(col(row, 5)));
            m.setImdbId(col(row, 6));
            if (m.getName() == null) {
                m.setName(m.getNameOriginal() != null ? m.getNameOriginal() : m.getNameEn());
            }
            movies.add(m);
        }
        log.info("CSV: загружено фильмов из дампа: {}", movies.size());
        return movies;
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        List<String[]> rows = logFile.readKpDump();
        return rows == null ? null : rows.size();
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
