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
 * Проверяет привязку последовательности «quit → поиск висящего Chrome → taskkill»
 * к закрытию Spring-контекста: именно так завершается приложение при закрытии
 * десктоп-окна ({@code DesktopLauncher.shutdownSpring()} → {@code SpringApplication.exit()}
 * → закрытие контекста → {@code @PreDestroy AuthManager.shutdown()}).
 *
 * <p>Лёгкий Spring-контекст (без {@code @SpringBootTest}): реальный Tomcat/Chrome
 * не поднимаются, {@link ChromeProcessKiller} мокается — реальный taskkill в тестах
 * не выполняется.</p>
 */
class AuthManagerShutdownLifecycleTest {

    /**
     * Закрытие Spring-контекста (то же, что при закрытии десктоп-окна) должно
     * выполнить последовательность: driver.quit() → принудительное завершение
     * висящего Chrome с нашим профилем.
     */
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

        // Закрытие контекста = то же, что происходит при закрытии десктоп-окна.
        AuthManager authManager = context.getBean(AuthManager.class);
        context.close();

        // Строгий порядок: (1) quit() → (2)+(3) поиск/принудительное завершение.
        InOrder inOrder = inOrder(driver, killer);
        inOrder.verify(driver).quit();
        inOrder.verify(killer).killStaleChromeProcesses();

        // После закрытия контекста драйвер освобождён.
        org.junit.jupiter.api.Assertions.assertNull(authManager.getDriver());
    }

}
