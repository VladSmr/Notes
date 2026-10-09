package ru.importer.notes.kp;

import org.springframework.stereotype.Component;
import ru.importer.notes.util.ThreadSleepUtil;

/**
 * Rate-limiter для API-вызовов Кинопоиска. Два профиля:
 * <ul>
 *   <li><b>votes</b> — эндпоинт {@code /v1/kp_users/.../votes}: лимит 2 rps → 600 мс</li>
 *   <li><b>films</b> — эндпоинт {@code /v2.2/films/{id}}: глобальный тариф ~25 rps → 50 мс (запас)</li>
 * </ul>
 * Каждый профиль — свой объект-блокировки: votes и films не сериализуют друг друга.
 */
@Component
public class ApiRateLimiter {

    /** Профиль «votes» — медленный (2 rps), для {@code /v1/kp_users/...}. */
    public static final String PROFILE_VOTES = "votes";
    /** Профиль «films» — быстрый (20 rps), для {@code /v2.2/films/{id}}. */
    public static final String PROFILE_FILMS = "films";

    private final long votesIntervalNanos;
    private final long filmsIntervalNanos;

    /** Блокировки на профиль — независимая синхронизация: votes и films не блокируют друг друга. */
    private final Object votesLock = new Object();
    private final Object filmsLock = new Object();

    /** Метки времени последнего запроса (nanos, 0 = первый вызов). */
    private long lastVotesNanos = 0L;
    private long lastFilmsNanos = 0L;

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
     * Неизвестный профиль → {@link IllegalArgumentException}.
     *
     * @param profile {@link #PROFILE_VOTES} или {@link #PROFILE_FILMS}
     */
    public void waitIfNeeded(String profile) {
        if (!PROFILE_VOTES.equals(profile) && !PROFILE_FILMS.equals(profile)) {
            throw new IllegalArgumentException("Неизвестный профиль: " + profile
                    + ". Ожидается: " + PROFILE_VOTES + " или " + PROFILE_FILMS);
        }
        boolean films = PROFILE_FILMS.equals(profile);
        Object lock = films ? filmsLock : votesLock;
        long interval = films ? filmsIntervalNanos : votesIntervalNanos;
        synchronized (lock) {
            long now = System.nanoTime();
            long prev = films ? lastFilmsNanos : lastVotesNanos;
            if (prev != 0L) {
                long elapsed = now - prev;
                if (elapsed < interval) {
                    long sleepMs = (interval - elapsed) / 1_000_000L + 1;
                    ThreadSleepUtil.sleepUninterruptibly(sleepMs);
                    now = System.nanoTime(); // обновить после sleep
                }
            }
            if (films) {
                lastFilmsNanos = now;
            } else {
                lastVotesNanos = now;
            }
        }
    }
}