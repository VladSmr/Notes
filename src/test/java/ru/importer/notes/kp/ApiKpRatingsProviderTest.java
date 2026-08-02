package ru.importer.notes.kp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.importer.notes.dto.MovieData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKpRatingsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKpRatingsProvider provider = new ApiKpRatingsProvider(objectMapper);

    private JsonNode json(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void mapItem_shouldMapFilm() {
        JsonNode item = json("{\"kinopoiskId\":301,\"nameRu\":\"Брат\",\"nameOriginal\":\"Brat\"," +
                "\"nameEn\":\"Brother\",\"imdbId\":\"tt0118767\",\"year\":\"1997\",\"type\":\"FILM\"," +
                "\"userRating\":9,\"countries\":[{\"country\":\"Россия\"}]}");

        MovieData movie = provider.mapItem(item);
        assertEquals(301L, movie.getKpId());
        assertEquals("tt0118767", movie.getImdbId());
        assertEquals("Брат", movie.getName());
        assertEquals("Brat", movie.getNameOriginal());
        assertEquals("Brother", movie.getNameEn());
        assertEquals(1997, movie.getYear());
        assertEquals(9, movie.getKpRating());
        assertEquals("https://www.kinopoisk.ru/film/301/", movie.getKpUrl());
    }

    @Test
    void mapItem_shouldPreferEnglishNameOverOriginalForImdbSearch() {
        JsonNode item = json("{\"kinopoiskId\":999,\"nameRu\":\"Жизнь прекрасна\",\"nameOriginal\":\"La vita è bella\"," +
                "\"nameEn\":\"Life Is Beautiful\",\"year\":\"1997\",\"type\":\"FILM\",\"userRating\":9}");

        MovieData movie = provider.mapItem(item);
        assertEquals("La vita è bella", movie.getNameOriginal());
        assertEquals("Life Is Beautiful", movie.getNameEn());
    }

    @Test
    void mapItem_shouldMapSeriesWithSeriesUrl() {
        JsonNode item = json("{\"kinopoiskId\":7385223,\"nameRu\":\"Сериал\",\"nameOriginal\":\"The Show\"," +
                "\"year\":\"2026\",\"type\":\"TV_SERIES\",\"userRating\":8}");

        MovieData movie = provider.mapItem(item);
        assertEquals(7385223L, movie.getKpId());
        assertEquals("The Show", movie.getNameOriginal());
        assertEquals("The Show", movie.getNameEn());
        assertEquals(2026, movie.getYear());
        assertEquals("https://www.kinopoisk.ru/series/7385223/", movie.getKpUrl());
    }

    @Test
    void mapItem_shouldFallbackToNameEnWhenNoNameOriginal() {
        JsonNode item = json("{\"kinopoiskId\":5,\"nameRu\":\"Фильм\",\"nameEn\":\"Fallback Title\"," +
                "\"year\":\"2001\",\"type\":\"FILM\",\"userRating\":7}");

        MovieData movie = provider.mapItem(item);
        assertEquals("Fallback Title", movie.getNameEn());
    }

    @Test
    void mapItem_shouldParseYearFromRange() {
        JsonNode item = json("{\"kinopoiskId\":6,\"nameRu\":\"Сага\",\"year\":\"2012-2014\"," +
                "\"type\":\"TV_SERIES\",\"userRating\":7}");

        MovieData movie = provider.mapItem(item);
        assertEquals(2012, movie.getYear());
    }

    @Test
    void mapItem_shouldReturnNullWhenNoKpId() {
        assertNull(provider.mapItem(json("{\"nameRu\":\"Без id\"}")));
    }

    @Test
    void mapItem_shouldUseNameOriginalAsRussianNameWhenNameRuMissing() {
        JsonNode item = json("{\"kinopoiskId\":7,\"nameOriginal\":\"Only Original\",\"year\":\"2005\"," +
                "\"type\":\"FILM\",\"userRating\":6}");

        MovieData movie = provider.mapItem(item);
        assertEquals("Only Original", movie.getName());
    }

    @Test
    void parseYear_shouldHandleNullAndGarbage() {
        assertEquals(0, ApiKpRatingsProvider.parseYear(null));
        assertEquals(0, ApiKpRatingsProvider.parseYear(""));
        assertEquals(0, ApiKpRatingsProvider.parseYear("abc"));
        assertEquals(1985, ApiKpRatingsProvider.parseYear("1985"));
    }

}
