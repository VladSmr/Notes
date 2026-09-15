package ru.importer.notes.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-тесты чтения версии приложения из Maven-метаданных в classpath:
 * ветка «метаданные есть» и обе ветки fallback-плейсхолдера.
 *
 * Для ветки «метаданные есть» в test-resources лежит заглушка по тому же пути,
 * что и в production ({@code META-INF/maven/ru.importer/notes/pom.properties}),
 * поэтому тест заодно проверяет, что production-путь соответствует GAV из pom.xml.
 */
class AppVersionReaderTest {

    @Test
    void readVersion_returnsVersionFromMavenMetadata() {
        // Заглушка: src/test/resources/META-INF/maven/ru.importer/notes/pom.properties
        assertEquals("9.9.9-test", AppVersionReader.readVersion());
    }

    @Test
    void readVersion_fallsBackToPlaceholderWhenResourceMissing() {
        assertEquals(AppVersionReader.UNKNOWN_VERSION, AppVersionReader.readVersion(
                "/META-INF/maven/nonexistent/group/artifact/pom.properties"));
    }

    @Test
    void readVersion_fallsBackToPlaceholderWhenVersionBlank() {
        // Ресурс есть, но ключ version пуст.
        assertEquals(AppVersionReader.UNKNOWN_VERSION, AppVersionReader.readVersion(
                "/META-INF/maven/test/no-version/pom.properties"));
    }
}
