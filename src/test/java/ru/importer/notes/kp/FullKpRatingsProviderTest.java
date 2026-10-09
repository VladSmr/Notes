package ru.importer.notes.kp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.log.LogFileService;
import ru.importer.notes.movie.ImportProgress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты {@link FullKpRatingsProvider}: Selenium → Merge → API-enrichment.
 * Успешные дамп-тесты проверяют delta-logic: новые kpId обогащаются,
 * дефицитные обогащаются, полностью заполненные — нет.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FullKpRatingsProviderTest {

    @Mock
    private AuthManager authManager;
    @Mock
    private KpNotesImporter notesImporter;
    @Mock
    private ApiFilmCardEnricher enricher;
    @Mock
    private LogFileService logFileService;
    @Mock
    private Consumer<List<MovieData>> onBatch;

    private FullKpRatingsProvider provider;

    @BeforeEach
    void setUp() {
        // Selenium-фаза требует реальный драйвер.
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));
        provider = new FullKpRatingsProvider(authManager, notesImporter, enricher, logFileService);
    }

    // -------------------------------------------------------------------------
    // Фазы A→B
    // -------------------------------------------------------------------------

    @Test
    void fetchRatings_runsSeleniumThenEnrich() {
        when(logFileService.readKpDump()).thenReturn(null);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(movie(1L, "Film 1", 2010))));
        doNothing().when(enricher).enrich(any(), anyString(), any());

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        verify(notesImporter).getNotes(any(), eq(1L), any(), any());
        verify(enricher).enrich(any(), eq("token"), any());
        assertEquals(1, result.size());
        verify(onBatch, atLeast(1)).accept(any());
    }

    // -------------------------------------------------------------------------
    // Resume-дельта
    // -------------------------------------------------------------------------

    /**
     * Дамп 3 фильма (все поля заполнены = НЕ дефицитные). Selenium вернул 3 старых + 1 новый.
     * Обогащается только новый kpId=101. Дамп остаётся 4.
     */
    @Test
    void fetchRatings_dump3Selenium3plus1_enrichesOnlyNew() {
        // Дамп: 3 фильма (kpId 1,2,3), все поля заполнены.
        List<String[]> dumpRows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            dumpRows.add(new String[]{
                    "Film " + i, "Orig " + i, "En " + i,
                    String.valueOf(2010 + i), "8", String.valueOf(i), "tt" + i, "успешно", ""});
        }

        when(logFileService.readKpDump()).thenReturn(dumpRows);
        // Selenium: те же kpId 1,2,3 + новый kpId=101.
        List<MovieData> seleniumMovies = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            seleniumMovies.add(movie((long) i, "Film " + i, 2010 + i));
        }
        seleniumMovies.add(movie(101L, "New Film", 2020));

        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(seleniumMovies));
        doNothing().when(enricher).enrich(any(), anyString(), any());

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(4, result.size());
        // Только kpId=101 — новый и с null-полями — требует обогащения.
        verify(enricher, times(1)).enrich(any(), anyString(), any());
    }

    /**
     * Проверяет mergeDump: для существующих kpId данные из dump сохраняются
     * (копируются в новые объекты), kpRating берётся из fresh если изменился.
     * Новые kpId добавляются как fresh-объекты.
     */
    @Test
    void mergeDump_preservesDumpDataForExistingKpIds() {
        // Dump: 3 фильма с полными данными.
        Map<Long, MovieData> dump = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("DumpFilm " + i);
            m.setNameOriginal("DumpOrig " + i);
            m.setNameEn("DumpEn " + i);
            m.setYear(2010 + i);
            m.setKpRating(7);
            m.setImdbId("tt" + i);
            m.setKpUrl("https://www.kinopoisk.ru/film/" + i + "/");
            dump.put((long) i, m);
        }

        // Selenium: те же kpId 1-3 (пустые nameOriginal/imdbId) + новый kpId=101.
        List<MovieData> fresh = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("SelFilm " + i);
            m.setYear(2010 + i);
            m.setKpRating(8); // изменился
            // nameOriginal/imdbId — пустые (Selenium не заполняет)
            fresh.add(m);
        }
        fresh.add(movie(101L, "New Film", 2020));

        List<MovieData> merged = provider.mergeDump(dump, fresh);

        // Результат: 4 фильма.
        assertEquals(4, merged.size());

        // kpId=1: данные из dump, kpRating из fresh (8 ≠ 7 → обновлён).
        MovieData m1 = merged.get(0);
        assertEquals(1L, m1.getKpId());
        assertEquals("DumpOrig 1", m1.getNameOriginal()); // dump wins
        assertEquals("DumpEn 1", m1.getNameEn());
        assertEquals("tt1", m1.getImdbId());             // dump wins
        assertEquals(8, m1.getKpRating());               // fresh (актуальный)
        assertEquals("DumpFilm 1", m1.getName());

        // kpId=2 и kpId=3 аналогично.
        assertEquals("DumpOrig 2", merged.get(1).getNameOriginal());
        assertEquals("tt2", merged.get(1).getImdbId());
        assertEquals("DumpOrig 3", merged.get(2).getNameOriginal());
        assertEquals("tt3", merged.get(2).getImdbId());

        // kpId=101: новый из fresh.
        assertEquals(101L, merged.get(3).getKpId());
        assertEquals("New Film", merged.get(3).getName());

        // Сколько требуют обогащения?
        long needEnrichment = merged.stream()
                .filter(m -> isBlank(m.getNameOriginal()) && isBlank(m.getNameEn())
                        || isBlank(m.getImdbId()) || !KpYearValidator.isValidYear(m.getYear()))
                .count();
        // Только kpId=101 (пустые поля, новый kpId).
        assertEquals(1, needEnrichment, "только kpId=101 требует обогащения, было: " + needEnrichment);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Дамп: фильм без imdbId и без nameOriginal (дефицит). Требует обогащения.
     */
    @Test
    void fetchRatings_deficitFilmIsEnriched() {
        // CSV-строка с null-полями (имитирует реальный дамп после Selenium, до обогащения).
        List<String[]> dumpRows = Collections.singletonList(
                new String[]{"Film 1", "", "", "2010", "8", "1", "", "успешно", ""}
        );
        when(logFileService.readKpDump()).thenReturn(dumpRows);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(movie(1L, "Film 1", 2010))));
        doNothing().when(enricher).enrich(any(), anyString(), any());

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(1, result.size());
        verify(enricher, times(1)).enrich(any(), anyString(), any());
    }

    /**
     * Дамп: фильм полностью обогащён. Selenium возвращает тот же kpId.
     * Обогащение не требуется.
     */
    @Test
    void fetchRatings_alreadyEnriched_notEnrichedAgain() {
        List<String[]> dumpRows = Collections.singletonList(
                new String[]{"Film 1", "Original 1", "En 1", "2010", "8", "1", "tt1", "успешно", ""}
        );
        when(logFileService.readKpDump()).thenReturn(dumpRows);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(movie(1L, "Film 1", 2010))));

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(1, result.size());
        verify(enricher, never()).enrich(any(), anyString(), any());
    }

    /**
     * Дамп: kpRating=7. Selenium: kpRating=8 (пользователь переоценил).
     * kpRating обновляется; обогащение не нужно (всё заполнено).
     */
    @Test
    void fetchRatings_kpRatingUpdated_whenChanged() {
        List<String[]> dumpRows = Collections.singletonList(
                new String[]{"Film 1", "Orig 1", "En 1", "2010", "7", "1", "tt1", "успешно", ""}
        );
        when(logFileService.readKpDump()).thenReturn(dumpRows);
        MovieData freshMovie = movie(1L, "Film 1", 2010);
        freshMovie.setKpRating(8);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(freshMovie)));

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(8, result.get(0).getKpRating());
        verify(enricher, never()).enrich(any(), anyString(), any());
    }

    /**
     * Дамп: 2 фильма. Selenium: только 1 (второй пользователь удалил с КП).
     * Удалённый из дампа НЕ удаляется — остаётся.
     */
    @Test
    void fetchRatings_deletedInSelenium_keptInDump() {
        List<String[]> dumpRows = Arrays.asList(
                new String[]{"Film 1", "Orig 1", "En 1", "2010", "8", "1", "tt1", "успешно", ""},
                new String[]{"Film 2", "Orig 2", "En 2", "2011", "9", "2", "tt2", "успешно", ""}
        );
        when(logFileService.readKpDump()).thenReturn(dumpRows);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(movie(1L, "Film 1", 2010))));

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getKpId());
        assertEquals(2L, result.get(1).getKpId());
    }

    // -------------------------------------------------------------------------
    // Abort
    // -------------------------------------------------------------------------

    @Test
    void fetchRatings_abortStopsEnrichment() {
        ImportProgress progress = new ImportProgress();
        progress.init(5);

        when(logFileService.readKpDump()).thenReturn(null);
        List<MovieData> seleniumMovies = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            seleniumMovies.add(movie((long) i, "Film " + i, 2010 + i));
        }
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(seleniumMovies));

        Thread t = new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            progress.abort();
        });
        t.start();

        List<MovieData> result = provider.fetchRatings(1L, "token", progress, onBatch);

        try { t.join(3000); } catch (InterruptedException ignored) {}

        assertTrue(progress.isAborted());
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // onBatch
    // -------------------------------------------------------------------------

    @Test
    void fetchRatings_onBatchCalled_afterSeleniumAndAfterEnrich() {
        when(logFileService.readKpDump()).thenReturn(null);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(movie(1L, "Film 1", 2010))));

        provider.fetchRatings(1L, "token", null, onBatch);

        verify(onBatch, atLeast(2)).accept(any());
    }

    // -------------------------------------------------------------------------
    // Дедуп между страницами
    // -------------------------------------------------------------------------

    @Test
    void fetchRatings_dedupBetweenPages() {
        // notesImporter возвращает дублирующиеся kpId.
        List<MovieData> withDupes = new ArrayList<>();
        withDupes.add(movie(1L, "Film 1", 2010));
        withDupes.add(movie(1L, "Film 1", 2010));
        withDupes.add(movie(2L, "Film 2", 2011));

        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(withDupes));
        when(logFileService.readKpDump()).thenReturn(null);

        List<MovieData> result = provider.fetchRatings(1L, "token", null, onBatch);

        assertEquals(2, result.size());
    }

    // -------------------------------------------------------------------------
    // KpQuotaExhaustedException
    // -------------------------------------------------------------------------

    @Test
    void fetchRatings_quotaExhausted_savesPartialDump() {
        when(logFileService.readKpDump()).thenReturn(null);
        when(notesImporter.getNotes(any(), eq(1L), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(
                        movie(1L, "Film 1", 2010),
                        movie(2L, "Film 2", 2011)
                )));
        doThrow(new KpQuotaExhaustedException("Квота исчерпана"))
                .when(enricher).enrich(any(), anyString(), any());

        assertThrows(KpQuotaExhaustedException.class,
                () -> provider.fetchRatings(1L, "token", null, onBatch));

        // Частичный дамп сохранён (последний вызов onBatch).
        verify(onBatch, atLeast(1)).accept(any());
    }

    // -------------------------------------------------------------------------
    // Помощь
    // -------------------------------------------------------------------------

    private static MovieData movie(long kpId, String name, int year) {
        MovieData m = new MovieData();
        m.setKpId(kpId);
        m.setName(name);
        m.setYear(year);
        m.setKpRating(8);
        return m;
    }
}