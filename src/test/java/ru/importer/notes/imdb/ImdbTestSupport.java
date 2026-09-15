package ru.importer.notes.imdb;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import ru.importer.notes.dto.MovieData;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая база фикстур тестов пакета imdb: каркас моков Selenium-драйвера,
 * билдеры MovieData и связка верификатора/поиска/кликера.
 */
abstract class ImdbTestSupport {

    protected final ImdbNotesExporter exporter = new ImdbNotesExporter();
    protected final ImdbPageVerifier verifier = new ImdbPageVerifier();
    protected final ImdbSearchAndSelect search = new ImdbSearchAndSelect(verifier);
    protected final ImdbRatingClicker clicker = new ImdbRatingClicker();

    // ------------------------------------------------------------------
    // Мок WebDriver: manage()/timeouts() + опционально результаты findElements
    // ------------------------------------------------------------------

    protected WebDriver mockDriver(List<WebElement> findElementsResult) {
        WebDriver driver = mock(WebDriver.class);
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(driver.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(timeouts.implicitlyWait(any(Duration.class))).thenReturn(timeouts);
        when(timeouts.getImplicitWaitTimeout()).thenReturn(Duration.ofSeconds(2));
        if (findElementsResult != null) {
            when(driver.findElements(any(By.class))).thenReturn(findElementsResult);
        }
        return driver;
    }

    /** Полностью заполненный фильм (название + год + оценка) — на IMDB идти можно. */
    protected MovieData completeMovie() {
        MovieData movie = new MovieData();
        movie.setKpId(12928878L);
        movie.setName("Женщина-Халк: Адвокат");
        movie.setNameEn("She-Hulk: Attorney at Law");
        movie.setYear(2022);
        movie.setKpRating(8);
        return movie;
    }

    /** HTML-страница с одним JSON-LD-скриптом (как на тайтл-страницах IMDB). */
    protected static String pageWithJsonLd(String jsonLd) {
        return "<html><head>"
                + "<script type=\"application/ld+json\">" + jsonLd + "</script>"
                + "</head><body></body></html>";
    }

    /** Мок WebDriver тайтл-страницы: заголовок + исходник страницы (JSON-LD). */
    protected WebDriver titlePageDriver(String title, String pageSource) {
        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getTitle()).thenReturn(title);
        when(driver.getPageSource()).thenReturn(pageSource);
        return driver;
    }
}
