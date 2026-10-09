package ru.importer.notes.kp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты {@link ApiFilmCardEnricher}: маппинг полей, merge-логика.
 * HTTP-слой тестируется через subclass с переопределённым {@link #fetchFilm};
 * HTTP-ошибки — через замоканный RestClient (GET → RequestHeadersUriSpec).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ApiFilmCardEnricherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // HTTP-мок (GET → RequestHeadersUriSpec — для GET-запросов)
    // -------------------------------------------------------------------------

    private RestClient mockGetClient(String body, HttpStatus errorStatus) {
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        if (errorStatus != null) {
            when(response.body(String.class))
                    .thenThrow(new HttpClientErrorException(errorStatus));
        } else {
            when(response.body(String.class)).thenReturn(body);
        }

        RestClient.RequestHeadersSpec<?> headersChain = mock(RestClient.RequestHeadersSpec.class);
        doReturn(headersChain).when(headersChain).header(anyString(), any(String[].class));
        when(headersChain.retrieve()).thenReturn(response);

        // GET → RequestHeadersUriSpec
        RestClient.RequestHeadersUriSpec uriSpec =
                (RestClient.RequestHeadersUriSpec) mock(RestClient.RequestHeadersUriSpec.class);
        doReturn(headersChain).when(uriSpec).uri(anyString(), any(Object.class));

        RestClient rc = mock(RestClient.class);
        when(rc.get()).thenReturn(uriSpec);
        return rc;
    }

    private ApiFilmCardEnricher enricher(RestClient rc) {
        return new ApiFilmCardEnricher(mapper, rc);
    }

    // -------------------------------------------------------------------------
    // Subclass для тестов маппинга (подменяет fetchFilm — HTTP не нужен)
    // -------------------------------------------------------------------------

    private static class TestableEnricher extends ApiFilmCardEnricher {
        private final JsonNode fakeResponse;
        private int callCount = 0;
        private Long lastKpId;

        TestableEnricher(ObjectMapper mapper, JsonNode fakeResponse) {
            super(mapper);
            this.fakeResponse = fakeResponse;
        }

        @Override
        JsonNode fetchFilm(Long filmId, String apiToken) {
            callCount++;
            lastKpId = filmId;
            return fakeResponse;
        }

        int callCount() { return callCount; }
        Long lastKpId() { return lastKpId; }
    }

    private TestableEnricher enricher(String jsonBody) {
        JsonNode node;
        try {
            node = jsonBody == null ? mapper.createObjectNode() : mapper.readTree(jsonBody);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new TestableEnricher(mapper, node);
    }

    // -------------------------------------------------------------------------
    // Тесты маппинга (через subclass)
    // -------------------------------------------------------------------------

    @Test
    void enrich_mapsNameOriginalNameEn() {
        TestableEnricher e = enricher(
                "{\"nameOriginal\":\"Original Title\",\"nameEn\":\"English Title\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        e.enrich(m, "token", null);
        assertEquals("Original Title", m.getNameOriginal());
        assertEquals("English Title", m.getNameEn());
    }

    @Test
    void enrich_fallsBackNameOriginalFromNameEn() {
        TestableEnricher e = enricher("{\"nameEn\":\"English Fallback\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        e.enrich(m, "token", null);
        assertEquals("English Fallback", m.getNameOriginal());
    }

    @Test
    void enrich_doesNotOverwriteExistingNameEn_preservesNameEn() {
        // API не затирает непустое nameEn. nameOriginal: null → фолбэк на nameEn из карточки.
        TestableEnricher e = enricher("{\"nameEn\":\"API Title\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setNameEn("Existing Title");
        e.enrich(m, "token", null);
        assertEquals("Existing Title", m.getNameEn(), "непустое nameEn не затирается");
        assertEquals("Existing Title", m.getNameOriginal(),
                "nameOriginal = null → фолбэк на nameEn из карточки API");
    }

    @Test
    void enrich_doesNotOverwriteExistingNameOriginal() {
        TestableEnricher e = enricher("{\"nameOriginal\":\"API Original\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setNameOriginal("Existing Original");
        e.enrich(m, "token", null);
        assertEquals("Existing Original", m.getNameOriginal());
    }

    // ---- Год ----

    @Test
    void enrich_apiYearWins_whenValid() {
        TestableEnricher e = enricher("{\"year\":2019,\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setYear(2015);
        e.enrich(m, "token", null);
        assertEquals(2019, m.getYear());
    }

    @Test
    void enrich_keepsSeleniumYear_whenApiYearInvalid() {
        TestableEnricher e = enricher("{\"year\":0,\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setYear(2015);
        e.enrich(m, "token", null);
        assertEquals(2015, m.getYear());
    }

    @Test
    void enrich_apiYearWins_range() {
        TestableEnricher e = enricher("{\"year\":\"2012-2014\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setYear(0);
        e.enrich(m, "token", null);
        assertEquals(2012, m.getYear());
    }

    @Test
    void enrich_apiYearStartYearFallback() {
        TestableEnricher e = enricher("{\"year\":null,\"startYear\":2015,\"type\":\"TV_SERIES\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setYear(0);
        e.enrich(m, "token", null);
        assertEquals(2015, m.getYear());
    }

    // ---- imdbId ----

    @Test
    void enrich_imdbIdIsFilled_whenEmpty() {
        TestableEnricher e = enricher("{\"imdbId\":\"tt1234567\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        e.enrich(m, "token", null);
        assertEquals("tt1234567", m.getImdbId());
    }

    @Test
    void enrich_imdbIdNotOverwritten_whenPresent() {
        TestableEnricher e = enricher("{\"imdbId\":\"tt9999999\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setImdbId("tt1111111");
        e.enrich(m, "token", null);
        assertEquals("tt1111111", m.getImdbId());
    }

    // ---- type/kpUrl ----

    @Test
    void enrich_seriesTypes() {
        assertTrue(ApiFilmCardEnricher.isSeriesType("TV_SERIES"));
        assertTrue(ApiFilmCardEnricher.isSeriesType("MINI_SERIES"));
        assertTrue(ApiFilmCardEnricher.isSeriesType("TV_SHOW"));
        assertFalse(ApiFilmCardEnricher.isSeriesType("FILM"));
        assertFalse(ApiFilmCardEnricher.isSeriesType("VIDEO"));
        assertFalse(ApiFilmCardEnricher.isSeriesType(null));
    }

    @Test
    void enrich_tvShowGoesToSeriesUrl() {
        TestableEnricher e = enricher("{\"type\":\"TV_SHOW\"}");
        MovieData m = new MovieData();
        m.setKpId(456L);
        e.enrich(m, "token", null);
        assertEquals("https://www.kinopoisk.ru/series/456/", m.getKpUrl());
    }

    @Test
    void enrich_filmGoesToFilmUrl() {
        TestableEnricher e = enricher("{\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(789L);
        e.enrich(m, "token", null);
        assertEquals("https://www.kinopoisk.ru/film/789/", m.getKpUrl());
    }

    @Test
    void enrich_callsFetchFilmWithCorrectKpId() {
        TestableEnricher e = enricher("{\"nameOriginal\":\"T\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        m.setKpId(999L);
        e.enrich(m, "token", null);
        assertEquals(1, e.callCount());
        assertEquals(999L, e.lastKpId());
    }

    @Test
    void enrich_skipsWhenNoKpId() {
        TestableEnricher e = enricher("{\"nameOriginal\":\"T\",\"type\":\"FILM\"}");
        MovieData m = new MovieData();
        e.enrich(m, "token", null);
        assertEquals(0, e.callCount());
    }

    // -------------------------------------------------------------------------
    // Тесты HTTP-ошибок (через замоканный RestClient)
    // -------------------------------------------------------------------------

    @Test
    void enrich_402x3_throwsKpQuotaExhaustedException() {
        ApiFilmCardEnricher e = enricher(mockGetClient(null, HttpStatus.PAYMENT_REQUIRED));
        MovieData m = new MovieData();
        m.setKpId(123L);
        assertThrows(KpQuotaExhaustedException.class, () -> e.enrich(m, "token", null));
    }

    @Test
    void enrich_402x2_then200_succeeds() {
        // Первые 2 вызова — 402, третий — успех.
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        when(response.body(String.class))
                .thenThrow(new HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED))
                .thenThrow(new HttpClientErrorException(HttpStatus.PAYMENT_REQUIRED))
                .thenReturn("{\"nameOriginal\":\"Fixed Title\",\"type\":\"FILM\"}");

        RestClient.RequestHeadersSpec<?> headersChain = mock(RestClient.RequestHeadersSpec.class);
        doReturn(headersChain).when(headersChain).header(anyString(), any(String[].class));
        when(headersChain.retrieve()).thenReturn(response);

        RestClient.RequestHeadersUriSpec uriSpec =
                (RestClient.RequestHeadersUriSpec) mock(RestClient.RequestHeadersUriSpec.class);
        doReturn(headersChain).when(uriSpec).uri(anyString(), any(Object.class));

        RestClient rc = mock(RestClient.class);
        when(rc.get()).thenReturn(uriSpec);

        ApiFilmCardEnricher e = enricher(rc);
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setNameOriginal(null);

        e.enrich(m, "token", null);
        assertEquals("Fixed Title", m.getNameOriginal());
    }

    @Test
    void enrich_401_throwsFatal() {
        ApiFilmCardEnricher e = enricher(mockGetClient(null, HttpStatus.UNAUTHORIZED));
        MovieData m = new MovieData();
        m.setKpId(123L);
        assertThrows(HttpClientErrorException.class, () -> e.enrich(m, "token", null));
    }

    @Test
    void enrich_404_doesNotThrow() {
        ApiFilmCardEnricher e = enricher(mockGetClient(null, HttpStatus.NOT_FOUND));
        MovieData m = new MovieData();
        m.setKpId(123L);
        m.setName("Test");
        m.setYear(2000);

        assertDoesNotThrow(() -> e.enrich(m, "token", null));
        assertEquals(2000, m.getYear());
        assertEquals("Test", m.getName());
    }
}