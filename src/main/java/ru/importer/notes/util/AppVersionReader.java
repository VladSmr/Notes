package ru.importer.notes.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Читает версию приложения из стандартных Maven-метаданных в classpath:
 * {@code META-INF/maven/{groupId}/{artifactId}/pom.properties} — этот файл
 * генерирует maven-jar-plugin при сборке, он попадает в итоговый jar.
 * Если метаданных нет (например, запуск из IDE, где classpath — target/classes
 * без META-INF/maven) или ключ version отсутствует/пуст — возвращается
 * плейсхолдер {@link #UNKNOWN_VERSION}. Файловая система вне classpath не используется.
 */
public final class AppVersionReader {

    /** Путь к Maven-метаданным проекта в classpath (groupId/artifactId из pom.xml). */
    private static final String POM_PROPERTIES_RESOURCE =
            "/META-INF/maven/ru.importer/notes/pom.properties";

    /** Плейсхолдер версии, если метаданные недоступны (например, запуск из IDE). */
    public static final String UNKNOWN_VERSION = "dev";

    private AppVersionReader() {
    }

    /** Версия приложения из Maven-метаданных или {@link #UNKNOWN_VERSION}. */
    public static String readVersion() {
        return readVersion(POM_PROPERTIES_RESOURCE);
    }

    /**
     * Читает ключ {@code version} из properties-ресурса в classpath.
     *
     * @param resourcePath путь к ресурсу в classpath (с ведущим «/»)
     * @return значение version или {@link #UNKNOWN_VERSION}, если ресурса нет,
     *         ключа нет или он пуст
     */
    static String readVersion(String resourcePath) {
        Properties props = new Properties();
        try (InputStream in = AppVersionReader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return UNKNOWN_VERSION;
            }
            props.load(in);
        } catch (IOException e) {
            return UNKNOWN_VERSION;
        }
        String version = props.getProperty("version");
        if (version == null || version.isBlank()) {
            return UNKNOWN_VERSION;
        }
        return version.strip();
    }
}
