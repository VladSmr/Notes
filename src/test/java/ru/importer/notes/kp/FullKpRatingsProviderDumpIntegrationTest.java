package ru.importer.notes.kp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebDriver;
import org.mockito.Mockito;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Интеграционные тесты FullKpRatingsProvider с РЕАЛЬНЫМ LogFileService.
 * Используют РЕАЛЬНЫЙ LogFileService для записи CSV (с BOM и правильным форматом),
 * а не ручной writeString. Это гарантирует что readKpDump() читает то, что записано.
 */
class FullKpRatingsProviderDumpIntegrationTest {

    @TempDir
    Path tempDir;

    private LogFileService logFileService;

    @BeforeEach
    void setUp() {
        logFileService = new LogFileService();
        logFileService.setLogDir(tempDir.toString());
        logFileService.setKpDumpName(7L, "full");
    }

    /**
     * Записывает дамп через реальный LogFileService (формат с BOM, ';').
     * Каждая строка — один фильм в формате saveKpDump.
     * @param kpRatings (name;nameOriginal;nameEn;year;rating;kpId;imdbId;RATED;).
     */
    private void writeDump(String... kpRatings) {
        logFileService.saveKpDump(kpRatings);
    }

    @SuppressWarnings("unchecked")
    private KpNotesImporter mockNotesImporter(List<MovieData> films) {
        KpNotesImporter importer = mock(KpNotesImporter.class);
        doReturn(new ArrayList<>(films))
                .when(importer).getNotes(any(), eq(7L), any(), any());
        return importer;
    }

    private FullKpRatingsProvider provider(KpNotesImporter notesImporter,
                                           ApiFilmCardEnricher enricher) {
        // Selenium-фаза требует реальный драйвер (authManager.getDriver()).
        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(Mockito.mock(WebDriver.class));
        return new FullKpRatingsProvider(authManager, notesImporter,
                                        enricher, logFileService);
    }

    // ---- (а) imdb_id и оригинальное название сохраняются после merge ----

    @Test
    void existingDump_imdbAndOriginalName_preservedAfterMerge() {
        // Записываем дамп через реальный LogFileService.
        writeDump("Фильм;Original Title;EN Title;2021;8;777;tt123456;RATED;");

        MovieData seleniumMovie = new MovieData();
        seleniumMovie.setKpId(777L);
        seleniumMovie.setName("Фильм");
        seleniumMovie.setYear(2021);
        seleniumMovie.setKpRating(9);

        ImportProgress progress = new ImportProgress();
        List<MovieData> result = provider(mockNotesImporter(List.of(seleniumMovie)),
                                          mock(ApiFilmCardEnricher.class))
                .fetchRatings(7L, "token", progress, movies -> { });

        MovieData film777 = result.stream()
                .filter(m -> m.getKpId() != null && m.getKpId().equals(777L))
                .findFirst().orElseThrow();

        assertEquals("Original Title", film777.getNameOriginal(),
                     "оригинальное название из дампа должно сохраниться");
        assertEquals("tt123456", film777.getImdbId(),
                     "imdbId из дампа должен сохраниться");
        assertEquals(9, film777.getKpRating(),
                     "kpRating должен обновиться с Selenium");
    }

    // ---- (б) удалённый из КП фильм сохраняется в дампе ----

    @Test
    void existingDump_deletedFromKp_preservedInResult() {
        writeDump("Удалённый фильм;Deleted Film;Deleted EN;2019;7;999;tt999999;RATED;");

        ImportProgress progress = new ImportProgress();
        List<MovieData> result = provider(mockNotesImporter(List.of()),
                                          mock(ApiFilmCardEnricher.class))
                .fetchRatings(7L, "token", progress, movies -> { });

        MovieData film999 = result.stream()
                .filter(m -> m.getKpId() != null && m.getKpId().equals(999L))
                .findFirst().orElse(null);
        assertTrue(film999 != null,
                   "фильм, удалённый из КП, должен сохраниться в дампе");
        assertEquals("Удалённый фильм", film999.getName());
        assertEquals("tt999999", film999.getImdbId());
    }

    // ---- (в) новый фильм добавляется ----

    @Test
    void seleniumNewMovie_addedToResult() {
        MovieData newMovie = new MovieData();
        newMovie.setKpId(888L);
        newMovie.setName("Новый фильм");
        newMovie.setYear(2023);
        newMovie.setKpRating(8);

        ImportProgress progress = new ImportProgress();
        List<MovieData> result = provider(mockNotesImporter(List.of(newMovie)),
                                          mock(ApiFilmCardEnricher.class))
                .fetchRatings(7L, "token", progress, movies -> { });

        MovieData film888 = result.stream()
                .filter(m -> m.getKpId() != null && m.getKpId().equals(888L))
                .findFirst().orElse(null);
        assertTrue(film888 != null, "новый фильм с Selenium должен быть добавлен");
        assertEquals("Новый фильм", film888.getName());
        assertEquals(8, film888.getKpRating());
    }

    // ---- (г) kpRating=0 из Selenium НЕ затирает валидную оценку из дампа ----

