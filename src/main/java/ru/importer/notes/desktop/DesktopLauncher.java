package ru.importer.notes.desktop;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import ru.importer.notes.NotesApplication;

/**
 * Десктоп-оболочка приложения на JavaFX WebView.
 *
 * <p>Запускает Spring Boot (тот же {@link NotesApplication}) в фоновом потоке, ждёт
 * готовности HTTP-порта 8085 и открывает окно с WebView, который рендерит существующий
 * Thymeleaf-UI (http://localhost:8085/notes) без адресной строки — «настоящее» десктоп-окно.</p>
 *
 * <p>Это главная точка входа для собранного .exe/.msi. {@link NotesApplication#main} остаётся
 * для разработки/тестов (запуск без окна).</p>
 */
public class DesktopLauncher extends Application {

    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private static final String BASE_URL = "http://localhost:8085/notes";
    private static final long STARTUP_TIMEOUT_SECONDS = 120;

    /** Путь к иконке окна в classpath-ресурсах (PNG). */
    private static final String WINDOW_ICON_RESOURCE = "/icons/app.png";

    private ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        launch(DesktopLauncher.class, args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Запускаем Spring Boot в отдельном потоке, чтобы не блокировать JavaFX-поток.
        AtomicReference<ConfigurableApplicationContext> ctxRef = new AtomicReference<>();
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread springThread = new Thread(() -> {
            try {
                ConfigurableApplicationContext ctx =
                        SpringApplication.run(NotesApplication.class, new String[0]);
                ctxRef.set(ctx);
                started.countDown();
            } catch (Throwable t) {
                startupError.set(t);
                started.countDown();
            }
        }, "spring-boot-startup");
        springThread.setDaemon(false);
        springThread.start();

        try {
            if (!started.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Spring Boot не поднялся за " + STARTUP_TIMEOUT_SECONDS + " с");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            showErrorAndExit("Прерывание при запуске Spring Boot: " + e.getMessage());
            return;
        }

        if (startupError.get() != null) {
            showErrorAndExit("Не удалось запустить Spring Boot: "
                    + startupError.get().getMessage());
            return;
        }

        this.springContext = ctxRef.get();

        // Ждём, пока HTTP-порт реально начнёт отвечать (SpringApplication.run может вернуться
        // до полного старта встроенного Tomcat при некоторых конфигурациях).
        if (!waitForHttpReady()) {
            showErrorAndExit("HTTP-сервер на " + BASE_URL + " не стал доступен за "
                    + STARTUP_TIMEOUT_SECONDS + " с");
            return;
        }

        buildWindow(primaryStage);
        primaryStage.show();
    }

    /** Строит главное окно с WebView. */
    private void buildWindow(Stage primaryStage) {
        BorderPane root = new BorderPane();

        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        engine.load(BASE_URL);

        root.setCenter(webView);

        Scene scene = new Scene(root, 1280, 820);
        primaryStage.setTitle("KP → IMDB Importer");
        primaryStage.setScene(scene);

        // Иконка окна (и в заголовке окна, и в таскбаре). Грузим из classpath-ресурсов
        // безопасно: если ресурс отсутствует или битый — просто логируем предупреждение,
        // но приложение не роняем.
        applyWindowIcon(primaryStage);

        // Центрируем окно на основном экране.
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        primaryStage.setX((bounds.getWidth() - 1280) / 2 + bounds.getMinX());
        primaryStage.setY((bounds.getHeight() - 820) / 2 + bounds.getMinY());

        // Закрытие окна → graceful shutdown Spring, чтобы процесс не висел в фоне.
        primaryStage.setOnCloseRequest(event -> shutdownSpring());
        primaryStage.setOnHidden(event -> shutdownSpring());
    }

    /**
     * Применяет иконку окна из classpath-ресурса.
     *
     * <p>Вызывается до {@code primaryStage.show()}, чтобы иконка отобразилась и в заголовке
     * окна, и в таскбаре. Если иконка не найдена или не загрузилась — только логируем
     * предупреждение и продолжаем работу (окно откроется с системной иконкой по умолчанию).</p>
     */
    private void applyWindowIcon(Stage primaryStage) {
        try {
            var iconUrl = getClass().getResource(WINDOW_ICON_RESOURCE);
            if (iconUrl == null) {
                log.warn("Иконка окна не найдена в ресурсах: {}", WINDOW_ICON_RESOURCE);
                return;
            }
            Image icon = new Image(iconUrl.toString());
            if (icon.isError()) {
                log.warn("Не удалось загрузить иконку окна {}: {}",
                        WINDOW_ICON_RESOURCE, icon.getException());
                return;
            }
            primaryStage.getIcons().add(icon);
            log.info("Иконка окна применена: {}", WINDOW_ICON_RESOURCE);
        } catch (RuntimeException e) {
            log.warn("Не удалось применить иконку окна {}: {}", WINDOW_ICON_RESOURCE,
                    e.getMessage(), e);
        }
    }

    /** Корректно завершает Spring-контекст (Tomcat, фоновые потоки). */
    private void shutdownSpring() {
        ConfigurableApplicationContext ctx = this.springContext;
        if (ctx != null) {
            log.info("Закрываю окно — выполняю graceful shutdown Spring");
            int exitCode = SpringApplication.exit(ctx, () -> 0);
            log.info("Spring-контекст закрыт, exitCode={}", exitCode);
            this.springContext = null;
        }
        Platform.exit();
    }

    /** Ожидает готовности HTTP-порта. */
    private boolean waitForHttpReady() {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (ping(BASE_URL)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean ping(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            // 200 и 3xx (редирект на /notes/) считаем признаком того, что сервер поднялся.
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /** Показывает сообщение об ошибке и завершает приложение. */
    private void showErrorAndExit(String message) {
        log.error(message);
        // Сначала показываем алерт и дожидаемся его закрытия, и только потом завершаем
        // Spring и JavaFX-toolkit. Если вызвать Platform.exit() сразу (до показа диалога),
        // toolkit завершится раньше, чем алерт успеет отобразиться, и пользователь увидит
        // лишь молчаливое закрытие окна без объяснения причины.
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Ошибка запуска");
            alert.setHeaderText("Не удалось запустить приложение");
            alert.setContentText(message);
            alert.showAndWait();
            shutdownSpring();
        });
    }

    @Override
    public void stop() {
        shutdownSpring();
    }
}
