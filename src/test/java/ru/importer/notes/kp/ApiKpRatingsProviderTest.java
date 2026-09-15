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
    void mapItem_shouldFallbackOriginalTitleToEnglishName() {
        // nameOriginal nullable: для зарубежных фильмов original_title фолбэк на nameEn,
        // чтобы колонка в CSV не пустовала.
        JsonNode item = json("{\"kinopoiskId\":12928878,\"nameRu\":\"Женщина-Халк: Адвокат\"," +
                "\"nameEn\":\"She-Hulk: Attorney at Law\",\"year\":\"2022\",\"type\":\"TV_SERIES\",\"userRating\":8}");

        MovieData movie = provider.mapItem(item);
        assertEquals("Женщина-Халк: Адвокат", movie.getName());
        assertEquals("She-Hulk: Attorney at Law", movie.getNameEn());
        assertEquals("She-Hulk: Attorney at Law", movie.getNameOriginal(), "original_title должен заполниться из nameEn");
    }

    @Test
    void mapItem_shouldKeepEmptyOriginalTitleForRussianFilm() {
        // У российских фильмов на КП нет ни nameEn, ни nameOriginal — original_title
        // остаётся пустым, title берётся из nameRu (это ограничение данных API).
        JsonNode item = json("{\"kinopoiskId\":301,\"nameRu\":\"Брат\",\"year\":\"1997\"," +
                "\"type\":\"FILM\",\"userRating\":9,\"countries\":[{\"country\":\"Россия\"}]}");

        MovieData movie = provider.mapItem(item);
        assertEquals("Брат", movie.getName());
        assertNull(movie.getNameOriginal());
        assertNull(movie.getNameEn());
    }

    @Test
    void parseYear_shouldHandleNullAndGarbage() {
        assertEquals(0, ApiKpRatingsProvider.parseYear(null));
        assertEquals(0, ApiKpRatingsProvider.parseYear(""));
        assertEquals(0, ApiKpRatingsProvider.parseYear("abc"));
        assertEquals(1985, ApiKpRatingsProvider.parseYear("1985"));
    }

    @Test
    void parseYear_shouldRespectSingleValidatorRange() {
        // Диапазон года — единый источник KpYearValidator (1890–2050).
        assertEquals(1895, ApiKpRatingsProvider.parseYear("1895"), "1890-е — валидная часть диапазона");
        assertEquals(2050, ApiKpRatingsProvider.parseYear("2050"), "MAX_YEAR = 2050 принимается");
        assertEquals(0, ApiKpRatingsProvider.parseYear("1889"), "до 1890 — мусор");
        assertEquals(0, ApiKpRatingsProvider.parseYear("2051"), "год 2051 не распознаётся");
        assertEquals(0, ApiKpRatingsProvider.parseYear("2100"), "2100 больше не в диапазоне");
    }

}
