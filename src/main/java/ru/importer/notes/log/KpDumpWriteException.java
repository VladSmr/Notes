package ru.importer.notes.log;

/**
 * Unchecked-исключение записи дампа {@code kp-ratings.csv}.
 * <p>
 * Оборачивает исходную {@link java.io.IOException} (или иную причину) с сохранённым
 * {@code cause}, чтобы вызывающий мог классифицировать ошибку: «файл занят другим
 * процессом» против «директория недоступна / нет прав на запись».
 */
public class KpDumpWriteException extends RuntimeException {

    public KpDumpWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
