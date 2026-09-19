package ru.importer.notes.kp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

/**
 * Парсинг оценок КП через kinopoiskapiunofficial.tech API. Токен обязателен.
 * Невалидные годы фильмов дозапрашиваются из карточки {@code GET /v2.2/films/{id}}.
 */
@Slf4j
@Service
public class ApiKpRatingsProvider implements KpRatingsProvider {

    /**
     * Периодичность промежуточного дампа: уведомляем о каждом 100-м фильме.
     */
    static final int BATCH_SIZE = 100;
    /**
     * Пауза между ретраями при 429 Too Many Requests.
     */
    private static final long BACKOFF_429_MS = 3000L;
    /**
     * Пауза между ретраями при 5xx/сетевых ошибках.
     */
    private static final long BACKOFF_ERROR_MS = 1500L;
    /**
     * Страны СНГ/бывшего СССР: для них допустимо отсутствие оригинального названия.
     */
    private static final Set<String> CIS_COUNTRIES = Set.of(
            "Россия", "СССР", "Российская империя", "Украина", "Беларусь", "Казахстан",
            "Армения", "Азербайджан", "Грузия", "Киргизия", "Кыргызстан", "Молдова",
            "Молдавия", "Таджикистан", "Туркменистан", "Туркмения", "Узбекистан");
    private static final int MAX_ATTEMPTS = 3;
    /**
     * Минимальный интервал между запросами: действует на ВСЕ запросы (страницы и валидация годов),
     * не больше 2 запросов в секунду — ниже лимита эндпоинта votes и квоты тарифа.
     */
    private static final long MIN_REQUEST_INTERVAL_NANOS = 600_000_000L;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private long lastRequestNanos;

    @Autowired
    public ApiKpRatingsProvider(ObjectMapper objectMapper) {
        this(objectMapper, RestClient.builder()
                                     .baseUrl("https://kinopoiskapiunofficial.tech/api")
                                     .build());
    }

    /**
     * Для тестов: подменяет HTTP-слой моком RestClient.
     */
    ApiKpRatingsProvider(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Год из карточки v2.2: {@code year}; для сериалов фолбэк на {@code startYear}.
     */
    static int extractFilmYear(JsonNode film) {
        int year = intYear(film, "year");
        if (KpYearValidator.isValidYear(year)) {
            return year;
        }
        int startYear = intYear(film, "startYear");
        return KpYearValidator.isValidYear(startYear) ? startYear : year;
    }

    /**
     * Числовое поле года из карточки v2.2 (integer/nullable); строки — через parseYear.
     */
    private static int intYear(JsonNode film, String field) {
        JsonNode value = film.path(field);
        if (value.isInt() || value.isLong()) {
            return value.intValue();
        }
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        return parseYear(value.asText(""));
    }

    /**
     * Год из текстового поля API: первый год валидного диапазона (см. {@link KpYearValidator}) либо 0.
     */
    static int parseYear(String value) {
        if (value == null) {
            return 0;
        }
        Matcher m = KpYearValidator.YEAR_PATTERN.matcher(value);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Карточка фильма {@code GET /v2.2/films/{id}} для валидации года. По официальной
     * схеме поля {@code year}/{@code startYear} — числа (nullable); у сериалов год
     * начала показа — в {@code startYear}.
     */
    protected JsonNode fetchFilm(Long filmId, String apiToken) {
        return fetchJsonWithRetry(apiToken, "фильм " + filmId,
                                  () -> restClient.get().uri("/v2.2/films/{id}", filmId));
    }

    /**
     * GET-запрос с ретраями при 429 и 5xx/сетевых ошибках; 4xx без 429 НЕ ретраятся
     * (токен, квота, не найдено). Каждый запрос проходит через {@link #rateLimit()},
     * поэтому лимит не задеть даже при валидации большой пачки фильмов.
     *
     * @param label человекочитаемое имя ресурса для логов (вин. падеж: «страницу 5»)
     */
    private JsonNode fetchJsonWithRetry(String apiToken, String label,
                                        Supplier<RestClient.RequestHeadersSpec<?>> request) {
        Exception last = null;
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
                sleepUninterruptibly(backoffMs);
            }
        }
        throw new RuntimeException("Не удалось получить " + label + " с API Кинопоиска", last);
    }

