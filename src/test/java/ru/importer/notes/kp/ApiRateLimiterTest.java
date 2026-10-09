package ru.importer.notes.kp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты разделённого rate-limiter: два профиля — votes (600 мс) и films (50 мс).
 * Каждый тест работает со свежим экземпляром ({@link #setUp}) — изоляция гарантирована.
 */
class ApiRateLimiterTest {

    private ApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ApiRateLimiter();
    }

    @Test
    void votesProfile_shouldWaitOnSecondCall() {
        // 1-й вызов votes: lastVotesNanos=0 → без wait
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);
        // 2-й вызов votes: ждём ≥580 мс
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 580, "votes: ждал ≥580 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void filmsProfile_shouldWaitOnSecondCall() {
        // 1-й вызов films: lastFilmsNanos=0 → без wait
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);
        // 2-й вызов films: ждём ≥40 мс
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 40, "films: ждал ≥40 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void films_shouldNotAddDelayAfterVotes() {
        // votes ждёт ~600 мс, films вызванный сразу после — ~0 мс.
        // Общее время ≈ 600 мс, а не 650 мс (films не добавил задержку).
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // +600 мс
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // ~0 мс
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(totalMs < 700,
                "films сразу после votes: ждал ≈600 мс, total=" + totalMs + "мс");
    }

    @Test
    void consecutiveFilmsCalls_shouldRespect50msInterval() {
        // 1-й films: без wait
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);
        // 2-й films: ждём ≥40 мс
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 40, "films→films: ждал ≥40 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void customIntervals_areRespected() {
        // Подменяем интервалы через конструктор (для будущих тестов/моков).
        // votes=200мс, films=10мс.
        ApiRateLimiter fast = new ApiRateLimiter(200_000_000L, 10_000_000L);

        fast.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // 1-й
        long start = System.nanoTime();
        fast.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // 2-й: ≥200 мс
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 180, "custom votes: ждал ≥180 мс, elapsed=" + elapsedMs + "мс");

        fast.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // 1-й
        start = System.nanoTime();
        fast.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // 2-й: ≥10 мс
        elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 8, "custom films: ждал ≥8 мс, elapsed=" + elapsedMs + "мс");
    }
}