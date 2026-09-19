package ru.importer.notes.kp;

import java.util.List;
import java.util.function.Consumer;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

/** Способ получения оценок пользователя с Кинопоиска. */
public interface KpRatingsProvider {

    /** Ключ способа: используется в форме и при выборе провайдера. */
    String getKey();

    /**
     * Фильмы с оценками пользователя.
     *
     * @param apiToken API-токен (для API-провайдера обязателен, Selenium игнорирует)
     */
    List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress);

    /**
     * Фильмы с оценками, уведомляя о накопленных порциях через {@code onBatch}
     * (например, каждые N фильмов) — для периодического сохранения промежуточного дампа.
     * Базовая реализация просто делегирует без уведомлений.
     *
     * @param apiToken  API-токен (для API-провайдера обязателен, Selenium игнорирует)
     * @param onBatch   колбэк «накоплено N фильмов» (может быть null)
     */
    default List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress,
                                         Consumer<List<MovieData>> onBatch) {
        return fetchRatings(userId, apiToken, progress);
    }

    /**
     * Возвращает общее количество оценок пользователя на Кинопоиске.
     * Selenium читает счётчик в футере страницы оценок, API — поле {@code total} из ответа.
     *
     * @return количество оценок или null, если определить не удалось
     */
    Integer fetchTotalRatings(Long userId, String apiToken);

    /**
     * Предварительная проверка доступа к источнику пробным запросом (первая страница
     * оценок). Вызывается перед стартом парсинга в способе API. Реализации без токена
     * (Selenium/CSV) ничего не делают.
     *
     * @param userId   id профиля КП
     * @param apiToken API-токен (для API-провайдера обязателен)
     * @throws org.springframework.web.client.RestClientResponseException при 401/403
     *         (невалидный/запрещённый токен) — ошибка не ретраится
     */
    default void validateToken(Long userId, String apiToken) {
    }

}
