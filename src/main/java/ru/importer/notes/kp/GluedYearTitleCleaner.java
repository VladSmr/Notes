package ru.importer.notes.kp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Зачистка названия от хвоста, склеенного с годом: «Мортал Комбат 22025» → «Мортал Комбат 2».
 * Валидность года — только через {@link KpYearValidator} (единый источник диапазона).
 */
final class GluedYearTitleCleaner {

    /**
     * Хвост названия, склеенный с годом без пробела: «Мортал Комбат 22025» → год 2025.
     * Ловит ПОСЛЕДНИЕ 4 цифры хвоста, если они — валидный год; иначе первые 4 цифры
     * хвоста из ≥5 цифр ({@link #LONG_DIGIT_TAIL}). Без \b: хвост приклеен к цифрам названия.
     */
    private static final Pattern GLUED_YEAR_TAIL =
            Pattern.compile("\\s*" + KpYearValidator.YEAR_RANGE_REGEX + "(-\\d{2})?\\s*$");
    /**
     * Запасной вариант хвоста-склейки из ≥5 цифр, когда последние 4 цифры — НЕ валидный
     * год (иначе сработал бы {@link #GLUED_YEAR_TAIL}): берём ПЕРВЫЕ 4 цифры хвоста
     * как год, название — без всего N-цифрового хвоста.
     */
    private static final Pattern LONG_DIGIT_TAIL =
            Pattern.compile("\\s*(\\d{5,})\\s*$");

    private GluedYearTitleCleaner() {
    }

    /**
     * Год, склеенный с концом названия без пробела: «Мортал Комбат 22025» → 2025,
     * «Сериал 2021-22» → 2021; нет хвоста-года — 0. Хвост из ≥5 цифр: сначала
     * последние 4 цифры хвоста, если не год — первые 4 цифры хвоста.
     */
    static int extractTrailingGluedYear(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        Matcher m = GLUED_YEAR_TAIL.matcher(name);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return firstFourDigitsTailYear(name);
    }

    /**
     * Запасной год из цифрового хвоста ≥5: первые 4 цифры, если это валидный год; иначе 0.
     */
    private static int firstFourDigitsTailYear(String name) {
        Matcher d = LONG_DIGIT_TAIL.matcher(name);
        if (!d.find()) {
            return 0;
        }
        try {
            int y = Integer.parseInt(d.group(1).substring(0, 4));
            if (KpYearValidator.isValidYear(y)) {
                return y;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /**
     * Отрезает от названия хвост, склеенный с годом: «Мортал Комбат 22025» → «Мортал Комбат 2».
     * Правила безопасности: при {@code knownYear > 0} хвост отрезается только при совпадении
     * с ним (иначе цифры могли быть частью названия — «Космическая одиссея 2001»); хвост
     * из ≥5 цифр — сначала последние 4 цифры как год, иначе первые 4; если после отрезания
     * пусто — название не трогаем.
     */
    static String stripTrailingGluedYear(String name, int knownYear) {
        if (name == null || name.isBlank()) {
            return name;
        }
        Matcher m = GLUED_YEAR_TAIL.matcher(name);
        if (!m.find()) {
            // Последние 4 цифры цифрового хвоста (≥5) — не год: пробуем первые 4.
            int first4Year = firstFourDigitsTailYear(name);
            if (first4Year > 0 && (knownYear <= 0 || first4Year == knownYear)) {
                Matcher d = LONG_DIGIT_TAIL.matcher(name);
                if (d.find()) {
                    String stripped = name.substring(0, d.start()).trim();
                    if (!stripped.isEmpty()) {
                        return stripped;
                    }
                }
            }
            return name;
        }
        int tailYear;
        try {
            tailYear = Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
            return name;
        }
        if (knownYear > 0 && tailYear != knownYear) {
            // Цифры в конце не совпадают с известным годом — вероятно, часть названия.
            return name;
        }
        String stripped = name.substring(0, m.start()).trim();
        return stripped.isEmpty() ? name : stripped;
    }

}
