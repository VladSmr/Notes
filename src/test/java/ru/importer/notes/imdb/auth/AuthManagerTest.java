package ru.importer.notes.imdb.auth;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import ru.importer.notes.util.ChromeProcessKiller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты управления жизненным циклом WebDriver в {@link AuthManager}.
 * ChromeDriver создаётся через {@code new} — в тестах конструкция перехватывается
 * {@link MockedConstruction}, поэтому реальные Chrome/chromedriver не запускаются.
 *
 * <p>{@link ChromeProcessKiller} всегда мокается — реальный {@code taskkill}/перебор
 * процессов в тестах не выполняется.</p>
 */
class AuthManagerTest {

    /** AuthManager с замоканным киллером процессов (без реального taskkill). */
    private static AuthManager newAuthManager(ChromeProcessKiller killer) {
        return new AuthManager(killer);
    }

    @Test
    void closeDriver_withoutDriver_doesNotThrow() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));

        assertDoesNotThrow(authManager::closeDriver);
        assertSame(null, authManager.getDriver());
    }

    /**
     * Повторный вызов openBrowserAndWaitLogin должен СНАЧАЛА закрыть предыдущий драйвер
     * (driver.quit()), и только потом открыть новый — иначе два Chrome конфликтуют
     * за один профиль --user-data-dir.
     */
    @Test
    void repeatedOpenBrowser_closesPreviousDriverBeforeOpeningNew() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver first = authManager.openBrowserAndWaitLogin();
            WebDriver second = authManager.openBrowserAndWaitLogin();

            assertNotSame(first, second);
            // Предыдущий драйвер корректно закрыт.
            verify(first).quit();
            // В AuthManager теперь живёт новый драйвер.
            assertSame(second, authManager.getDriver());
        }
    }

    @Test
    void closeDriver_quitsDriverAndClearsReference() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();

            authManager.closeDriver();

            verify(driver).quit();
            assertSame(null, authManager.getDriver());
        }
    }

    /** Если драйвер уже закрыт (окно закрыто вручную, крэш) — quit() бросает, closeDriver не должен падать. */
    @Test
    void closeDriver_whenQuitFails_doesNotThrowAndClearsReference() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();
            doThrow(new RuntimeException("Session already closed")).when(driver).quit();

            assertDoesNotThrow(authManager::closeDriver);
            assertSame(null, authManager.getDriver());
        }
    }

    /** Остановка приложения (@PreDestroy) тоже должна закрывать браузер. */
    @Test
    void shutdown_closesDriver() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();

            authManager.shutdown();

            verify(driver).quit();
            assertSame(null, authManager.getDriver());
        }
    }

    // ------------------------------------------------------------------
    // Последовательность: driver.quit() → проверка висящего Chrome → taskkill
    // ------------------------------------------------------------------

    /**
     * При открытии браузера выполняется полная последовательность: сначала quit()
     * предыдущего драйвера, затем принудительное завершение висящего Chrome с нашим
     * профилем. Точка применения №1 («Открыть браузер» — и парсинг, и проставление).
     */
    @Test
    void openBrowser_runsQuitBeforeForceKillInOrder() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver first = authManager.openBrowserAndWaitLogin();
            WebDriver second = authManager.openBrowserAndWaitLogin();

            // Порядок: (1) quit() предыдущего драйвера → (2)+(3) поиск/принудительное завершение.
            InOrder inOrder = inOrder(first, killer);
            inOrder.verify(first).quit();
            inOrder.verify(killer).killStaleChromeProcesses();
            assertSame(second, authManager.getDriver());
        }
    }

    /**
     * Даже если драйвера нет (жёсткое падение — ссылка потеряна, а chrome.exe остался),
     * открытие браузера всё равно запускает проверку/принудительное завершение:
     * именно этот сценарий разблокирует профиль перед {@code new ChromeDriver(...)}.
     */
    @Test
    void openBrowserWithoutPreviousDriver_stillForciblyKillsStaleChrome() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            authManager.openBrowserAndWaitLogin();

            verify(killer).killStaleChromeProcesses();
            assertNotNull(authManager.getDriver());
        }
    }

    /**
     * Точечное закрытие после этапа парсинга/проставления (Processor вызывает
     * closeDriver() в finally) НЕ должно запускать принудительное завершение —
     * последовательность quit → проверка → taskkill выполняется только в двух местах:
     * перед открытием браузера и при выходе из приложения.
     */
    @Test
    void closeDriver_directCall_doesNotForceKillProcess() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            authManager.openBrowserAndWaitLogin();

            // Изолируем вызов kill из openBrowserAndWaitLogin.
            clearInvocations(killer);

            authManager.closeDriver();

            verify(killer, never()).killStaleChromeProcesses();
        }
    }

    /**
     * Точка применения №2: при закрытии приложения (shutdown → @PreDestroy) порядок
     * строго quit() → принудительное завершение.
     */
    @Test
    void shutdown_runsQuitThenForceKillInOrder() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();
            clearInvocations(killer);

            authManager.shutdown();

            InOrder inOrder = inOrder(driver, killer);
            inOrder.verify(driver).quit();
            inOrder.verify(killer).killStaleChromeProcesses();
            assertSame(null, authManager.getDriver());
        }
    }

    /** Сбой принудительного завершения не должен мешать открытию браузера. */
    @Test
    void openBrowser_whenForceKillFails_stillOpensBrowser() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        doThrow(new RuntimeException("taskkill failed")).when(killer).killStaleChromeProcesses();
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            assertDoesNotThrow(authManager::openBrowserAndWaitLogin);
            assertNotNull(authManager.getDriver());
        }
    }

    /** Сбой принудительного завершения не должен мешать остановке приложения (@PreDestroy). */
    @Test
    void shutdown_whenForceKillFails_doesNotThrowAndQuitsDriver() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        doThrow(new RuntimeException("taskkill failed")).when(killer).killStaleChromeProcesses();
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver driver = authManager.openBrowserAndWaitLogin();

            assertDoesNotThrow(authManager::shutdown);
            verify(driver).quit();
            assertSame(null, authManager.getDriver());
        }
    }

}