    protected JsonNode fetchPage(Long userId, String apiToken, int page) {
        return fetchJsonWithRetry(apiToken, "страницу " + page,
                                  () -> restClient.get().uri("/v1/kp_users/{id}/votes?page={page}", userId, page));
    }

    /**
     * Настоящий год из карточки {@code GET /v2.2/films/{id}}; при неудаче год
     * остаётся как есть (0) — фильм получит статус «неполные данные» при проставлении.
     *
     * @return true, если год удалось исправить
     */
    private boolean fixYearFromCatalog(MovieData movie, String apiToken, ImportProgress progress) {
        if (progress != null) {
            // Мягкая пауза перед дозапросом (безопасная точка) и проверка остановки.
            progress.waitWhilePaused();
            if (progress.isAborted()) {
                return false;
            }
        }
        try {
            JsonNode film = fetchFilm(movie.getKpId(), apiToken);
            int year = extractFilmYear(film);
            if (KpYearValidator.isValidYear(year)) {
                log.debug("API: год фильма '{}' (kpId={}) исправлен по /v2.2/films: {} -> {}",
                          movie.getName(), movie.getKpId(), movie.getYear(), year);
                movie.setYear(year);
                return true;
            }
            log.debug("API: в карточке /v2.2/films год фильма '{}' (kpId={}) тоже невалидный: {}",
                      movie.getName(), movie.getKpId(), film.path("year").asText(""));
        } catch (Exception e) {
            log.warn("API: не удалось уточнить год фильма '{}' (kpId={}): {}",
                     movie.getName(), movie.getKpId(), e.getMessage());
        }
        return false;
    }

    @Override
    public String getKey() {
        return "api";
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress) {
        return fetchRatings(userId, apiToken, progress, null);
    }

