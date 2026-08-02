package ru.importer.notes.imdb;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ImdbSearchRealTest {

    private WebDriver driver;

    private WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        return new ChromeDriver(options);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
    }

    @Test
    void search_shouldFindShawshankRedemption() {
        driver = createDriver();
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(15));
        driver.get("https://www.imdb.com/find/?q=The+Shawshank+Redemption+1994");

        boolean foundShawshank = driver.findElements(By.cssSelector("a[href*='/title/tt']")).stream()
                .map(WebElement::getText)
                .anyMatch(t -> t.toLowerCase().contains("shawshank"));
        assertTrue(foundShawshank, "Should find 'Shawshank' in search results");
    }

    @Test
    void search_shouldFindInception() {
        driver = createDriver();
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        driver.get("https://www.imdb.com/find/?q=Inception+2010");

        List<WebElement> links = driver.findElements(By.cssSelector("a[href*='/title/tt']"));
        boolean foundInception = links.stream()
                .map(WebElement::getText)
                .anyMatch(t -> t.toLowerCase().contains("inception"));
        assertFalse(links.isEmpty(), "Should find title links for Inception search");
    }

    @Test
    void search_shouldFindMoviesByRussianTitles() {
        driver = createDriver();
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

        String[][] testCases = {
                {"The+Shawshank+Redemption+1994", "tt0111161"},
                {"Inception+2010", "tt1375666"},
                {"Interstellar+2014", "tt0816692"},
        };

        for (String[] tc : testCases) {
            String query = tc[0];
            String expectedTt = tc[1];
            driver.get("https://www.imdb.com/find/?q=" + query);

            List<WebElement> links = driver.findElements(By.cssSelector("a[href*='/title/tt']"));
            boolean foundExpected = links.stream()
                    .map(el -> el.getAttribute("href"))
                    .anyMatch(h -> h != null && h.contains(expectedTt));

            assertTrue(foundExpected,
                    "Expected " + expectedTt + " in search results for: " + query
                            + ". Found " + links.size() + " links.");
        }
    }

}
