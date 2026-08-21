package ru.importer.notes.movie;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import org.junit.jupiter.api.Test;
import ru.importer.notes.log.KpDumpWriteException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-тесты классификации ошибок записи дампа: «файл занят другим процессом»
 * против «директория недоступна / нет прав на запись».
 */
class ProcessorErrorClassificationTest {

    @Test
    void accessDeniedException_isPermissionOrDirError() {
        assertTrue(Processor.isPermissionOrDirError(new AccessDeniedException("D:\\notes\\kp-ratings.csv")));
    }

    @Test
    void accessDeniedWrappedInKpDumpWriteException_isPermissionOrDirError() {
        KpDumpWriteException ex = new KpDumpWriteException(
                "Не удалось записать временный файл дампа",
                new AccessDeniedException("D:\\notes\\kp-ratings.csv.tmp"));
        assertTrue(Processor.isPermissionOrDirError(ex));
    }

    @Test
    void fileNotFoundAccessDeniedMessage_isPermissionOrDirError() {
        // Windows: «Отказано в доступе» при отсутствии прав на запись.
        FileNotFoundException ex = new FileNotFoundException(
                "D:\\notes\\kp-ratings.csv.tmp (Отказано в доступе)");
        assertTrue(Processor.isPermissionOrDirError(ex));
    }

    @Test
    void fileNotFoundPlain_isNotPermissionOrDirError() {
        // FileNotFoundException без «отказано в доступе» — файл не найден, а не проблема прав.
        FileNotFoundException ex = new FileNotFoundException("D:\\notes\\kp-ratings.csv");
        assertFalse(Processor.isPermissionOrDirError(ex));
    }

    @Test
    void ioExceptionBeingUsedByAnotherProcess_isNotPermissionOrDirError() {
        IOException ex = new IOException("The process cannot access the file because it is being used by another process.");
        assertFalse(Processor.isPermissionOrDirError(ex));
    }

    @Test
    void ioExceptionAccessDenied_isPermissionOrDirError() {
        IOException ex = new IOException("D:\\notes\\kp-ratings.csv (Отказано в доступе)");
        assertTrue(Processor.isPermissionOrDirError(ex));
    }

    @Test
    void genericException_isNotPermissionOrDirError() {
        assertFalse(Processor.isPermissionOrDirError(new RuntimeException("something else")));
    }

    @Test
    void nestedCauseIsInspected() {
        IOException root = new IOException("D:\\notes (Отказано в доступе)");
        RuntimeException mid = new RuntimeException("wrap", root);
        KpDumpWriteException ex = new KpDumpWriteException("top", mid);
        assertTrue(Processor.isPermissionOrDirError(ex));
    }
}
