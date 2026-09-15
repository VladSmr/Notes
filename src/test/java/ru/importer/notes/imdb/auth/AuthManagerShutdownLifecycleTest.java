package ru.importer.notes.imdb.auth;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.importer.notes.util.ChromeProcessKiller;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;

/**
 * Привязка последовательности «quit → поиск висящего Chrome → taskkill» к закрытию
 * Spring-контекста (так завершается приложение при закрытии десктоп-окна). Лёгкий
 * Spring-контекст без {@code @SpringBootTest}: Tomcat/Chrome не поднимаются,
 * {@link ChromeProcessKiller} мокается.
 */
class AuthManagerShutdownLifecycleTest {

    /** Закрытие Spring-контекста выполняет последовательность quit() → принудительное завершение. */
    @Test
    void springContextClose_runsQuitThenForceKillSequence() {
        ChromeProcessKiller killer = mock(ChromeProcessKiller.class);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ChromeProcessKiller.class, () -> killer);
        context.register(AuthManager.class);
        context.refresh();

        WebDriver driver;
        try (MockedConstruction<ChromeDriver> ignored = mockConstruction(ChromeDriver.class)) {
            driver = context.getBean(AuthManager.class).openBrowserAndWaitLogin();
        }

        // Закрытие контекста = то же, что при закрытии десктоп-окна.
        AuthManager authManager = context.getBean(AuthManager.class);
        context.close();

        InOrder inOrder = inOrder(driver, killer);
        inOrder.verify(driver).quit();
        inOrder.verify(killer).killStaleChromeProcesses();

        org.junit.jupiter.api.Assertions.assertNull(authManager.getDriver());
    }

}
