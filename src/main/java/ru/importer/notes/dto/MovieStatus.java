package ru.importer.notes.dto;

import lombok.Getter;

/**
 * Статус обработки фильма на этапе парсинга/проставления.
 */
@Getter
public enum MovieStatus {

    PENDING,
    NOT_FOUND,
    SKIPPED_SAME,
    SKIPPED_DIFFERENT,
    RATED,
    /** Оценка проставлена при неоднозначной выдаче поиска (несколько точных совпадений) — «проставлено с оговоркой». */
    RATED_AMBIGUOUS,
    ERROR,
    INCOMPLETE_DATA;

    public boolean isDone() {
        return this == RATED
                || this == RATED_AMBIGUOUS
                || this == SKIPPED_SAME
                || this == SKIPPED_DIFFERENT
                || this == INCOMPLETE_DATA;
    }
}
