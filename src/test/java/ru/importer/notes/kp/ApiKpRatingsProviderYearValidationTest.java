package ru.importer.notes.kp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Валидация года в API-провайдере: невалидный год из списка оценок дозапрашивается
 * из {@code GET /v2.2/films/{id}}. HTTP-слой RestClient замокан — сеть не трогается,
 * при этом реальный код fetchPage/fetchFilm (rate limit, ретраи, заголовки) выполняется.
 */
class ApiKpRatingsProviderYearValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Замоканные цепочки RestClient: ответы раздаются по шаблону URI запроса. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class MockHttp {
        final RestClient restClient;
        final RestClient.RequestHeadersSpec votesChain;
        final RestClient.RequestHeadersSpec filmsChain;

        MockHttp(String votesBody, String filmsBody) {
            RestClient.ResponseSpec votesResponse = mock(RestClient.ResponseSpec.class);
            when(votesResponse.body(String.class)).thenReturn(votesBody);
            votesChain = mock(RestClient.RequestHeadersSpec.class);
            doReturn(votesChain).when(votesChain).header(anyString(), any(String[].class));
            when(votesChain.retrieve()).thenReturn(votesResponse);

            RestClient.ResponseSpec filmsResponse = mock(RestClient.ResponseSpec.class);
            if (filmsBody == null) {
                // filmsBody == null => эндпоинт фильма отдаёт 404 Not Found.
                when(filmsResponse.body(String.class))
                        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
            } else {
                when(filmsResponse.body(String.class)).thenReturn(filmsBody);
            }
            filmsChain = mock(RestClient.RequestHeadersSpec.class);
            doReturn(filmsChain).when(filmsChain).header(anyString(), any(String[].class));
            when(filmsChain.retrieve()).thenReturn(filmsResponse);

            RestClient.RequestHeadersUriSpec uriSpec =
                    (RestClient.RequestHeadersUriSpec) mock(RestClient.RequestHeadersUriSpec.class);
            when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> {
                String template = inv.getArgument(0);
                return template.startsWith("/v2.2/films/") ? filmsChain : votesChain;
            });

