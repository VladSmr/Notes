package ru.importer.notes.kp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты разделённого rate-limiter: два профиля — votes (600 мс) и films (50 мс).
 * Каждый тест работает со свежим экземпляром ({@link #setUp}) — изоляция гарантирована.
 * Per-profile локи: votes и films не сериализуют друг друга.
 */
class ApiRateLimiterTest {

    private ApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ApiRateLimiter();
    }

    @Test
    void votesProfile_shouldWaitOnSecondCall() {
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // 1-й: без wait
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // 2-й: ≥580 мс
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 580, "votes: ждал ≥580 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void filmsProfile_shouldWaitOnSecondCall() {
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // 1-й: без wait
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS); // 2-й: ≥40 мс
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 40, "films: ждал ≥40 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void films_shouldNotBlockByVotesAndViceVersa() {
        // Прогреваем votes до 2-го вызова → 600 мс.
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // films: без wait (1-й)
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);   // votes: 2-й → 600 мс

        // films сразу после прогретого votes — без дополнительной задержки (~0 мс).
        // Общее время ≈ 600 мс, не 650 мс.
        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // +600 мс
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // ~0 мс
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(totalMs < 700,
                "films после votes: total≈600 мс, total=" + totalMs + "мс");
    }

    @Test
    void consecutiveFilmsCalls_shouldRespect50msInterval() {
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // 1-й: без wait
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);   // сброс films через votes не нужен

        long start = System.nanoTime();
        limiter.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS); // 2-й: ≥40 мс
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 40, "films→films: ждал ≥40 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void customIntervals_areRespected() {
        // Подменяем интервалы: votes=200 мс, films=10 мс.
        ApiRateLimiter fast = new ApiRateLimiter(200_000_000L, 10_000_000L);

        fast.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);
        long start = System.nanoTime();
        fast.waitIfNeeded(ApiRateLimiter.PROFILE_VOTES);  // ≥200 мс
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 180, "custom votes: ждал ≥180 мс, elapsed=" + elapsedMs + "мс");

        fast.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);
        start = System.nanoTime();
        fast.waitIfNeeded(ApiRateLimiter.PROFILE_FILMS);  // ≥10 мс
        elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 8, "custom films: ждал ≥8 мс, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void unknownProfile_throwsIllegalArgumentException() {
        try {
            limiter.waitIfNeeded("unknown");
            assertTrue(false, "должно было выбросить IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}