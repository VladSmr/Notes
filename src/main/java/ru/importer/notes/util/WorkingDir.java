package ru.importer.notes.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Рабочая директория приложения для файлов результатов, логов и chrome-profile:
 * {@code %USERPROFILE%\KP-IMDB-Importer} (в Program Files писать нельзя).
 * Значение лишь подставляется как дефолт в поля ввода.
 */
public final class WorkingDir {

    private static final String APP_DIR_NAME = "KP-IMDB-Importer";

    private WorkingDir() {
    }

    /** Рабочая директория по умолчанию: {@code %USERPROFILE%\KP-IMDB-Importer}. */
    public static String defaultWorkingDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            // Крайний случай: нет user.home — падаем на текущую директорию.
            return Paths.get(APP_DIR_NAME).toAbsolutePath().toString();
        }
        return Paths.get(userHome, APP_DIR_NAME).toAbsolutePath().toString();
    }

    /** Директория chrome-profile внутри рабочей директории приложения. */
    public static Path chromeProfileDir() {
        return Paths.get(defaultWorkingDir(), "chrome-profile");
    }
}
