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
 * Возвращает все строки-фильмы дампа (с валидным kp_id); строки-мусор без kp_id
 * пропускаются с предупреждением. Пропуск уже обработанных делает ImdbNotesExporter,
 * чтобы дамп никогда не урезался и не терял ранее обработанные фильмы.
 * При чтении зачищаются склейки «название+год» (год ДОВЕРЯЕТСЯ хвосту, а не полю строки:
 * сценарий «Волчья яма 22013»/2003 → «Волчья яма 2»/2013).
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
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            MovieData m = parseRow(row);
            if (m.getKpId() == null || m.getKpId() <= 0) {
                // Строка без валидного kp_id — мусор из старого/битого дампа
                // (например, обрывок многострочной ошибки). В фильм не превращаем:
                // иначе она получит статус «неполные данные» и попадёт в этап.
                skipped++;
                log.warn("CSV: строка {} пропущена — нет валидного kp_id (фрагмент: {})",
                         i + 1, rowFragment(row));
                continue;
            }
            if (m.getStatus().isDone()) {
                done++;
            }
            movies.add(m);
        }
        if (skipped > 0) {
            log.warn("CSV: пропущено строк без валидного kp_id: {}", skipped);
        }
        log.info("CSV: загружено фильмов из дампа: {} (из них уже обработанных: {})",
                 movies.size(), done);
        return movies;
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        List<String[]> rows = logFile.readKpDump();
        if (rows == null) {
            return null;
        }
        // Считаем только строки-фильмы (валидный kp_id) — согласовано с fetchRatings,
        // чтобы прогресс-бар не завышался мусорными строками.
        int count = 0;
        for (String[] row : rows) {
            Long kpId = parseLong(col(row, 5));
            if (kpId != null && kpId > 0) {
                count++;
            }
        }
        return count;
    }

    /** Короткий фрагмент строки для лога (не выводим целиком многострочные ошибки). */
    private static String rowFragment(String[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            String value = row[i] == null ? "" : row[i].replace('\n', ' ').replace('\r', ' ');
            if (value.length() > 40) {
                value = value.substring(0, 40) + "…";
            }
            if (i > 0) {
                sb.append(';');
            }
            sb.append(value);
            if (sb.length() > 120) {
                break;
            }
        }
        return sb.toString();
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
        cleanTrustedGluedTail(m);
        // Английское/оригинальное названия тоже участвуют в поиске (candidateTitles) —
        // при наличии хвост-склейки отрезаем тот же хвост-год (год не переопределяем).
        m.setNameOriginal(stripTrustedTail(m.getNameOriginal()));
        m.setNameEn(stripTrustedTail(m.getNameEn()));
        return m;
    }

    /** Отрезает склейку «название+год» (режим «доверять хвосту»); без склейки — как есть. */
    private static String stripTrustedTail(String title) {
        if (title == null || GluedYearTitleCleaner.extractTrustedGluedYear(title) <= 0) {
            return title;
        }
        return GluedYearTitleCleaner.stripTrustedGluedYear(title);
    }

    /**
     * Зачистка склейки «название+год» при чтении дампа: если название оканчивается цифровым
     * хвостом из ≥5 цифр с валидным годом на конце, год берётся ИЗ ХВОСТА (год строки может
     * быть мусором), а хвост-год отрезается. Исправленный title/year попадает и в
     * перезаписываемый дамп (строки сохраняются теми же объектами MovieData).
     */
    private static void cleanTrustedGluedTail(MovieData m) {
        String name = m.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        int tailYear = GluedYearTitleCleaner.extractTrustedGluedYear(name);
        if (tailYear <= 0) {
            return;
        }
        String stripped = GluedYearTitleCleaner.stripTrustedGluedYear(name);
        if (stripped.equals(name)) {
            return;
        }
        log.warn("CSV: название '{}' склеено с годом — исправлено на '{}' (год {} вместо {})",
                 name, stripped, tailYear, m.getYear());
        m.setName(stripped);
        m.setYear(tailYear);
    }

}
