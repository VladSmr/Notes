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

    /**
     * Возвращает полный текст исключения: первой строкой {@code e.toString()}
     * (класс и сообщение), далее — полный stack trace и причина (cause).
     *
     * @param e исключение или {@code null}
     * @return многострочный текст исключения или {@code null}, если {@code e == null}
     */
    public static String format(Throwable e) {
        if (e == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
