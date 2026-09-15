package ru.importer.notes.kp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Границы диапазона года (1890–2050) и согласованность regex-фрагмента
 * YEAR_RANGE_REGEX с константами MIN/MAX (единый источник диапазона для парсеров).
 */
class KpYearValidatorTest {

    @Test
    void isValidYear_bounds() {
        assertTrue(KpYearValidator.isValidYear(1890), "MIN_YEAR валиден");
        assertTrue(KpYearValidator.isValidYear(1899), "1890-е — валидные фильмы");
        assertTrue(KpYearValidator.isValidYear(2022));
        assertTrue(KpYearValidator.isValidYear(2050), "год 2050 валиден (MAX_YEAR = 2050)");

        assertFalse(KpYearValidator.isValidYear(1889), "год 1889 ниже диапазона");
        assertFalse(KpYearValidator.isValidYear(2051), "год 2051 невалиден");
        assertFalse(KpYearValidator.isValidYear(2100), "2100 больше не валиден (MAX_YEAR снижен до 2050)");
        assertFalse(KpYearValidator.isValidYear(2101));
        assertFalse(KpYearValidator.isValidYear(0), "0 — не распарсившийся год");
        assertFalse(KpYearValidator.isValidYear(-5));
    }

    @Test
    void yearPattern_matchesExactlyValidatorRange() {
        // Regex-фрагмент должен распознавать РОВНО тот диапазон, что и isValidYear.
        for (int year = 1880; year <= 2110; year++) {
            boolean found = KpYearValidator.YEAR_PATTERN.matcher(String.valueOf(year)).find();
            assertEquals(KpYearValidator.isValidYear(year), found,
                    "regex и isValidYear расходятся на годе " + year);
        }
    }

    @Test
    void yearPattern_parsesYearFromText() {
        // Сценарии парсеров: строка-диапазон, склейка, текст без года.
        assertEquals("2012", firstMatch("2012-2014"));
        assertEquals("1997", firstMatch("1997"));
        assertEquals("2025", firstMatch("Мортал Комбат 22025"));
        assertEquals("", firstMatch("мусор без года"));
    }

    private static String firstMatch(String text) {
        var matcher = KpYearValidator.YEAR_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}
