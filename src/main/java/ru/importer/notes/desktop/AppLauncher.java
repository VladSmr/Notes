package ru.importer.notes.desktop;

/**
 * Обёртка-запускатель десктоп-приложения для упакованного (jpackage) билда.
 *
 * <p>Когда JavaFX лежит на classpath (а не на module-path) — например, при запуске из
 * thin-JAR с зависимостями в папке {@code app/*} — прямой вызов
 * {@link DesktopLauncher#main} приводит к ошибке
 * {@code "JavaFX runtime components are missing"}: {@code Application.launch} из
 * безымянного модуля отказывается стартовать без явного module-path.</p>
 *
 * <p>Обход: вызываем {@code com.sun.javafx.application.LauncherImpl.launchApplication},
 * который запускает {@link DesktopLauncher} (наследник {@code javafx.application.Application})
 * без этой проверки. Именно {@code AppLauncher} указывается как {@code --main-class}
 * в jpackage; {@link DesktopLauncher#main} остаётся для запуска из IDE/разработки.</p>
 */
public final class AppLauncher {

    private AppLauncher() {
    }

    public static void main(String[] args) throws Exception {
        // com.sun.* — внутренний API JavaFX; для jpackage-билда это штатный механизм.
        com.sun.javafx.application.LauncherImpl.launchApplication(
                DesktopLauncher.class, args);
    }
}
