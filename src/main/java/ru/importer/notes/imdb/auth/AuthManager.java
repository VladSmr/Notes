package ru.importer.notes.imdb.auth;

import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Service;
import ru.importer.notes.util.ChromeProcessKiller;
import ru.importer.notes.util.WorkingDir;

@Service
@Slf4j
public class AuthManager {

    private final ChromeProcessKiller chromeProcessKiller;
    private WebDriver driver;

    public AuthManager(ChromeProcessKiller chromeProcessKiller) {
        this.chromeProcessKiller = chromeProcessKiller;
    }

    /**
     * Корректно закрывает текущий WebDriver, освобождая профиль Chrome.
     * Безопасно вызывать многократно: нет драйвера — ничего не делает.
     */
    public synchronized void closeDriver() {
        WebDriver current = driver;
        driver = null;
        if (current == null) {
            return;
        }
        try {
            current.quit();
            log.info("WebDriver закрыт, профиль Chrome освобождён");
        } catch (Exception e) {
            // Драйвер мог быть уже закрыт (окно закрыто вручную, крэш браузера) — это не ошибка.
            log.warn("WebDriver уже был закрыт (или закрытие не удалось): {}", e.getMessage());
        }
    }

    /**
     * Текущий экземпляр WebDriver.
     */
    public WebDriver getDriver() {
        return driver;
    }

    /**
     * Выполнен ли вход в аккаунт IMDB.
     */
    public boolean isLoggedIn() {
        if (driver == null) {
            return false;
        }
        try {
            driver.get("https://www.imdb.com");
            List<WebElement> userMenus = driver.findElements(
                    By.cssSelector(".imdb-header__account-toggle, #navUserMenu, [data-testid=\"nav-user-menu\"]")
            );
            return !userMenus.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Открывает Chrome с пользовательским профилем; живой драйвер и висящий Chrome
     * с нашим профилем предварительно закрываются (разблокировка профиля).
     *
     * @return экземпляр WebDriver
     */
    public synchronized WebDriver openBrowserAndWaitLogin() {
        // Разблокировка профиля: закрытие живого драйвера + висящего Chrome с нашим профилем.
        releaseBrowserProfile();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--blink-settings=imagesEnabled=false");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        // Храним профиль Chrome в рабочей директории приложения (%USERPROFILE%\KP-IMDB-Importer),
        // а не рядом с exe — в Program Files писать нельзя.
        String userDataDir = WorkingDir.chromeProfileDir().toString();
        options.addArguments("--user-data-dir=" + userDataDir);

        driver = new ChromeDriver(options);

        log.info("New Chrome window opened");
        return driver;
    }

    /**
     * Разблокировка профиля Chrome: {@code driver.quit()}, затем принудительное
     * завершение висящего chrome.exe с нашим профилем (личный Chrome не затрагивается).
     */
    private void releaseBrowserProfile() {
        // (1) Корректное закрытие через Selenium (если драйвер жив).
        closeDriver();
        // (2)+(3) Проверка висящего Chrome-процесса с нашим профилем и его завершение.
        // Не должно уронить ни открытие браузера, ни остановку приложения.
        try {
            chromeProcessKiller.killStaleChromeProcesses();
        } catch (RuntimeException e) {
            log.warn("Принудительное завершение висящего Chrome не удалось: {}", e.getMessage());
        }
    }

    /**
     * Закрывает браузер при остановке приложения ({@code @PreDestroy}), чтобы профиль
     * Chrome не остался заблокированным: {@code driver.quit()} → висящий Chrome → taskkill.
     */
    @PreDestroy
    public void shutdown() {
        releaseBrowserProfile();
    }

}
