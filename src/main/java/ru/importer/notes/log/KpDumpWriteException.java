package ru.importer.notes.log;

/**
 * Unchecked-исключение записи дампа оценок КП (kp-ratings-*.csv) с сохранённым {@code cause},
 * чтобы вызывающий мог классифицировать ошибку («файл занят» / «нет прав на запись»).
 */
public class KpDumpWriteException extends RuntimeException {

    public KpDumpWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
