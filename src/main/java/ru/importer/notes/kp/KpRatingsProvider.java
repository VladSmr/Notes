package ru.importer.notes.kp;

import java.util.List;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

/** Способ получения оценок пользователя с Кинопоиска. */
public interface KpRatingsProvider {

    /** Ключ способа: используется в форме и при выборе провайдера. */
    String getKey();

    /**
     * Возвращает фильмы с оценками пользователя.
     *
     * @param userId    идентификатор пользователя КП
     * @param apiToken  API-токен (для API-провайдера обязателен, Selenium игнорирует)
     * @param progress  прогресс импорта
     *
     * @return список фильмов с оценками
     */
    List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress);

    /**
     * Возвращает общее количество оценок пользователя на Кинопоиске.
     * Selenium читает счётчик в футере страницы оценок, API — поле {@code total} из ответа.
     *
     * @return количество оценок или null, если определить не удалось
     */
    Integer fetchTotalRatings(Long userId, String apiToken);

}
