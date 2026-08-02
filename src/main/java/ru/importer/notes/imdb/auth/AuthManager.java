package ru.importer.notes.imdb.auth;

import java.nio.file.Paths;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private WebDriver driver;

    /**
     * Закрывает браузер и освобождает ресурсы.
     */
    public void close() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }

    /**
     * Возвращает текущий экземпляр WebDriver.
     */
    public WebDriver getDriver() {
        return driver;
    }

    /**
     * Проверяет, выполнен ли вход в аккаунт IMDB.
     */
    public boolean isLoggedIn() {
        if (driver == null) {
            return false;
        }
        try {
            driver.get("https://www.imdb.com");
            java.util.List<org.openqa.selenium.WebElement> userMenus = driver.findElements(
                    By.cssSelector(".imdb-header__account-toggle, #navUserMenu, [data-testid=\"nav-user-menu\"]")
            );
            return !userMenus.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Открывает Chrome с пользовательским профилем.
     * Навигация по сайтам происходит отдельно — сначала КП, потом IMDB.
     *
     * @return экземпляр WebDriver
     */
    public WebDriver openBrowserAndWaitLogin() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        String userDataDir = Paths.get("chrome-profile").toAbsolutePath().toString();
        options.addArguments("--user-data-dir=" + userDataDir);

        driver = new ChromeDriver(options);

        log.info("New Chrome window opened");
        return driver;
    }

}
