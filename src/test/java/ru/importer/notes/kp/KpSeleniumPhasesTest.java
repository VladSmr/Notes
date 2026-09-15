package ru.importer.notes.kp;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.imdb.auth.AuthManager;
import ru.importer.notes.movie.ImportProgress;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeleniumKpRatingsProvider: знаменатель фазы 2 = фактическое число missing;
 * валидация годов — третья фаза после original titles.
 */
class KpSeleniumPhasesTest extends KpTestSupport {

    /**
     * Selenium-парсинг: знаменатель пересчитывается после фазы 1 под фактическое
     * число missing, чтобы current достигал total ровно в конце (бар 100%).
     */
    @Test
    void seleniumProvider_barReaches100PercentWhenMissingLessThanN() {
        int n = 100;
        int pagesCeil = (n + 19) / 20; // ceil(100/20) = 5
        int missing = 20; // у 80 фильмов nameEn уже заполнен при парсинге

        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            m.setYear(2000);
            if (i > missing) {
                m.setNameEn("En " + i);
            }
            movies.add(m);
        }

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int pg = 0; pg < pagesCeil; pg++) {
                p.advance(ImportProgress.PHASE_KP, "Страница " + (pg + 1), "kp");
            }
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());

        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int i = 0; i < missing; i++) {
                p.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
            }
            return null;
        }).when(notesImporter).fetchOriginalTitles(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));

        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil); // предварительный знаменатель фазы 1 (как в Processor)

        provider.fetchRatings(1L, null, progress);

        // Знаменатель пересчитан: pagesScanned(5) + missing(20) = 25.
        assertEquals(pagesCeil + missing, progress.getTotal());
        // current достиг total ровно в конце (бар 100%), не превышая его.
        assertEquals(progress.getTotal(), progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    /**
     * Missing-фильм без URL пропускается без advance — в знаменатель не попадает,
     * иначе current застрянет ниже total.
     */
    @Test
    void seleniumProvider_missingWithoutUrlExcludedFromDenominator() {
        int pagesCeil = 2;
        int missingWithUrl = 3;
        int missingWithoutUrl = 2;

        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            m.setYear(2000); // годы валидные — фаза 3 не нужна
            m.setNameEn("En " + i);
            movies.add(m);
        }
        // Помечаем 5 фильмов как missing; у 2 из них убираем и kpId, и kpUrl.
        for (int i = 0; i < missingWithUrl + missingWithoutUrl; i++) {
            movies.get(i).setNameEn(null);
        }
        movies.get(0).setKpId(null);
        movies.get(0).setKpUrl(null);
        movies.get(1).setKpId(null);
        movies.get(1).setKpUrl(null);

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int pg = 0; pg < pagesCeil; pg++) {
                p.advance(ImportProgress.PHASE_KP, "Страница " + (pg + 1), "kp");
            }
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());

        // Фаза 2 обрабатывает только missing-фильмы с URL.
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            for (int i = 0; i < missingWithUrl; i++) {
                p.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
            }
            return null;
        }).when(notesImporter).fetchOriginalTitles(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));

        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil);

        provider.fetchRatings(1L, null, progress);

        // Знаменатель = pagesScanned(2) + missingWithUrl(3) = 5 (без учёта missing без URL).
        assertEquals(pagesCeil + missingWithUrl, progress.getTotal());
        assertEquals(progress.getTotal(), progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void seleniumProvider_fixesInvalidYearsAfterOriginalTitlesAndCountsInDenominator() {
        // Фаза 2 чинит missing-фильму и название, и год, поэтому после неё невалидных
        // годов 2: total = страницы(1) + missing(1) + invalid(2) = 4.
        MovieData valid = movieWith(1L, "Valid", "En 1", 1999);
        MovieData invalidWithEn1 = movieWith(2L, "Inv 1", "En 2", 0);
        MovieData invalidWithEn2 = movieWith(3L, "Inv 2", "En 3", 0);
        MovieData missingTitle = movieWith(4L, "Missing", null, 0);
        List<MovieData> movies = new ArrayList<>(List.of(valid, invalidWithEn1, invalidWithEn2, missingTitle));

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            p.advance(ImportProgress.PHASE_KP, "Страница 1", "kp");
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            // Фаза 2 дозаполняет missing-фильму и оригинальное название, и год.
            ((List<MovieData>) inv.getArgument(0)).get(3).setNameEn("En 4");
            ((List<MovieData>) inv.getArgument(0)).get(3).setYear(2015);
            p.advance(ImportProgress.PHASE_KP, "Missing", "orig");
            return null;
        }).when(notesImporter).fetchOriginalTitles(any(), any(), any(), any());
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            p.advance(ImportProgress.PHASE_KP, "Inv 1", "year");
            p.advance(ImportProgress.PHASE_KP, "Inv 2", "year");
            return null;
        }).when(notesImporter).fixInvalidYears(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));
        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(1);

        provider.fetchRatings(1L, null, progress);

        // Знаменатель пересчитан по факту после фазы 2; current достигает total в конце.
        assertEquals(4, progress.getTotal());
        assertEquals(4, progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());

        // Порядок фаз: парсинг -> оригинальные названия -> валидация годов.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(notesImporter);
        inOrder.verify(notesImporter).getNotes(any(), any(), any(), any());
        inOrder.verify(notesImporter).fetchOriginalTitles(any(), any(), any(), any());
        inOrder.verify(notesImporter).fixInvalidYears(any(), any(), any(), any());
    }

    @Test
    void seleniumProvider_skipsYearFixWhenAbortedAfterTitles() {
        List<MovieData> movies = new ArrayList<>();
        movies.add(movieWith(1L, "Valid", "En 1", 1999));

        KpNotesImporter notesImporter = mock(KpNotesImporter.class);
        doAnswer(inv -> {
            ImportProgress p = inv.getArgument(2);
            p.advance(ImportProgress.PHASE_KP, "Страница 1", "kp");
            p.abort();
            return movies;
        }).when(notesImporter).getNotes(any(), any(), any(), any());

        AuthManager authManager = mock(AuthManager.class);
        when(authManager.getDriver()).thenReturn(mock(WebDriver.class));
        SeleniumKpRatingsProvider provider = new SeleniumKpRatingsProvider(authManager, notesImporter);

        ImportProgress progress = new ImportProgress();
        progress.init(1);
        provider.fetchRatings(1L, null, progress);

        // После остановки пользователем фаза валидации годов не запускается.
        verify(notesImporter, never()).fixInvalidYears(any(), any(), any(), any());
    }
}
