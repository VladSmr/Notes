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
 * Unit-тесты жизненного цикла WebDriver в {@link AuthManager}: ChromeDriver перехватывается
 * {@link MockedConstruction}, {@link ChromeProcessKiller} мокается — реальный Chrome и
 * taskkill не запускаются.
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

    @Test
    void repeatedOpenBrowser_closesPreviousDriverBeforeOpeningNew() {
        AuthManager authManager = newAuthManager(mock(ChromeProcessKiller.class));
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver first = authManager.openBrowserAndWaitLogin();
            WebDriver second = authManager.openBrowserAndWaitLogin();

            assertNotSame(first, second);
            verify(first).quit();
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

    @Test
    void openBrowser_runsQuitBeforeForceKillInOrder() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            WebDriver first = authManager.openBrowserAndWaitLogin();
            WebDriver second = authManager.openBrowserAndWaitLogin();

            InOrder inOrder = inOrder(first, killer);
            inOrder.verify(first).quit();
            inOrder.verify(killer).killStaleChromeProcesses();
            assertSame(second, authManager.getDriver());
        }
    }

    @Test
    void openBrowserWithoutPreviousDriver_stillForciblyKillsStaleChrome() {
        // Даже без живого драйвера открытие браузера разблокирует профиль принудительным завершением.
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);
        AuthManager authManager = newAuthManager(killer);
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            authManager.openBrowserAndWaitLogin();

            verify(killer).killStaleChromeProcesses();
            assertNotNull(authManager.getDriver());
        }
    }

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
