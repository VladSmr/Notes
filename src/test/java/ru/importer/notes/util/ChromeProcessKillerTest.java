package ru.importer.notes.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.importer.notes.util.ChromeProcessKiller.ChromeProcess;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты поиска и принудительного завершения висящего Chrome-процесса с НАШИМ
 * профилем ({@link ChromeProcessKiller}).
 *
 * <p>Реальные {@code taskkill}/перебор процессов не запускаются: швы
 * {@code findChromeProcesses()} и {@code killPid(long)} перехватываются Mockito-spy.</p>
 */
class ChromeProcessKillerTest {

    private static final Path PROFILE =
            Paths.get("C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile");

    /** Командная строка личного Chrome пользователя (другой профиль) — его трогать нельзя. */
    private static final String PERSONAL_CHROME_CMD =
            "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" "
                    + "--profile-directory=\"C:\\Users\\User\\AppData\\Local\\Google\\Chrome\\User Data\\Default\"";

    /** Процесс нашего импортёра: наш --user-data-dir в командной строке. */
    private static final String OUR_CHROME_CMD =
            "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" "
                    + "--user-data-dir=\"C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile\" "
                    + "--remote-allow-origins=*";

    // ------------------------------------------------------------------
    // Убивается ТОЛЬКО наш процесс (по пути chrome-profile), не все chrome.exe
    // ------------------------------------------------------------------

    @Test
    void killsOnlyProcessesWithOurProfileCommandLine() {
        ChromeProcessKiller killer = spyChromeKiller(List.of(
                new ChromeProcess(101, OUR_CHROME_CMD),
                new ChromeProcess(202, PERSONAL_CHROME_CMD)
        ));

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(1, killed);
        verify(killer).killPid(101L);
        verify(killer, never()).killPid(202L);
    }

    /** Похожий путь (chrome-profile2) не должен считаться нашим профилем. */
    @Test
    void doesNotKillProcessWithSimilarButDifferentProfilePath() {
        ChromeProcessKiller killer = spyChromeKiller(List.of(
                new ChromeProcess(303,
                        "\"C:\\...\\chrome.exe\" --user-data-dir=\"C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile2\"")
        ));

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(0, killed);
        verify(killer, never()).killPid(anyLong());
    }

    @Test
    void noProcesses_nothingKilled() {
        ChromeProcessKiller killer = spyChromeKiller(List.of());

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(0, killed);
        verify(killer, never()).killPid(anyLong());
    }

    @Test
    void processWithoutCommandLine_isIgnored() {
        ChromeProcessKiller killer = spyChromeKiller(List.of(
                new ChromeProcess(404, "")
        ));

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(0, killed);
        verify(killer, never()).killPid(anyLong());
    }

    // ------------------------------------------------------------------
    // Устойчивость к ошибкам taskkill / перечисления процессов
    // ------------------------------------------------------------------

    /** taskkill не смог завершить один процесс — остальные всё равно обрабатываются, исключения нет. */
    @Test
    void taskkillFailure_oneProcess_otherProcessesStillProcessed() {
        ChromeProcessKiller killer = spyChromeKiller(List.of(
                new ChromeProcess(101, OUR_CHROME_CMD),
                new ChromeProcess(102, OUR_CHROME_CMD)
        ));
        doReturn(false).when(killer).killPid(101L); // taskkill вернул ошибку
        doReturn(true).when(killer).killPid(102L);

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(1, killed);
        verify(killer).killPid(101L);
        verify(killer).killPid(102L);
    }

    /** Убойный сценарий: taskkill бросает исключение — метод не должен упасть и обработать остальные. */
    @Test
    void taskkillThrows_noPropagation() {
        ChromeProcessKiller killer = spyChromeKiller(List.of(
                new ChromeProcess(101, OUR_CHROME_CMD),
                new ChromeProcess(102, OUR_CHROME_CMD)
        ));
        doThrow(new RuntimeException("Access denied")).when(killer).killPid(101L);

        int killed = assertDoesNotThrow(() -> killer.killStaleChromeProcesses(PROFILE));

        // Первый PID упал, второй всё равно обработан.
        assertEquals(1, killed);
        verify(killer).killPid(101L);
        verify(killer).killPid(102L);
    }

    /** Сбой перечисления процессов (ProcessHandle) не должен приводить к исключению. */
    @Test
    void findChromeProcessesFails_returnsZeroWithoutThrow() {
        ChromeProcessKiller killer = spyChromeKiller(List.of());
        doThrow(new RuntimeException("process enumeration failed")).when(killer).findChromeProcesses();

        int killed = killer.killStaleChromeProcesses(PROFILE);

        assertEquals(0, killed);
        verify(killer, never()).killPid(anyLong());
    }

    // ------------------------------------------------------------------
    // Логика сопоставления командной строки с путём профиля
    // ------------------------------------------------------------------

    @Test
    void matchesProfile_isCaseInsensitive() {
        String needle = ChromeProcessKiller.normalize(
                "C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile");

        assertTrue(ChromeProcessKiller.matchesProfile(
                "--user-data-dir=C:\\users\\USER\\kp-imdb-importer\\CHROME-PROFILE", needle));
        assertTrue(ChromeProcessKiller.matchesProfile(OUR_CHROME_CMD, needle));
    }

    @Test
    void matchesProfile_acceptsQuotedAndTrailingPosition() {
        String needle = ChromeProcessKiller.normalize(
                "C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile");

        // Путь в кавычках (пробелы в пути пользователя).
        assertTrue(ChromeProcessKiller.matchesProfile(
                "--user-data-dir=\"C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile\"", needle));
        // Путь в конце строки.
        assertTrue(ChromeProcessKiller.matchesProfile(
                "chrome.exe --user-data-dir=C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile", needle));
        // Пробелы в пути пользователя.
        assertTrue(ChromeProcessKiller.matchesProfile(
                "--user-data-dir=\"C:\\Users\\User_Name\\KP-IMDB-Importer\\chrome-profile\"",
                ChromeProcessKiller.normalize("C:\\Users\\User_Name\\KP-IMDB-Importer\\chrome-profile")));
    }

    @Test
    void matchesProfile_rejectsForeignCommandLines() {
        String needle = ChromeProcessKiller.normalize(
                "C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile");

        // Похожий путь: chrome-profile2 не совпадает с chrome-profile.
        assertFalse(ChromeProcessKiller.matchesProfile(
                "--user-data-dir=C:\\Users\\User\\KP-IMDB-Importer\\chrome-profile2", needle));
        // Личный Chrome.
        assertFalse(ChromeProcessKiller.matchesProfile(PERSONAL_CHROME_CMD, needle));
        // null/пустые входы.
        assertFalse(ChromeProcessKiller.matchesProfile(null, needle));
        assertFalse(ChromeProcessKiller.matchesProfile("", needle));
        assertFalse(ChromeProcessKiller.matchesProfile("chrome.exe", ""));
    }

    // ------------------------------------------------------------------
    // Помощники
    // ------------------------------------------------------------------

    /** Spy-киллер с подменённым списком процессов: реальный ProcessHandle/taskkill не используется. */
    private static ChromeProcessKiller spyChromeKiller(List<ChromeProcess> processes) {
        ChromeProcessKiller killer = org.mockito.Mockito.spy(new ChromeProcessKiller());
        doReturn(processes).when(killer).findChromeProcesses();
        // По умолчанию killPid «успешен»; конкретные тесты переопределяют при необходимости.
        doReturn(true).when(killer).killPid(anyLong());
        return killer;
    }

}
