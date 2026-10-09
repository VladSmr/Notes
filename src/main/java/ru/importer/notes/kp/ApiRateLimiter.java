package ru.importer.notes.kp;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import ru.importer.notes.util.ThreadSleepUtil;

/**
 * Rate-limiter для API-вызовов Кинопоиска. Два профиля:
 * <ul>
 *   <li><b>votes</b> — эндпоинт {@code /v1/kp_users/.../votes}: лимит 2 rps → 600 мс</li>
 *   <li><b>films</b> — эндпоинт {@code /v2.2/films/{id}}: глобальный тариф ~25 rps → 50 мс (запас)</li>
 * </ul>
 * Thread-safe: синхронизация через {@code synchronized} на экземпляре.
 */
@Component
public class ApiRateLimiter {

    /** Профиль «votes» — медленный (2 rps), для {@code /v1/kp_users/...}. */
    public static final String PROFILE_VOTES = "votes";
    /** Профиль «films» — быстрый (20 rps), для {@code /v2.2/films/{id}}. */
    public static final String PROFILE_FILMS = "films";

    private final long votesIntervalNanos;
    private final long filmsIntervalNanos;
    private final AtomicLong lastVotesNanos = new AtomicLong(0L);
    private final AtomicLong lastFilmsNanos = new AtomicLong(0L);

    /** Production-конструктор: стандартные интервалы. */
    public ApiRateLimiter() {
        this(600_000_000L, 50_000_000L);
    }

    /**
     * Конструктор для тестов: подменяет интервалы (мок времени).
     *
     * @param votesIntervalNanos интервал votes в наносекундах
     * @param filmsIntervalNanos интервал films в наносекундах
     */
    public ApiRateLimiter(long votesIntervalNanos, long filmsIntervalNanos) {
        this.votesIntervalNanos = votesIntervalNanos;
        this.filmsIntervalNanos = filmsIntervalNanos;
    }

    /**
     * Ждёт, если с предыдущего запроса того же профиля прошло менее интервала.
     *
     * @param profile {@link #PROFILE_VOTES} или {@link #PROFILE_FILMS}
     */
    public synchronized void waitIfNeeded(String profile) {
        long interval = PROFILE_FILMS.equals(profile) ? filmsIntervalNanos : votesIntervalNanos;
        AtomicLong lastNanos = PROFILE_FILMS.equals(profile) ? lastFilmsNanos : lastVotesNanos;

        long now = System.nanoTime();
        long prev = lastNanos.get();
        if (prev != 0L) {
            long elapsed = now - prev;
            if (elapsed < interval) {
                long sleepMs = (interval - elapsed) / 1_000_000L + 1;
                ThreadSleepUtil.sleepUninterruptibly(sleepMs);
                now = System.nanoTime(); // обновить после sleep
            }
        }
        lastNanos.set(now);
    }

    /** Сброс состояния — для изоляции тестов (вызывать в @BeforeEach). */
    public void reset() {
        lastVotesNanos.set(0L);
        lastFilmsNanos.set(0L);
    }
}