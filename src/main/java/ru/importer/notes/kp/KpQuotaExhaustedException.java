package ru.importer.notes.kp;

/**
 * Квота API Кинопоиска исчерпана: после 3 подряд 402-запросов обогащение прекращается.
 * Пробрасывается наверх — частичный дамп сохраняется вызывающей стороной.
 */
public class KpQuotaExhaustedException extends RuntimeException {

    public KpQuotaExhaustedException(String message) {
        super(message);
    }

    public KpQuotaExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}