    @Test
    void seleniumRatingZero_doesNotOverwriteValidRating() {
        writeDump("Фильм с рейтингом;Some Title;EN Title;2021;8;777;tt123456;RATED;");

        MovieData seleniumMovie = new MovieData();
        seleniumMovie.setKpId(777L);
        seleniumMovie.setName("Фильм с рейтингом");
        seleniumMovie.setYear(2021);
        seleniumMovie.setKpRating(0); // Selenium вернул 0.

        ImportProgress progress = new ImportProgress();
        List<MovieData> result = provider(mockNotesImporter(List.of(seleniumMovie)),
                                          mock(ApiFilmCardEnricher.class))
                .fetchRatings(7L, "token", progress, movies -> { });

        MovieData film777 = result.stream()
                .filter(m -> m.getKpId() != null && m.getKpId().equals(777L))
                .findFirst().orElseThrow();
        assertEquals(8, film777.getKpRating(),
                     "kpRating=0 из Selenium не должен затирать валидную оценку 8 из дампа");
    }

    // ---- (д) Selenium-фаза НЕ вызывает onBatch до merge ----

    @Test
    void seleniumPhase_doesNotCallOnBatch_beforeMerge() {
        MovieData movie = new MovieData();
        movie.setKpId(111L);
        movie.setName("Test");
        movie.setYear(2022);
        movie.setKpRating(7);
        movie.setNameOriginal(null); // дефицитный → needsEnrichment

        KpNotesImporter mockImporter = mockNotesImporter(List.of(movie));

        ImportProgress progress = new ImportProgress();
        AtomicInteger callCount = new AtomicInteger(0);

        List<MovieData> result = provider(mockImporter, mock(ApiFilmCardEnricher.class))
                .fetchRatings(7L, "token", progress, movies -> callCount.incrementAndGet());

        // (1) getNotes вызван с реальным драйвером, progress, и onBatch=null (Selenium-фаза не пишет).
        verify(mockImporter).getNotes(any(WebDriver.class), eq(7L), any(), argThat(Objects::isNull));
        // (2) onBatch вызван хотя бы 1 раз (после merge или в обогащении).
        assertTrue(callCount.get() >= 1,
                   "onBatch должен быть вызван хотя бы 1 раз (после merge или в обогащении)");
        assertEquals(1, result.size());
    }

    // ---- Major 2: onBatch вызывается в catch перед throw ----

    @Test
    void quotaExhausted_onBatchCalledBeforeThrow() {
        // Записываем 100 дамповых фильмов (все с imdbId + nameOriginal → не need enrichment,
        // но они всё равно в merged, и все 3 selenium-фильма — new → need enrichment.
        String[] dumpRecords = new String[100];
        for (int i = 1; i <= 100; i++) {
            dumpRecords[i - 1] = "Film " + i + ";Title " + i + ";EN " + i + ";2020;8;"
                    + (1000 + i) + ";tt" + (1000 + i) + ";RATED;";
        }
        writeDump(dumpRecords);

        // Mock enricher бросает квоту на любом фильме.
        ApiFilmCardEnricher mockEnricher = mock(ApiFilmCardEnricher.class);
        doThrow(new KpQuotaExhaustedException("Квота исчерпана"))
                .when(mockEnricher).enrich(any(MovieData.class), eq("token"), any());

        // Selenium возвращает 3 новых фильма (без nameOriginal → need enrichment).
        List<MovieData> seleniumMovies = List.of(
                makeMovie(1101L, "Film 101", null),
                makeMovie(1102L, "Film 102", null),
                makeMovie(1103L, "Film 103", null)
        );

        ImportProgress progress = new ImportProgress();
        List<List<MovieData>> batches = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        try {
            provider(mockNotesImporter(seleniumMovies), mockEnricher)
                    .fetchRatings(7L, "token", progress, movies -> {
                        callCount.incrementAndGet();
                        batches.add(new ArrayList<>(movies));
                    });
        } catch (KpQuotaExhaustedException expected) {
            // Ожидаемое исключение.
        }

        // При квоте: вызов после merge (103) + вызов в catch (103) = 2+ вызова, 103 в последнем.
        assertTrue(callCount.get() >= 2,
                    "onBatch вызывается >=2 раз: после merge и в catch");
        List<MovieData> lastBatch = batches.get(batches.size() - 1);
        int lastBatchCount = (int) lastBatch.stream()
                .mapToInt(m -> m.getKpId() != null ? 1 : 0)
                .sum();
        assertEquals(103, lastBatchCount,
                     "последний onBatch (catch) должен содержать все фильмы: 100 дамп + 3 Selenium, "
                     + "фактически: " + lastBatchCount);
        int totalUnique = (int) lastBatch.stream()
                .mapToInt(m -> m.getKpId() != null ? 1 : 0)
                .sum();
        assertEquals(103, totalUnique,
                     "в последнем batch все 103 фильма (100 дамп + 3 Selenium)");
    }

    private static MovieData makeMovie(Long kpId, String name, String nameOriginal) {
        MovieData m = new MovieData();
        m.setKpId(kpId);
        m.setName(name);
        m.setNameOriginal(nameOriginal);
        m.setYear(2020);
        m.setKpRating(8);
        return m;
    }
}