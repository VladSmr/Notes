package ru.importer.notes.kp;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import ru.importer.notes.dto.MovieData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая база фикстур тестов пакета kp: каркас моков Selenium-драйвера
 * и фабрики MovieData.
 */
abstract class KpTestSupport {

    protected final KpNotesImporter importer = new KpNotesImporter();

    /** Парсер HTML-документа выгрузки (вынесен из импортера). */
    protected final KpRatingsPageParser parser = new KpRatingsPageParser();

    /** Драйвер: страница фильма с оригинальным названием и годом в CSS-module классе. */
    protected WebDriver mockDriverWithOriginalTitleAndYear() {
        WebDriver driver = mock(WebDriver.class);
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(driver.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(timeouts.implicitlyWait(any(Duration.class))).thenReturn(timeouts);

        WebElement titleEl = mock(WebElement.class);
        when(titleEl.getText()).thenReturn("Original Title");
        when(driver.findElements(any(By.class))).thenReturn(Collections.singletonList(titleEl));

        // Страница фильма с годом в CSS-module классе (как на реальном КП).
        when(driver.getPageSource()).thenReturn(
                "<html><body><span class=\"styles_year__abc\">2021</span></body></html>");
        return driver;
    }

    /** Драйвер, отдающий источники страниц по порядку (последовательные вызовы getPageSource). */
    protected WebDriver mockDriverWithPageSources(String... pageSources) {
        WebDriver driver = mock(WebDriver.class);
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(driver.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(timeouts.implicitlyWait(any(Duration.class))).thenReturn(timeouts);
        // Последовательные вызовы getPageSource отдают источники по порядку.
        when(driver.getPageSource()).thenReturn(pageSources[0],
                java.util.Arrays.copyOfRange(pageSources, 1, pageSources.length));
        return driver;
    }

    /** Страница фильма КП с годом в CSS-module классе. */
    protected static String filmPageWithYear(int year) {
        return "<html><body><span class=\"styles_year__abc\">" + year + "</span></body></html>";
    }

    /** Список фильмов без nameEn: kpId = i, название "Film i". */
    protected List<MovieData> moviesWithoutEn(int count) {
        List<MovieData> movies = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            MovieData m = new MovieData();
            m.setKpId((long) i);
            m.setName("Film " + i);
            movies.add(m);
        }
        return movies;
    }

    /** Фабрика фильмов для тестов валидации годов: kpId + название + оригинал + год. */
    protected static MovieData movieWith(long kpId, String name, String nameEn, int year) {
        MovieData m = new MovieData();
        m.setKpId(kpId);
        m.setName(name);
        m.setNameEn(nameEn);
        m.setYear(year);
        return m;
    }
}
