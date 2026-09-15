package ru.importer.notes.kp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * fixInvalidYears: валидация невалидных годов со страниц фильмов КП.
 */
class KpInvalidYearFixTest extends KpTestSupport {

    @Test
    void fixInvalidYears_shouldFillYearFromFilmPageAndStripGluedName() {
        // Название склеено с годом, страница фильма отдаёт настоящий год — он проставляется,
        // склейка зачищается.
        MovieData glued = new MovieData();
        glued.setKpId(322L);
        glued.setName("Мортал Комбат 22025");
        glued.setNameEn("Mortal Kombat 2");
        glued.setYear(0);

        MovieData plain = new MovieData();
        plain.setKpId(555L);
        plain.setName("Начало");
        plain.setYear(0);

        List<MovieData> movies = new ArrayList<>(List.of(glued, plain));
        WebDriver driver = mockDriverWithPageSources(filmPageWithYear(2025), filmPageWithYear(2010));

        AtomicInteger batches = new AtomicInteger();
        importer.fixInvalidYears(movies, driver, null, list -> batches.incrementAndGet());

        assertEquals("Мортал Комбат 2", movies.get(0).getName(),
                "склейка должна зачиститься: хвост совпал с настоящим годом");
        assertEquals(2025, movies.get(0).getYear());
        assertEquals(2010, movies.get(1).getYear());
        assertEquals("Начало", movies.get(1).getName(), "название без склейки не меняется");

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(driver, times(2)).get(urls.capture());
        assertEquals("https://www.kinopoisk.ru/film/322/", urls.getAllValues().get(0));
        assertEquals("https://www.kinopoisk.ru/film/555/", urls.getAllValues().get(1));

        // 2 фильма (< 5): только финальный дамп.
        assertEquals(1, batches.get());
    }

    @Test
    void fixInvalidYears_shouldKeepZeroWhenPageHasNoYearAndContinue() {
        // Год не найден — одна попытка, остаётся 0; парсинг не прерывается.
        String pageWithoutYear = "<html><head><title>Фильм — смотреть онлайн</title></head>"
                + "<body><p>нет года</p></body></html>";
        MovieData noYear = new MovieData();
        noYear.setKpId(1L);
        noYear.setName("Без года");
        noYear.setYear(0);

        MovieData fixable = new MovieData();
        fixable.setKpId(2L);
        fixable.setName("Должен исправиться");
        fixable.setYear(0);

        List<MovieData> movies = new ArrayList<>(List.of(noYear, fixable));
        WebDriver driver = mockDriverWithPageSources(pageWithoutYear, filmPageWithYear(2011));

        importer.fixInvalidYears(movies, driver, null, list -> { });

        assertEquals(0, noYear.getYear(), "если год не найден на странице — остаётся 0");
        assertEquals(2011, fixable.getYear(), "парсинг продолжается: следующий фильм обрабатывается");
    }

    @Test
    void fixInvalidYears_shouldNotVisitMoviesWithValidYear() {
        MovieData valid = new MovieData();
        valid.setKpId(301L);
        valid.setName("Брат");
        valid.setYear(1997); // валидный — страница фильма не нужна

        WebDriver driver = mock(WebDriver.class);
        importer.fixInvalidYears(List.of(valid), driver, null, null);

        verify(driver, never()).get(anyString());
        assertEquals(1997, valid.getYear());
    }

    @Test
    void fixInvalidYears_shouldNotTouchMeaningfulDigitsInName() {
        // Настоящий год (1968) не совпадает с «хвостом» названия (2001) — название не трогаем.
        MovieData odyssey = new MovieData();
        odyssey.setKpId(1L);
        odyssey.setName("Космическая одиссея 2001");
        odyssey.setNameEn("2001: A Space Odyssey");
        odyssey.setYear(0);

        WebDriver driver = mockDriverWithPageSources(filmPageWithYear(1968));
        importer.fixInvalidYears(List.of(odyssey), driver, null, null);

        assertEquals("Космическая одиссея 2001", odyssey.getName(),
                "цифры, не совпадающие с настоящим годом, — часть названия");
        assertEquals(1968, odyssey.getYear());
    }

    @Test
    void fixInvalidYears_shouldCallOnBatchEveryFivePlusFinal() {
        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            movies.add(m);
        }
        WebDriver driver = mockDriverWithPageSources(java.util.Collections.nCopies(7, filmPageWithYear(2021))
                .toArray(new String[0]));

        AtomicInteger batches = new AtomicInteger();
        importer.fixInvalidYears(movies, driver, null, list -> batches.incrementAndGet());

        // 7 фильмов: onBatch после 5-го (1) + финальный (1) = 2.
        assertEquals(2, batches.get());
        for (MovieData m : movies) {
            assertEquals(2021, m.getYear(), "год для " + m.getName());
        }
    }

    @Test
    void fixInvalidYears_shouldStopWhenAbortedAndStillSaveDump() {
        List<MovieData> movies = moviesWithoutEn(10);
        WebDriver driver = mockDriverWithPageSources(filmPageWithYear(2021));

        // Останавливаемся после обработки 2 фильмов (на третьей итерации isAborted -> true).
        ImportProgress abortedAfterTwo = new ImportProgress() {
            private int advances;
            private boolean aborted;

            @Override
            public void advance(String phase, String movieName, String status) {
                advances++;
                if (advances >= 2) {
                    aborted = true;
                }
            }

            @Override
            public boolean isAborted() {
                return aborted;
            }
        };

        AtomicInteger batches = new AtomicInteger();
        importer.fixInvalidYears(movies, driver, abortedAfterTwo, list -> batches.incrementAndGet());

        // Обработаны первые 2 фильма — год проставлен, остальные не тронуты.
        assertEquals(2021, movies.get(0).getYear());
        assertEquals(2021, movies.get(1).getYear());
        assertEquals(0, movies.get(2).getYear());

        // Накопленный дамп сохранён в конце фазы (done=2 > 0) — финальный onBatch вызван.
        assertEquals(1, batches.get());
    }

    @Test
    void fixInvalidYears_shouldSkipMoviesWithoutUrl() {
        // Фильм без kpId и kpUrl пропускается без запроса (некуда идти), год остаётся 0.
        MovieData noUrl = new MovieData();
        noUrl.setName("Без ссылки");
        noUrl.setYear(0);

        WebDriver driver = mockDriverWithPageSources(filmPageWithYear(2021));
        importer.fixInvalidYears(List.of(noUrl), driver, null, null);

        verify(driver, never()).get(anyString());
        assertEquals(0, noUrl.getYear());
    }
}
