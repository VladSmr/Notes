package ru.importer.notes.kp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

/** Парсинг оценок КП через kinopoiskapiunofficial.tech API. Токен обязателен. */
@Service
public class ApiKpRatingsProvider implements KpRatingsProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKpRatingsProvider.class);

    /** Страны СНГ/бывшего СССР: для них допустимо отсутствие оригинального названия. */
    private static final Set<String> CIS_COUNTRIES = Set.of(
            "Россия", "СССР", "Российская империя", "Украина", "Беларусь", "Казахстан",
            "Армения", "Азербайджан", "Грузия", "Киргизия", "Кыргызстан", "Молдова",
            "Молдавия", "Таджикистан", "Туркменистан", "Туркмения", "Узбекистан");

    private static final Pattern YEAR_PATTERN = Pattern.compile("(19\\d{2}|20\\d{2})");

    private static final int MAX_ATTEMPTS = 3;

    /** Периодичность промежуточного дампа: уведомляем о каждом 100-м фильме. */
    static final int BATCH_SIZE = 100;

    /** Минимальный интервал между запросами: лимит API — 2 запроса в секунду. */
    private static final long MIN_REQUEST_INTERVAL_NANOS = 600_000_000L;

    /** Пауза между ретраями при 429 Too Many Requests. */
    private static final long BACKOFF_429_MS = 3000L;

    /** Пауза между ретраями при 5xx/сетевых ошибках. */
    private static final long BACKOFF_ERROR_MS = 1500L;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private long lastRequestNanos;

    public ApiKpRatingsProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://kinopoiskapiunofficial.tech/api")
                .build();
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
        int page = 1;
        int totalPages = Integer.MAX_VALUE;

        while (page <= totalPages) {
            if (progress != null && progress.isAborted()) {
                log.info("Импорт остановлен пользователем на странице {}", page);
                break;
            }
            JsonNode root = fetchPage(userId, apiToken, page);
            totalPages = root.path("totalPages").asInt(totalPages);
            for (JsonNode item : root.path("items")) {
                MovieData movie = mapItem(item);
                if (movie != null) {
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

        log.info("API: сканирование завершено. Всего фильмов: {}", result.size());
        return result;
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

    protected JsonNode fetchPage(Long userId, String apiToken, int page) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                rateLimit();
                String body = restClient.get()
                        .uri("/v1/kp_users/{id}/votes?page={page}", userId, page)
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
            log.warn("API: попытка {} страницы {} не удалась: {}, повтор через {}мс",
                    attempt, page, last.getMessage(), backoffMs);
            if (attempt < MAX_ATTEMPTS) {
                sleepUninterruptibly(backoffMs);
            }
        }
        throw new RuntimeException("Не удалось получить страницу " + page + " с API Кинопоиска", last);
    }

    /** Выдерживает лимит API: не чаще 2 запросов в секунду. */
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

    /** Маппинг элемента items[] в MovieData. package-private для тестов. */
    MovieData mapItem(JsonNode item) {
        long kpId = item.path("kinopoiskId").asLong(0);
        if (kpId == 0) {
            return null;
        }

        String nameOriginal = blankToNull(item.path("nameOriginal").asText(null));
        String nameEn = blankToNull(item.path("nameEn").asText(null));
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
                log.warn("Внимание: у фильма '{}' ({}, kpId={}) нет ни оригинального, ни английского названия — страна не СНГ, это ошибка данных",
                        name, movie.getYear(), kpId);
            }
        }
        return movie;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static int parseYear(String value) {
        if (value == null) {
            return 0;
        }
        Matcher m = YEAR_PATTERN.matcher(value);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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

}
