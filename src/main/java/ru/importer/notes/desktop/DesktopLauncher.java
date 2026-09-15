package ru.importer.notes.desktop;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import ru.importer.notes.NotesApplication;
import ru.importer.notes.util.AppVersionReader;

/**
 * Десктоп-оболочка на JavaFX WebView: запускает Spring Boot в фоновом потоке, ждёт
 * готовности порта 8085 и открывает окно с WebView поверх существующего Thymeleaf-UI.
 * Главная точка входа для собранного .exe/.msi ({@link NotesApplication#main} — для разработки).
 */
@Slf4j
public class DesktopLauncher extends Application {

    private static final String BASE_URL = "http://localhost:8085/notes";
    private static final long STARTUP_TIMEOUT_SECONDS = 120;
    /**
     * Путь к иконке окна в classpath-ресурсах (PNG).
     */
    private static final String WINDOW_ICON_RESOURCE = "/icons/app.png";
    private ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        launch(DesktopLauncher.class, args);
    }

    /**
     * Применяет иконку окна из classpath-ресурса (до {@code primaryStage.show()}).
     * Если иконка не найдена/не загрузилась — только предупреждение, окно откроется
     * с системной иконкой.
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

    /**
     * Строит строку статуса внизу окна: версия приложения, прижата влево
     * (JavaFX-эквивалент Swing BorderLayout.PAGE_END + LINE_START: нижний регион
     * BorderPane + HBox с выравниванием по левому краю). Версия читается из
     * Maven-метаданных в classpath; при их отсутствии (запуск из IDE) — «dev».
     */
    private HBox buildStatusBar() {
        Label versionLabel = new Label("Версия " + AppVersionReader.readVersion());
        HBox statusBar = new HBox(versionLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        return statusBar;
    }

    /**
     * Строит главное окно с WebView.
     */
    private void buildWindow(Stage primaryStage) {
        BorderPane root = new BorderPane();

        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        engine.load(BASE_URL);

        root.setCenter(webView);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 820);
        primaryStage.setTitle("KP → IMDB Importer");
        primaryStage.setScene(scene);

        // Иконка окна; грузим безопасно — при отсутствии/битье ресурса только предупреждение.
        applyWindowIcon(primaryStage);

        // Центрируем окно на основном экране.
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        primaryStage.setX((bounds.getWidth() - 1280) / 2 + bounds.getMinX());
        primaryStage.setY((bounds.getHeight() - 820) / 2 + bounds.getMinY());

        // Закрытие окна → graceful shutdown Spring, чтобы процесс не висел в фоне.
        primaryStage.setOnCloseRequest(event -> shutdownSpring());
        primaryStage.setOnHidden(event -> shutdownSpring());
    }

    private boolean ping(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            // 200 и 3xx (редирект на /notes/) — признак того, что сервер поднялся.
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Показывает сообщение об ошибке и завершает приложение.
     */
    private void showErrorAndExit(String message) {
        log.error(message);
        // Алерт показывается и дожидается закрытия ДО завершения Spring/JavaFX-toolkit:
        // иначе toolkit завершится раньше, чем алерт успеет отобразиться.
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

    /**
     * Корректно завершает Spring-контекст. Дальше цепочка разблокировки профиля Chrome
     * ({@code driver.quit()} → висящий Chrome → taskkill) выполняется сама через
     * {@code @PreDestroy} {@link ru.importer.notes.imdb.auth.AuthManager} — здесь её
     * дублировать не нужно.
     */
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

        // SpringApplication.run может вернуться до полного старта встроенного Tomcat.
        if (!waitForHttpReady()) {
            showErrorAndExit("HTTP-сервер на " + BASE_URL + " не стал доступен за "
                                     + STARTUP_TIMEOUT_SECONDS + " с");
            return;
        }

        buildWindow(primaryStage);
        primaryStage.show();
    }

    @Override
    public void stop() {
        shutdownSpring();
    }

    /**
     * Ожидает готовности HTTP-порта.
     */
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

}
