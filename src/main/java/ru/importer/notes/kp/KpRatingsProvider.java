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
     * Возвращает фильмы с оценками пользователя.
     *
     * @param userId    идентификатор пользователя КП
     * @param apiToken  API-токен (для API-провайдера обязателен, Selenium игнорирует)
     * @param progress  прогресс текущего этапа (парсинг или проставление)
     *
     * @return список фильмов с оценками
     */
    List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress);

    /**
     * Возвращает фильмы с оценками пользователя, уведомляя о накопленных порциях.
     * <p>
     * Колбэк {@code onBatch} вызывается по мере наполнения списка (например, каждые N
     * фильмов), чтобы вызывающий мог периодически сохранять промежуточный дамп. Это
     * позволяет не терять собранные данные, если в самом конце запись упадёт.
     * <p>
     * Базовая реализация просто вызывает {@link #fetchRatings(Long, String, ImportProgress)}
     * без уведомлений — так сохраняется обратная совместимость для провайдеров, которым
     * периодическая запись не нужна (например, {@code saved} на этапе «Проставление»).
     *
     * @param userId    идентификатор пользователя КП
     * @param apiToken  API-токен (для API-провайдера обязателен, Selenium игнорирует)
     * @param progress  прогресс текущего этапа (парсинг или проставление)
     * @param onBatch   колбэк «накоплено N фильмов» (может быть null)
     *
     * @return список фильмов с оценками
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

}
