package ru.importer.notes.imdb.auth;

import jakarta.annotation.PreDestroy;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.util.ChromeProcessKiller;
import ru.importer.notes.util.WorkingDir;

@Service
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private WebDriver driver;

    private final ChromeProcessKiller chromeProcessKiller;

    public AuthManager(ChromeProcessKiller chromeProcessKiller) {
        this.chromeProcessKiller = chromeProcessKiller;
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
            List<WebElement> userMenus = driver.findElements(
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
     * Если текущий драйвер ещё жив — сначала закрывает его, чтобы два Chrome
     * не конфликтовали за один профиль (--user-data-dir).
     * Перед открытием нового браузера дополнительно принудительно завершается висящий
     * Chrome-процесс с НАШИМ профилем — если предыдущий запуск упал жёстко и
     * {@code driver.quit()} не успел выполниться, профиль остаётся заблокированным.
     * Оба этапа (парсинг и проставление) проходят через этот метод, поэтому
     * разблокировка профиля покрывает оба сценария «Открыть браузер».
     *
     * @return экземпляр WebDriver
     */
    public synchronized WebDriver openBrowserAndWaitLogin() {
        // Разблокировка профиля перед new ChromeDriver(...): корректное закрытие живого
        // драйвера + принудительное завершение висящего Chrome с нашим профилем.
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
     * Последовательность разблокировки профиля Chrome:
     * <ol>
     *   <li>{@code driver.quit()} — корректное закрытие через Selenium;</li>
     *   <li>поиск висящего процесса chrome.exe, в командной строке которого есть путь
     *       к НАШЕМУ {@code chrome-profile} (после жёсткого падения {@code quit()} не
     *       успевает выполниться и профиль остаётся заблокированным);</li>
     *   <li>если такой процесс найден — принудительное завершение через
     *       {@code taskkill /F /PID}. Личный Chrome пользователя не затрагивается:
     *       ищется только точное совпадение пути к нашему профилю.</li>
     * </ol>
     * Вызывается в двух местах: перед открытием браузера
     * ({@link #openBrowserAndWaitLogin()}) и при остановке приложения ({@link #shutdown()}).
     * После каждого этапа парсинга/проставления выполняется только {@link #closeDriver()} —
     * принудительное завершение там не нужно.
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
     * Корректно закрывает текущий WebDriver ({@code driver.quit()}), освобождая
     * профиль Chrome (снимает блокировку {@code --user-data-dir}).
     * Безопасно вызывать многократно: если драйвер не создан или уже закрыт —
     * ничего не делает и не падает.
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
     * Закрывает браузер при остановке приложения (закрытие десктоп-окна →
     * {@code SpringApplication.exit()} → закрытие Spring-контекста → {@code @PreDestroy},
     * а также при остановке Spring-контекста в dev-режиме), чтобы профиль Chrome
     * не оставался заблокированным. Выполняет полную последовательность:
     * {@code driver.quit()} → проверка висящего Chrome с нашим профилем → taskkill.
     */
    @PreDestroy
    public void shutdown() {
        releaseBrowserProfile();
    }

}
