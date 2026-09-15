package ru.importer.notes.kp;

import org.junit.jupiter.api.Test;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Схема единиц прогресса (знаменатель = ceil(N/20) + missing) и resetTotal
 * класса ImportProgress (сам класс — в пакете movie, тест временно в kp).
 */
class ImportProgressUnitsTest {

    @Test
    void progress_unitsScheme_apiUsesCeilPagesAsDenominator() {
        // API: знаменатель = ceil(N/20), каждая страница = 1 единица. N=101 → 6 страниц.
        int n = 101;
        int totalUnits = (n + 19) / 20; // ceil(N/20) = 6
        ImportProgress progress = new ImportProgress();
        progress.init(totalUnits);

        for (int p = 0; p < totalUnits; p++) {
            progress.advance(ImportProgress.PHASE_KP, "Страница " + (p + 1), "api");
        }
        assertEquals(totalUnits, progress.getCurrent());
        assertEquals(totalUnits, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_unitsScheme_phase2DenominatorMatchesActualMissing() {
        // Знаменатель фазы 2 = фактическое число missing-фильмов с URL, а не N.
        int n = 100;
        int pagesCeil = (n + 19) / 20; // ceil(100/20) = 5
        int missing = 20;

        ImportProgress progress = new ImportProgress();
        progress.init(pagesCeil); // предварительный знаменатель фазы 1

        // Фаза 1: по 1 единице за страницу.
        for (int p = 0; p < pagesCeil; p++) {
            progress.advance(ImportProgress.PHASE_KP, "Страница " + (p + 1), "kp");
        }
        assertEquals(pagesCeil, progress.getCurrent());

        // После фазы 1 знаменатель пересчитывается под фактическое число missing.
        int totalUnits = progress.getCurrent() + missing;
        progress.resetTotal(totalUnits);
        assertEquals(totalUnits, progress.getTotal());
        assertEquals(pagesCeil, progress.getCurrent(), "resetTotal не должен сбрасывать накопленное");

        for (int i = 0; i < missing; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
        }
        assertEquals(totalUnits, progress.getCurrent(), "current должен достигать total ровно в конце");
        assertEquals(totalUnits, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal(),
                "current не должен превышать total");

        // Лишние advance не должны перевалить current за total.
        progress.advance(ImportProgress.PHASE_KP, "лишний", "extra");
        assertEquals(totalUnits, progress.getCurrent());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_resetTotal_shouldClampCurrentWhenNewTotalSmaller() {
        ImportProgress progress = new ImportProgress();
        progress.init(10);
        for (int i = 0; i < 7; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фаза 1", "kp");
        }
        assertEquals(7, progress.getCurrent());

        progress.resetTotal(5); // новый знаменатель меньше накопленного current
        assertEquals(5, progress.getTotal());
        assertEquals(5, progress.getCurrent(), "current клампится до нового total");
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }

    @Test
    void progress_resetTotal_shouldKeepCurrentWhenNewTotalLarger() {
        ImportProgress progress = new ImportProgress();
        progress.init(3);
        progress.advance(ImportProgress.PHASE_KP, "Страница 1", "kp");
        progress.advance(ImportProgress.PHASE_KP, "Страница 2", "kp");
        assertEquals(2, progress.getCurrent());

        progress.resetTotal(2 + 4); // фаза 2 добавит 4 missing-фильма
        assertEquals(6, progress.getTotal());
        assertEquals(2, progress.getCurrent(), "накопленные единицы фазы 1 сохраняются");

        for (int i = 0; i < 4; i++) {
            progress.advance(ImportProgress.PHASE_KP, "Фильм " + i, "orig");
        }
        assertEquals(6, progress.getCurrent());
        assertEquals(6, progress.getTotal());
        assertTrue(progress.getCurrent() <= progress.getTotal());
    }
}