    @Override
    public List<MovieData> fetchRatings(Long userId, String apiToken, ImportProgress progress,
                                        Consumer<List<MovieData>> onBatch) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Не указан API-токен Кинопоиска");
        }

        List<MovieData> result = new ArrayList<>();
        Set<Long> seenKpIds = new HashSet<>();
        int page = 1;
        int totalPages = Integer.MAX_VALUE;
        int duplicates = 0;
        int invalidYears = 0;
        int yearsFixed = 0;

        while (page <= totalPages) {
            if (progress != null) {
                // Мягкая пауза между страницами, затем проверка остановки.
                progress.waitWhilePaused();
                if (progress.isAborted()) {
                    log.info("Импорт остановлен пользователем на странице {}", page);
                    break;
                }
            }
            JsonNode root = fetchPage(userId, apiToken, page);
            totalPages = root.path("totalPages").asInt(totalPages);
            for (JsonNode item : root.path("items")) {
                MovieData movie = mapItem(item);
                if (movie != null) {
                    // Дедуп по kpId: API может вернуть один фильм в разных вариантах названия/типа.
                    if (!seenKpIds.add(movie.getKpId())) {
                        duplicates++;
                        continue;
                    }
                    // Валидация года на этапе парсинга (до дампа): у фильма с невалидным
                    // годом настоящий год дозапрашивается из карточки /v2.2/films/{id}.
                    if (!KpYearValidator.isValidYear(movie.getYear())) {
                        invalidYears++;
                        if (fixYearFromCatalog(movie, apiToken, progress)) {
                            yearsFixed++;
                        }
                    }
                    result.add(movie);
                    // Промежуточный дамп каждые 100 оценок.
                    if (onBatch != null && result.size() % BATCH_SIZE == 0) {
                        onBatch.accept(result);
                    }
                }
            }
            log.info("API: страница {} загружена, всего фильмов: {}", page, result.size());
            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, "Страница " + page, "api");
            }
            page++;
        }

        if (duplicates > 0) {
            log.info("API: пропущено дублирующихся фильмов по kp_id: {}", duplicates);
        }
        if (invalidYears > 0) {
            log.info("API: валидация годов: невалидных {} из {} фильмов, исправлено через /v2.2/films: {}",
                     invalidYears, result.size(), yearsFixed);
        }
        log.info("API: сканирование завершено. Всего фильмов: {}", result.size());
        return result;
    }

    /**
     * Пробный запрос первой страницы оценок: проверяет, что токен действителен.
     * 401/403 приходят как {@link RestClientResponseException} сразу (без ретраев),
     * 429/5xx ретраятся как обычно.
     */
    @Override
    public void validateToken(Long userId, String apiToken) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Не указан API-токен Кинопоиска");
        }
        fetchPage(userId, apiToken, 1);
    }

    @Override
    public Integer fetchTotalRatings(Long userId, String apiToken) {
        if (apiToken == null || apiToken.isBlank()) {
            return null;
        }
        try {
            JsonNode root = fetchPage(userId, apiToken, 1);
            return root.path("total").asInt(0);
        } catch (Exception e) {
            log.warn("API: не удалось получить общее количество оценок: {}", e.getMessage());
            return null;
        }
    }

    private boolean isCis(JsonNode countries) {
        if (countries == null || !countries.isArray()) {
            return false;
        }
        for (JsonNode country : countries) {
            String name = country.path("country").asText("");
            if (CIS_COUNTRIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Маппинг элемента items[] в MovieData.
     */
    MovieData mapItem(JsonNode item) {
        long kpId = item.path("kinopoiskId").asLong(0);
        if (kpId == 0) {
            return null;
        }

        String nameOriginal = blankToNull(item.path("nameOriginal").asText(null));
        String nameEn = blankToNull(item.path("nameEn").asText(null));
        // КП API отдаёт nameOriginal не для всех фильмов (поле nullable). Для зарубежных
        // фильмов оригинальное название совпадает с английским — фолбэк на nameEn, чтобы
        // original_title в kp-ratings.csv не оставался пустым. У российских фильмов пусты
        // оба — original_title остаётся пустым, title уже заполнен из nameRu.
        if (nameOriginal == null) {
            nameOriginal = nameEn;
        }
        if (nameEn == null) {
            nameEn = nameOriginal;
        }

        String name = blankToNull(item.path("nameRu").asText(null));
        if (name == null) {
            name = nameOriginal;
        }
        if (name == null) {
            name = nameEn;
        }

        MovieData movie = new MovieData();
        movie.setKpId(kpId);
        movie.setImdbId(blankToNull(item.path("imdbId").asText(null)));
        movie.setName(name);
        movie.setNameOriginal(nameOriginal);
        movie.setNameEn(nameEn);
        movie.setYear(parseYear(item.path("year").asText("")));
        movie.setKpRating(item.path("userRating").asInt(0));

        String type = item.path("type").asText("FILM");
        boolean series = type != null && type.contains("SERIES");
        movie.setKpUrl("https://www.kinopoisk.ru/" + (series ? "series/" : "film/") + kpId + "/");

        if (nameOriginal == null && nameEn == null) {
            boolean cis = isCis(item.path("countries"));
            if (!cis) {
                log.warn("Внимание: у фильма '{}' ({}, kpId={}) нет ни оригинального, ни английского названия",
                         name, movie.getYear(), kpId);
            }
        }
        return movie;
    }

    /**
     * Выдерживает лимит API: не чаще 2 запросов в секунду.
     */
    private void rateLimit() {
        long now = System.nanoTime();
        if (lastRequestNanos != 0) {
            long elapsed = now - lastRequestNanos;
            if (elapsed < MIN_REQUEST_INTERVAL_NANOS) {
                sleepUninterruptibly((MIN_REQUEST_INTERVAL_NANOS - elapsed) / 1_000_000L + 1);
            }
        }
        lastRequestNanos = System.nanoTime();
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
