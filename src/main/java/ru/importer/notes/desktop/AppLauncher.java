package ru.importer.notes.desktop;

/**
 * Запускатель десктоп-приложения для jpackage-билда: когда JavaFX на classpath
 * (а не на module-path), прямой вызов {@link DesktopLauncher#main} падает с ошибкой
 * «JavaFX runtime components are missing». Обход — через
 * {@code com.sun.javafx.application.LauncherImpl.launchApplication}, минующий проверку.
 * Указывается как {@code --main-class} в jpackage.
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