            restClient = mock(RestClient.class);
            when(restClient.get()).thenReturn(uriSpec);
        }
    }

    private static String filmItem(long id, String year) {
        return "{\"kinopoiskId\":" + id + ",\"nameRu\":\"Фильм " + id + "\",\"nameEn\":\"Movie " + id
                + "\",\"year\":\"" + year + "\",\"type\":\"FILM\",\"userRating\":5}";
    }

    private static String votesPage(String... items) {
        return "{\"totalPages\":1,\"items\":[" + String.join(",", items) + "]}";
    }

    @Test
    void fetchRatings_invalidYear_fetchedFromFilmEndpoint() {
        // В списке оценок год пустой; в карточке v2.2 поле year — ЧИСЛО (по официальной спеке).
        MockHttp http = new MockHttp(
                votesPage(filmItem(301, "1997"), filmItem(302, "")),
                "{\"kinopoiskId\":302,\"nameRu\":\"Без года\",\"year\":1972}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null);

        assertEquals(2, movies.size());
        assertEquals(1997, movies.get(0).getYear(), "валидный год не трогается");
        assertEquals(1972, movies.get(1).getYear(), "невалидный год должен взяться из /v2.2/films");
        verify(http.votesChain, times(1)).retrieve();
        verify(http.filmsChain, times(1)).retrieve();
    }

    @Test
    void fetchRatings_invalidYearAndFilmEndpointAlsoInvalid_yearStaysZero() {
        // Год невалиден и в карточке — остаётся честный 0 (потом статус «неполные данные»).
        MockHttp http = new MockHttp(
                votesPage(filmItem(303, "мусор")),
                "{\"kinopoiskId\":303,\"nameRu\":\"Мусор\",\"year\":null}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);

        assertEquals(1, movies.size());
        assertEquals(0, movies.get(0).getYear(), "если в v2.2 год тоже невалидный — остаётся 0");
    }

    @Test
    void fetchRatings_filmEndpointNotFound_yearStaysZeroAndParsingContinues() {
        // 404 на карточке фильма не должен ломать весь парсинг: год остаётся 0, поток идёт дальше.
        MockHttp http = new MockHttp(
                votesPage(filmItem(404, ""), filmItem(305, "2003")),
                null);

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);

        assertEquals(2, movies.size());
        assertEquals(0, movies.get(0).getYear(), "год остаётся честным 0 при ошибке карточки");
        assertEquals(2003, movies.get(1).getYear(), "парсинг продолжается после ошибки");
        verify(http.filmsChain, times(1)).retrieve();
    }

    @Test
    void fetchRatings_seriesYearTakenFromStartYear() {
        // Для сериалов в v2.2 год начала показа лежит в числовом startYear.
        MockHttp http = new MockHttp(
                votesPage(filmItem(7385223, "")),
                "{\"kinopoiskId\":7385223,\"nameRu\":\"Сериал\",\"year\":null,\"startYear\":2015,"
                        + "\"endYear\":null,\"type\":\"TV_SERIES\"}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);

        assertEquals(1, movies.size());
        assertEquals(2015, movies.get(0).getYear(), "год сериала должен взяться из startYear");
    }

    @Test
    void extractFilmYear_shouldParseNumberStringAndRange() {
        ObjectMapper om = new ObjectMapper();
        try {
            assertEquals(1999, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":1999}")));
            assertEquals(1895, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":1895}")),
                    "числовой год в диапазоне 1890–2050 принимается как есть");
            assertEquals(2050, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":2050}")),
                    "MAX_YEAR = 2050 принимается");
            assertEquals(2012, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":\"2012-2014\"}")),
                    "строка-диапазон: берётся год начала");
            assertEquals(0, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":null,\"startYear\":null}")));
            assertEquals(0, ApiKpRatingsProvider.extractFilmYear(om.readTree("{}")));
            assertEquals(0, ApiKpRatingsProvider.extractFilmYear(om.readTree("{\"year\":\"???\"}")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fetchRatings_filmEndpointCalledOnlyForInvalidYears() {
        // 5 фильмов, 2 с невалидным годом: запросов к /v2.2/films — ровно 2, к votes — 1.
        MockHttp http = new MockHttp(
                votesPage(filmItem(1, "2001"), filmItem(2, ""), filmItem(3, "2003"),
                        filmItem(4, ""), filmItem(5, "2005")),
                "{\"kinopoiskId\":0,\"year\":2020}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);

        long start = System.nanoTime();
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(5, movies.size());
        assertEquals(2020, movies.get(1).getYear());
        assertEquals(2020, movies.get(3).getYear());
        assertEquals(2001, movies.get(0).getYear());
        // Валидация ТОЛЬКО для невалидных годов, НЕ для всех фильмов.
        verify(http.filmsChain, times(2)).retrieve();
        verify(http.votesChain, times(1)).retrieve();
        // Rate limit не задет при пачке: 3 запроса по 600мс (общий rateLimit) => >= 1200мс.
        assertTrue(elapsedMs >= 1100,
                "все запросы (страницы + валидация) должны идти через rateLimit, elapsed=" + elapsedMs + "мс");
    }

    @Test
    void fetchRatings_duplicatesNotValidatedTwice() {
        // Дубль по kpId пропускается ДО валидации — лишнего запроса карточки нет.
        MockHttp http = new MockHttp(
                votesPage(filmItem(401, ""), filmItem(401, "")),
                "{\"kinopoiskId\":401,\"nameRu\":\"Фильм 401\",\"year\":1984}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<MovieData> movies = provider.fetchRatings(1L, "token", null, null);

        assertEquals(1, movies.size());
        assertEquals(1984, movies.get(0).getYear());
        verify(http.filmsChain, times(1)).retrieve();
    }

    @Test
    void fetchRatings_batchCallbackStillFiresEvery100WithValidation() {
        // 100 фильмов, у последнего невалидный год: колбэк onBatch не должен сломаться.
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= 99; i++) {
            if (i > 1) {
                items.append(',');
            }
            items.append(filmItem(1000L + i, "2000"));
        }
        items.append(",{\"kinopoiskId\":2000,\"nameRu\":\"Особый\",\"nameEn\":\"Special\","
                + "\"year\":\"\",\"type\":\"FILM\",\"userRating\":5}");
        MockHttp http = new MockHttp(
                "{\"totalPages\":1,\"items\":[" + items + "]}",
                "{\"kinopoiskId\":2000,\"nameRu\":\"Особый\",\"year\":1957}");

        ApiKpRatingsProvider provider = new ApiKpRatingsProvider(mapper, http.restClient);
        List<Integer> batchSizes = new ArrayList<>();
        List<MovieData> movies = provider.fetchRatings(1L, "token", null,
                batch -> batchSizes.add(batch.size()));

        assertEquals(100, movies.size());
        assertEquals(List.of(100), batchSizes, "колбэк onBatch должен сработать на 100-м фильме как раньше");
        assertEquals(1957, movies.get(99).getYear(), "год последнего фильма должен успеть исправиться до дампа");
    }
}
