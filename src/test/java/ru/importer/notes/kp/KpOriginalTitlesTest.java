package ru.importer.notes.kp;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fetchOriginalTitles: onBatch каждые 5 фильмов + в конце, заполнение года, остановка;
 * parseYearDigits: выбор года из текста страницы.
 */
class KpOriginalTitlesTest extends KpTestSupport {

    @Test
    void fetchOriginalTitles_shouldFillOriginalTitleAndYearAndCallOnBatchEveryFivePlusFinal() {
        List<MovieData> movies = moviesWithoutEn(12);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // 12 фильмов: onBatch после 5-го и 10-го (2 вызова) + финальный в конце (1) = 3.
        assertEquals(3, batches.get());

        for (MovieData m : movies) {
            assertEquals("Original Title", m.getNameEn(), "original title для " + m.getName());
            assertEquals(2021, m.getYear(), "год для " + m.getName());
        }
    }

    @Test
    void fetchOriginalTitles_shouldNotOverwriteExistingYear() {
        List<MovieData> movies = moviesWithoutEn(3);
        movies.get(0).setYear(1999); // уже проставлен — не должен перезаписываться

        WebDriver driver = mockDriverWithOriginalTitleAndYear();
        importer.fetchOriginalTitles(movies, driver, null, list -> { });

        assertEquals(1999, movies.get(0).getYear());
        assertEquals(2021, movies.get(1).getYear());
        assertEquals(2021, movies.get(2).getYear());
    }

    @Test
    void fetchOriginalTitles_shouldCallOnBatchExactlyOnceWhenFewerThanFive() {
        List<MovieData> movies = moviesWithoutEn(3);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // Меньше 5 — промежуточных вызовов нет, только финальный.
        assertEquals(1, batches.get());
    }

    @Test
    void parseYearDigits_shouldTakeFirstFourDigitYearNotGlueAllDigits() {
        // Элемент содержит год И длительность — наивная склейка дала бы «2021230».
        assertEquals(2021, parser.parseYearDigits("2021 · 2 ч 30 мин"));
        assertEquals(1999, parser.parseYearDigits("1999"));
        assertEquals(2021, parser.parseYearDigits("Премьера: 2021, длительность 2 ч"));
    }

    @Test
    void parseYearDigits_shouldRejectYearsOutsideRange() {
        // Годы вне единого диапазона 1890–2050 (мусор: даты рождения, будущие даты) — не принимаем.
        assertEquals(1899, parser.parseYearDigits("1899"), "1890–1899 — валидная часть диапазона");
        assertEquals(0, parser.parseYearDigits("1889"));
        assertEquals(2050, parser.parseYearDigits("2050"), "MAX_YEAR = 2050 валиден");
        assertEquals(0, parser.parseYearDigits("2051"), "год 2051 вне диапазона");
        assertEquals(0, parser.parseYearDigits("2101"));
        assertEquals(0, parser.parseYearDigits("2100"));
        assertEquals(0, parser.parseYearDigits("1234"));
        assertEquals(0, parser.parseYearDigits(""));
        assertEquals(0, parser.parseYearDigits(null));
    }

    @Test
    void fetchOriginalTitles_shouldSkipMovieWithoutUrlWithoutDoneAndStillSaveDump() {
        // Фильм без kpId и kpUrl пропускается без done++ и advance, но дамп сохраняется.
        MovieData noUrl = new MovieData();
        noUrl.setName("Без ссылки");

        MovieData normal = new MovieData();
        normal.setKpId(1L);
        normal.setName("Фильм 1");

        List<MovieData> movies = new ArrayList<>();
        movies.add(noUrl);
        movies.add(normal);

        WebDriver driver = mockDriverWithOriginalTitleAndYear();

        AtomicInteger batches = new AtomicInteger();
        importer.fetchOriginalTitles(movies, driver, null, list -> batches.incrementAndGet());

        // Фильм без URL не обработан: original title и год не проставлены.
        assertNull(noUrl.getNameEn());
        assertEquals(0, noUrl.getYear());
        // Обычный фильм обработан.
        assertEquals("Original Title", normal.getNameEn());
        assertEquals(2021, normal.getYear());

        // Финальный дамп сохранён (done = 1 > 0).
        assertEquals(1, batches.get());
    }

    @Test
    void fetchOriginalTitles_shouldSaveAccumulatedDumpWhenAbortedMidWay() {
        List<MovieData> movies = moviesWithoutEn(10);
        WebDriver driver = mockDriverWithOriginalTitleAndYear();

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
        importer.fetchOriginalTitles(movies, driver, abortedAfterTwo, list -> batches.incrementAndGet());

        // Обработаны первые 2 фильма (они получают original title и год), остальные — нет.
        assertEquals("Original Title", movies.get(0).getNameEn());
        assertEquals("Original Title", movies.get(1).getNameEn());
        assertNull(movies.get(2).getNameEn());

        // Накопленный дамп сохранён в конце фазы (done=2 > 0) — финальный onBatch вызван.
        assertEquals(1, batches.get());
    }
}
