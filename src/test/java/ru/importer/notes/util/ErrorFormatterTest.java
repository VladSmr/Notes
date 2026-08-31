package ru.importer.notes.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-тесты полного текста ошибки: класс + сообщение + stack trace + цепочка cause
 * (используется для лога и показа пользователю в веб-интерфейсе).
 */
class ErrorFormatterTest {

    @Test
    void format_returnsNullForNull() {
        assertNull(ErrorFormatter.format(null));
    }

    @Test
    void format_containsExceptionClassAndMessage() {
        String text = ErrorFormatter.format(new IllegalStateException("ошибка проставления оценки"));
        assertTrue(text.startsWith("java.lang.IllegalStateException: ошибка проставления оценки"));
        assertTrue(text.contains("at ru.importer.notes.util.ErrorFormatterTest.format_containsExceptionClassAndMessage"));
    }

    @Test
    void format_containsStackTraceOfCauseChain() {
        RuntimeException outer = new RuntimeException("outer", new IllegalArgumentException("inner cause"));
        String text = ErrorFormatter.format(outer);
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: inner cause"));
        assertTrue(text.contains("at ru.importer.notes.util.ErrorFormatterTest.format_containsStackTraceOfCauseChain"));
        assertFalse(text.isBlank());
    }
}
