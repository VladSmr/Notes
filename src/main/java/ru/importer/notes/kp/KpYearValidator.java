package ru.importer.notes.kp;

import java.util.regex.Pattern;

/**
 * Валидация года выпуска фильма — ЕДИНЫЙ источник диапазона для всех этапов и парсеров.
 * Валидный год: 1890–2050 включительно; всё остальное (0 или мусор) — невалидно.
 * На этапе проставления (IMDB) невалидный год не блокер: поиск идёт по названию.
 */
public final class KpYearValidator {

    /** Нижняя граница валидного года (первые фильмы на КП — 1890-е). */
    public static final int MIN_YEAR = 1890;

    /** Верхняя граница валидного года (защита от мусора и будущих дат). */
    public static final int MAX_YEAR = 2050;

    /**
     * Regex-фрагмент года, соответствующий диапазону {@link #MIN_YEAR}–{@link #MAX_YEAR}.
     * Общий источник диапазона для всех парсеров; согласованность с MIN/MAX проверяется тестом.
     */
    public static final String YEAR_RANGE_REGEX = "(18[9]\\d|19\\d{2}|20[0-4]\\d|2050)";

    /** Скомпилированный фрагмент диапазона (для {@code find()} по тексту API/страниц). */
    public static final Pattern YEAR_PATTERN = Pattern.compile(YEAR_RANGE_REGEX);

    private KpYearValidator() {
    }

    /** Валиден ли год: 1890–2050 включительно (0 и мусор — невалидны). */
    public static boolean isValidYear(int year) {
        return year >= MIN_YEAR && year <= MAX_YEAR;
    }
}
