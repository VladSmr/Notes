package ru.importer.notes.kp;

import ru.importer.notes.util.ThreadSleepUtil;

/**
 * Единый rate-limiter для всех API-вызовов Кинопоиска (страницы оценок, обогащение карточек).
 * Действует на ВСЕ запросы: не чаще 2 запросов в секунду — ниже лимита эндпоинта
 * и квоты тарифа. Thread-safe: каждый вызов {@link #waitIfNeeded()} синхронизирован.
 */
public final class ApiRateLimiter {

    /** Минимальный интервал: не чаще 2 запросов в секунду. */
    private static final long MIN_INTERVAL_NANOS = 600_000_000L;
    /** Время последнего запроса (nanos). */
    private static long lastRequestNanos = 0L;

    private ApiRateLimiter() {}

    /**
     * Ждёт, если с предыдущего запроса прошло менее 0.6 с (не чаще 2 req/sec).
     * Вызывать перед каждым HTTP-запросом к API Кинопоиска.
     */
    public static synchronized void waitIfNeeded() {
        long now = System.nanoTime();
        if (lastRequestNanos != 0L) {
            long elapsed = now - lastRequestNanos;
            if (elapsed < MIN_INTERVAL_NANOS) {
                long sleepMs = (MIN_INTERVAL_NANOS - elapsed) / 1_000_000L + 1;
                ThreadSleepUtil.sleepUninterruptibly(sleepMs);
            }
        }
        lastRequestNanos = System.nanoTime();
    }
}