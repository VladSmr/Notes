package ru.importer.notes.kp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

/**
 * Полный парсинг оценок КП: Selenium (страницы оценок) + API-обогащение карточек.
 * Поддерживает resume: при наличии {@code kp-ratings-{userId}-full.csv} загружает
 * существующие данные и дополняет/обогащает только дефицитные и новые фильмы.
 */
@Slf4j
@Service
public class FullKpRatingsProvider implements KpRatingsProvider {

    private static final int BATCH_SIZE = 100;

    private final AuthManager authManager;
    private final KpNotesImporter notesImporter;
    private final ApiFilmCardEnricher enricher;
    private final LogFileService logFileService;

    @Autowired
    public FullKpRatingsProvider(AuthManager authManager,
                                  KpNotesImporter notesImporter,
                                  ApiFilmCardEnricher enricher,
                                  LogFileService logFileService) {
        this.authManager = authManager;
        this.notesImporter = notesImporter;
        this.enricher = enricher;
        this.logFileService = logFileService;
    }

    @Override
    public String getKey() {
        return "full";
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress) {
        return fetchRatings(userId, apiToken, progress, null);
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress,
                                        Consumer<List<MovieData>> onBatch) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Не указан API-токен Кинопоиска");
        }

        // ИНВАРИАНТ: дамп читается ДО любой записи в тот же файл.
        // Это гарантирует, что существующие imdbId / оригинальные названия / удалённые-из-КП
        // строки не будут потеряны при resume. Selenium-фаза накапливает данные in-memory,
        // запись в файл происходит только после merge — единым блоком.
        Map<Long, MovieData> dump = loadDump();

        // Фаза A: Selenium — страницы оценок (title/rating/год + kpId из ссылки).
        // onBatch=null: Selenium-фаза НЕ пишет в файл, чтобы не затереть существующий дамп
        // до merge. Промежуточные дампы после каждой страницы не нужны — merge фиксирует
        // состояние целиком, а обогащённые данные сохраняются при квоте в обогащённом блоке.
        List<MovieData> movies = seleniumPhase(userId, progress);

        if (progress != null && progress.isAborted()) {
            log.info("Остановлено после Selenium-фазы");
            return movies;
        }

        // Merge Selenium + дамп (dump — приоритетные данные).
        List<MovieData> merged = mergeDump(dump, movies);

        // Фиксируем merge-результат: это первый и единственный write в файл на фазе A.
        if (onBatch != null) {
            onBatch.accept(merged);
        }

        // Фаза B: обогащение только дефицитных/новых фильмов.
        List<MovieData> enriched = enrichPhase(merged, dump, apiToken, progress, onBatch);

        log.info("Full: завершено, фильмов в дампе: {}", enriched.size());
        return enriched;
    }

    /**
     * Selenium-фаза: переиспользует {@link KpNotesImporter#getNotes} —
     * страницы оценок по 20 фильмов (title/rating/год, kpId из ссылки).
     * Дедупликация по kpId между страницами. Запись в файл НЕ производится —
     * см. инвариант в {@link #fetchRatings(Long, String, ImportProgress, Consumer)}.
     * Драйвер передаётся явно: {@code null} был ошибкой при рефакторинге инварианта.
     */
    private List<MovieData> seleniumPhase(Long userId, ImportProgress progress) {
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            throw new IllegalStateException("Браузер КП не открыт");
        }
        List<MovieData> raw = notesImporter.getNotes(driver, userId, progress, null);
        return dedupByKpId(raw);
    }

    /** Дедупликация списка по kpId (сохраняет первый встреченный). */
    private List<MovieData> dedupByKpId(List<MovieData> movies) {
        Set<Long> seen = new HashSet<>();
        List<MovieData> result = new ArrayList<>();
        for (MovieData m : movies) {
            if (m.getKpId() != null && seen.add(m.getKpId())) {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * Загружает существующий дамп {@code kp-ratings-{userId}-full.csv}.
     * Возвращает карту kpId → MovieData.
     */
    private Map<Long, MovieData> loadDump() {
        Map<Long, MovieData> dump = new LinkedHashMap<>();
        List<String[]> rows = logFileService.readKpDump();
        if (rows == null) {
            return dump;
        }
        for (String[] row : rows) {
            MovieData m = parseDumpRow(row);
            if (m.getKpId() != null && m.getKpId() > 0) {
                dump.put(m.getKpId(), m);
            }
        }
        if (!dump.isEmpty()) {
            log.info("Full: загружено {} фильмов из существующего дампа", dump.size());
        }
        return dump;
    }

    /**
     * Смерживает Selenium-список с дампом.
     * Результат строится по dump: для каждого kpId берём dump-данные (приоритет),
     * для новых (нет в дампе) — fresh-данные. kpRating берётся из fresh
     * (актуальная оценка с КП), остальные поля — из dump (более полные).
     * Строки дампа без пары в Selenium-списке — сохраняются (пользователь удалил
     * фильм с КП, но дамп не удаляем).
     */
    List<MovieData> mergeDump(Map<Long, MovieData> dump, List<MovieData> fresh) {
        Map<Long, MovieData> seen = new LinkedHashMap<>();
        int newMovies = 0;

        // 1. Данные дампа — основа (сохраняем всё, включая удалённые из КП).
        for (MovieData d : dump.values()) {
            seen.put(d.getKpId(), d);
        }

        // 2. Selenium/fresh — обновляем kpRating и добавляем новые kpId.
        for (MovieData f : fresh) {
            Long kpId = f.getKpId();
            if (kpId == null) {
                continue;
            }
            MovieData existing = seen.get(kpId);
            if (existing == null) {
                // Новый kpId — храним fresh-объект (только что спарсен с КП).
                seen.put(kpId, f);
                newMovies++;
            } else {
                // Существующий kpId: копируем dump-данные (полные), обновляем kpRating
                // только если Selenium вернул валидную оценку (>0).
                MovieData merged = new MovieData();
                merged.setKpId(kpId);
                merged.setName(existing.getName());
                merged.setNameOriginal(existing.getNameOriginal());
                merged.setNameEn(existing.getNameEn());
                merged.setYear(existing.getYear());
                merged.setKpRating(f.getKpRating() > 0 ? f.getKpRating() : existing.getKpRating());
                merged.setImdbId(existing.getImdbId());
                merged.setKpUrl(existing.getKpUrl());
                merged.setStatus(existing.getStatus());
                merged.setErrorMessage(existing.getErrorMessage());
                seen.put(kpId, merged);
            }
        }

        if (newMovies > 0) {
            log.info("Full: добавлено новых фильмов (нет в дампе): {}", newMovies);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Обогащение фильмов из merged-списка.
     * Критерии: новый kpId ИЛИ (нет imdbId ИЛИ нет nameOriginal+nameEn ИЛИ невалидный год).
     * После каждого обогащённого — {@code progress.advance} и {@code waitWhilePaused()}.
     * Каждые BATCH_SIZE — промежуточный дамп через {@code onBatch}.
     * 402×3 → {@link KpQuotaExhaustedException}: частичный дамп сохраняется, исключение пробрасывается.
     */
    private List<MovieData> enrichPhase(List<MovieData> movies, Map<Long, MovieData> dump,
                                         String apiToken, ImportProgress progress,
                                         Consumer<List<MovieData>> onBatch) {
        // Считаем фильмы, требующие обогащения.
        // Новый kpId (не было в дампе) — всегда обогащаем.
        // Существующий kpId — обогащаем ТОЛЬКО при дефиците (isBlank-поля).
        List<MovieData> toEnrich = new ArrayList<>();
        for (MovieData m : movies) {
            Long kpId = m.getKpId();
            boolean isNew = kpId != null && !dump.containsKey(kpId);
            if (isNew || needsEnrichment(m)) {
                toEnrich.add(m);
            }
        }

        if (toEnrich.isEmpty()) {
            log.info("Full: все фильмы уже обогащены, обогащение не требуется");
            return movies;
        }

        // Пересчитываем знаменатель: страницы Selenium + обогащения.
        int pagesScanned = progress != null ? progress.getCurrent() : 0;
        if (progress != null) {
            progress.resetTotal(pagesScanned + toEnrich.size());
        }
        log.info("Full: обогащение {} фильмов", toEnrich.size());

        int enrichedCount = 0;
        int skippedAlreadyFull = 0;
        for (MovieData movie : toEnrich) {
            if (progress != null) {
                progress.waitWhilePaused();
                if (progress.isAborted()) {
                    log.info("Full: обогащение остановлено пользователем ({}/{})", enrichedCount, toEnrich.size());
                    break;
                }
            }

            // Был ли фильм «полным» до обогащения? (нужен для итогового счётчика skip.)
            boolean wasComplete = !needsEnrichment(movie);

            log.info("API: обогащаю '{}' (kpId={})", movie.getName(), movie.getKpId());
            try {
                enricher.enrich(movie, apiToken, progress);
                String imdbId = movie.getImdbId();
                int year = movie.getYear();
                String nameOrig = movie.getNameOriginal();
                if (imdbId != null && !imdbId.isBlank()) {
                    log.info("API: карточка получена: imdbId={}, год={}, ориг. название={}",
                             imdbId, year > 0 ? year : "—", nameOrig != null ? nameOrig : "—");
                } else if (year > 0) {
                    log.info("API: карточка получена: год={}, ориг. название={}",
                             year, nameOrig != null ? nameOrig : "—");
                } else {
                    log.info("API: карточка получена: ориг. название={}", nameOrig != null ? nameOrig : "—");
                }
                enrichedCount++;
            } catch (KpQuotaExhaustedException e) {
                log.warn("Full: исчерпана квота API — сохранён частичный дамп ({} обогащённых из {})",
                         enrichedCount, toEnrich.size());
                // Фиксируем все обогащённые (включая последний блок < BATCH_SIZE) перед пробросом.
                if (onBatch != null) {
                    onBatch.accept(movies);
                }
                throw e;
            } catch (Exception e) {
                log.warn("API: ошибка карточки '{}': {}", movie.getName(), e.getMessage());
                // Считать как «пропущен» только если он не был полным ДО обогащения.
                if (!wasComplete) {
                    skippedAlreadyFull++;
                }
                // Продолжаем со следующим фильмом — не бросаем.
            }

            // advance на каждый обогащённый.
            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, movie.getName(), "api");
            }

            // Промежуточный дамп каждые BATCH_SIZE.
            if (onBatch != null && enrichedCount % BATCH_SIZE == 0) {
                onBatch.accept(movies);
            }
        }

        // Финальный дамп в конце обогащения.
        if (onBatch != null && enrichedCount > 0) {
            onBatch.accept(movies);
        }

        int total = toEnrich.size();
        int skipped = total - enrichedCount - skippedAlreadyFull;
        log.info("Обогащено: {} из {}, пропущено (уже полные): {}", enrichedCount, total, skipped);

        return movies;
    }

    /**
     * Фильм требует API-обогащения, если:
     * — новый (не было в дампе) ИЛИ
     * — нет imdb_id (null или пусто) ИЛИ
     * — нет ни nameOriginal, ни nameEn (null или пусто) ИЛИ
     * — год невалиден.
     * Пустые строки из CSV дампа (CSV не различает null/"") считаются пустыми.
     */
    private boolean needsEnrichment(MovieData m) {
        boolean noNames = isBlank(m.getNameOriginal()) && isBlank(m.getNameEn());
        boolean noYear = !KpYearValidator.isValidYear(m.getYear());
        return noNames || noYear || isBlank(m.getImdbId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        WebDriver driver = authManager.getDriver();
        if (driver == null) {
            return null;
        }
        return notesImporter.fetchTotalRatings(driver, userId);
    }

    @Override
    public void validateToken(Long userId, String apiToken) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Не указан API-токен Кинопоиска");
        }
        // Пробный запрос карточки — достаточно для проверки токена.
        // fetchFilm использует тот же retry-механизм (401/403 отлавливается).
        enricher.fetchFilm(301L, apiToken);
    }

    // ---- Разбор строки дампа ----

    private static String col(String[] row, int i) {
        if (i >= row.length) { return null; }
        String v = row[i];
        if (v == null) { return null; }
        return v.isBlank() ? null : v.trim();
    }

    private static int parseInt(String v) {
        if (v == null) { return 0; }
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static Long parseLong(String v) {
        if (v == null) { return null; }
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private MovieData parseDumpRow(String[] row) {
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

    // Для тестов: возможность подменить импортер.
    FullKpRatingsProvider withNotesImporter(KpNotesImporter importer) {
        return new FullKpRatingsProvider(authManager, importer, enricher, logFileService);
    }
}