package ru.importer.notes.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Определяет рабочую директорию приложения для файлов результатов (kp-ratings.csv),
 * логов и chrome-profile.
 *
 * <p>В установленной версии (ярлык/десктоп-окно) программа ставится в Program Files,
 * куда писать нельзя, поэтому все рабочие файлы живут в
 * {@code %USERPROFILE%\KP-IMDB-Importer} (т.е. {@code C:\Users\<user>\KP-IMDB-Importer}).</p>
 *
 * <p>Пользователь по-прежнему может указать собственную директорию на форме — это значение
 * лишь подставляется как дефолт в поля ввода.</p>
 */
public final class WorkingDir {

    private static final String APP_DIR_NAME = "KP-IMDB-Importer";

    private WorkingDir() {
    }

    /**
     * @return путь к рабочей директории приложения по умолчанию:
     *         {@code %USERPROFILE%\KP-IMDB-Importer}
     */
    public static String defaultWorkingDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            // Крайний случай: нет user.home — падаем на текущую директорию.
            return Paths.get(APP_DIR_NAME).toAbsolutePath().toString();
        }
        return Paths.get(userHome, APP_DIR_NAME).toAbsolutePath().toString();
    }

    /**
     * @return путь к директории chrome-profile внутри рабочей директории приложения.
     */
    public static Path chromeProfileDir() {
        return Paths.get(defaultWorkingDir(), "chrome-profile");
    }
}
