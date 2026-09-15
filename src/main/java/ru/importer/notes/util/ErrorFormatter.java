package ru.importer.notes.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Формирует ПОЛНЫЙ текст исключения (класс + сообщение + stack trace, включая цепочку cause)
 * для лог-файла и для показа пользователю в веб-интерфейсе.
 */
public final class ErrorFormatter {

    private ErrorFormatter() {
    }

    /** Полный текст исключения ({@code e.toString()} + stack trace) или null. */
    public static String format(Throwable e) {
        if (e == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
