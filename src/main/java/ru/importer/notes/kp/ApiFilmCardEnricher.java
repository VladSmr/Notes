package ru.importer.notes.kp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;
import ru.importer.notes.util.ThreadSleepUtil;
import ru.importer.notes.kp.ApiRateLimiter;

/**
 * Обогащение данных фильма через {@code GET /v2.2/films/{id}}: nameOriginal, nameEn,
 * год, imdbId, type/kpUrl. Переиспользует карточку из {@link ApiKpRatingsProvider}.
 */
@Slf4j
@Service
public class ApiFilmCardEnricher {

    /** Пауза между ретраями при 429 Too Many Requests. */
    private static final long BACKOFF_429_MS = 3000L;
    /** Пауза между ретраями при 5xx/сетевых ошибках. */
    private static final long BACKOFF_ERROR_MS = 1500L;
    /** Пауза при 402: после трёх подряд — кидается {@link KpQuotaExhaustedException}. */
    private static final long BACKOFF_402_MS = 5000L;
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public ApiFilmCardEnricher(ObjectMapper objectMapper) {
        this(objectMapper, RestClient.builder()
                                    .baseUrl("https://kinopoiskapiunofficial.tech/api")
                                    .build());
    }

    /** Для тестов: подменяет HTTP-слой моком RestClient. */
    ApiFilmCardEnricher(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    /**
     * Обогащает {@code movie} данными из {@code GET /v2.2/films/{id}}.
     * Merge-правило: «не затирать непустое пустым».
     * 402×3 кидает {@link KpQuotaExhaustedException}; парциальный дамп сохраняется
     * вызывающей стороной. 401/403 — фатально. 404 — warn, данные Selenium сохранены.
     *
     * @param progress безопасные точки паузы/остановки (может быть null)
     */
    public void enrich(MovieData movie, String apiToken, ImportProgress progress) {
        if (movie.getKpId() == null) {
            log.warn("Обогащение: kpId отсутствует, пропускаю");
            return;
        }

        if (progress != null) {
            progress.waitWhilePaused();
            if (progress.isAborted()) {
                return;
            }
        }

        JsonNode film = fetchFilm(movie.getKpId(), apiToken);

        // nameEn — заполнять только если пусто.
        String nameEn = blankToNull(film.path("nameEn").asText(null));
        if (movie.getNameEn() == null || movie.getNameEn().isBlank()) {
            movie.setNameEn(nameEn);
        }

        // nameOriginal — пуст → фолбэк на nameEn из карточки.
        String nameOriginalApi = blankToNull(film.path("nameOriginal").asText(null));
        if (movie.getNameOriginal() == null || movie.getNameOriginal().isBlank()) {
            // Фолбэк: API nameOriginal > movie.nameEn > API nameEn.
            String fallback = nameOriginalApi != null ? nameOriginalApi
                    : (movie.getNameEn() != null && !movie.getNameEn().isBlank() ? movie.getNameEn() : nameEn);
            movie.setNameOriginal(fallback);
        }

        // Год: API — истина. Валидный API-год заменяет Selenium-год; невалидный — оставляем.
        int apiYear = ApiKpRatingsProvider.extractFilmYear(film);
        if (KpYearValidator.isValidYear(apiYear)) {
            if (apiYear != movie.getYear()) {
                log.debug("Обогащение: год фильма '{}' (kpId={}) — API={}, Selenium={}",
                          movie.getName(), movie.getKpId(), apiYear, movie.getYear());
                movie.setYear(apiYear);
            }
        }

        // imdbId — заполнять только если пусто.
        String imdbId = blankToNull(film.path("imdbId").asText(null));
        if ((movie.getImdbId() == null || movie.getImdbId().isBlank()) && imdbId != null) {
            movie.setImdbId(imdbId);
        }

        // type → kpUrl.
        String type = film.path("type").asText("FILM");
        boolean isSeriesType = isSeriesType(type);
        movie.setKpUrl("https://www.kinopoisk.ru/" + (isSeriesType ? "series/" : "film/") + movie.getKpId() + "/");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * TV_SHOW, TV_SERIES, MINI_SERIES — серии.
     * {@code type.contains("SERIES")} покрывает все, кроме TV_SHOW.
     */
    static boolean isSeriesType(String type) {
        if (type == null) {
            return false;
        }
        return type.contains("SERIES") || type.equals("TV_SHOW");
    }

    /**
     * GET /v2.2/films/{id} — та же карточка, что и в {@link ApiKpRatingsProvider}.
     */
    JsonNode fetchFilm(Long filmId, String apiToken) {
        return fetchJsonWithRetry(apiToken, "фильм " + filmId,
                                  () -> restClient.get().uri("/v2.2/films/{id}", filmId));
    }

    /**
     * GET с ретраями: 429/5xx — ретрай, 401/403 — фатально, 404 — warn,
     * 402×3 — {@link KpQuotaExhaustedException}.
     */
    private JsonNode fetchJsonWithRetry(String apiToken, String label,
                                        java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request) {
        Exception last = null;
        int consecutive402 = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                rateLimit();
                String body = request.get()
                                     .header("X-API-KEY", apiToken)
                                     .header("Content-Type", "application/json")
                                     .retrieve()
                                     .body(String.class);
                try {
                    return objectMapper.readTree(body);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Не удалось разобрать ответ API Кинопоиска", e);
                }
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 402) {
                    consecutive402++;
                    log.warn("API: 402 (квиток исчерпан) для {} (попытка {}/{})",
                             label, attempt, MAX_ATTEMPTS);
                    if (consecutive402 >= 3) {
                        throw new KpQuotaExhaustedException(
                                "Квота API исчерпана: 3 последовательных ответа 402. "
                                + "Обогащение остановлено.", e);
                    }
                    last = e;
                    ThreadSleepUtil.sleepUninterruptibly(BACKOFF_402_MS);
                    continue;
                }
                if (status == 404) {
                    log.warn("API: фильм '{}' не найден (404) — оставляю данные Selenium", label);
                    return objectMapper.createObjectNode();
                }
                if (status == 401 || status == 403) {
                    throw e; // фатально, без ретраев
                }
                if (status != 429 && status < 500) {
                    throw e;
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }

            long backoffMs = last instanceof RestClientResponseException
                    && ((RestClientResponseException) last).getStatusCode().value() == 429
                    ? BACKOFF_429_MS * attempt
                    : BACKOFF_ERROR_MS * attempt;
            log.warn("API: не удалось получить {} (попытка {}/{}): {}, повтор через {}мс",
                     label, attempt, MAX_ATTEMPTS, last.getMessage(), backoffMs);
            if (attempt < MAX_ATTEMPTS) {
                ThreadSleepUtil.sleepUninterruptibly(backoffMs);
            }
        }
        throw new RuntimeException("Не удалось получить " + label + " с API Кинопоиска", last);
    }

    /** Rate limit: единый на все API-вызовы. */
    private void rateLimit() {
        ApiRateLimiter.waitIfNeeded();
    }
}