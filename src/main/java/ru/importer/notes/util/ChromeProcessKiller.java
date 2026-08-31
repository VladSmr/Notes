package ru.importer.notes.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Принудительно завершает «висящие» процессы chrome.exe, удерживающие НАШ профиль
 * Chrome, после жёсткого падения приложения — когда {@code driver.quit()} не успел
 * выполниться и профиль остался заблокированным.
 *
 * <p><b>Алгоритм:</b> через {@link ProcessHandle} перечисляются все процессы; отбираются
 * только {@code chrome.exe}, в командной строке которых встречается путь к нашему
 * {@code chrome-profile} (аргумент {@code --user-data-dir=...}); каждый найденный PID
 * завершается через {@code taskkill /F /PID <pid>}.</p>
 *
 * <p><b>Безопасность:</b> личный Chrome пользователя не трогается никогда — у него в
 * командной строке нет пути к нашему профилю. Убивается не «все chrome.exe», а только
 * процессы с точным совпадением пути (без учёта регистра, с проверкой границы пути,
 * чтобы {@code chrome-profile2} не совпал с {@code chrome-profile}).</p>
 *
 * <p>Метод устойчив к ошибкам: процесс уже завершён, нет прав, taskkill недоступен —
 * все ситуации логируются, но исключение наружу не пробрасывается.</p>
 */
@Component
public class ChromeProcessKiller {

    private static final Logger log = LoggerFactory.getLogger(ChromeProcessKiller.class);

    /** Признак исполняемого файла Chrome в команде/командной строке процесса. */
    private static final String CHROME_EXECUTABLE_MARKER = "chrome.exe";

    /** Таймаут ожидания завершения taskkill, сек. */
    private static final long TASKKILL_TIMEOUT_SECONDS = 10;

    /**
     * Находит и принудительно завершает висящий Chrome с профилем приложения
     * ({@link WorkingDir#chromeProfileDir()}).
     *
     * @return число принудительно завершённых процессов
     */
    public int killStaleChromeProcesses() {
        return killStaleChromeProcesses(WorkingDir.chromeProfileDir());
    }

    /**
     * Находит и принудительно завершает висящие процессы chrome.exe, чья командная
     * строка содержит {@code profileDir}. Личные Chrome-процессы (с другими профилями)
     * не затрагиваются.
     *
     * @param profileDir путь к профилю приложения (может содержать пробелы/спецсимволы)
     * @return число принудительно завершённых процессов
     */
    public int killStaleChromeProcesses(Path profileDir) {
        if (profileDir == null) {
            return 0;
        }
        String profileNeedle = normalize(profileDir.toString());

        List<ChromeProcess> candidates;
        try {
            candidates = findChromeProcesses();
        } catch (RuntimeException e) {
            log.warn("Не удалось получить список процессов Chrome: {}", e.getMessage());
            return 0;
        }

        int killed = 0;
        for (ChromeProcess process : candidates) {
            if (!matchesProfile(process.commandLine(), profileNeedle)) {
                continue;
            }
            log.info("Найден висящий Chrome с профилем приложения (PID {}) — принудительно завершаю",
                    process.pid());
            try {
                if (killPid(process.pid())) {
                    killed++;
                }
            } catch (RuntimeException e) {
                // Сбой завершения одного процесса (нет прав, процесс уже ушёл) не должен
                // прервать обработку остальных и уронить вызывающий код.
                log.warn("Не удалось принудительно завершить Chrome (PID {}): {}",
                        process.pid(), e.getMessage());
            }
        }
        if (killed > 0) {
            log.info("Принудительно завершено висящих Chrome-процессов с нашим профилем: {}", killed);
        }
        return killed;
    }

    /**
     * Перечисляет процессы chrome.exe: PID + полная командная строка.
     *
     * <p>Package-private: точка расширения для тестов (реальный перебор процессов в
     * unit-тестах не выполняется). Исполняет {@link ProcessHandle#allProcesses()} — без
     * внешних {@code wmic}/{@code tasklist}, которые недоступны/удалены в части версий
     * Windows. Сбой чтения информации об отдельном процессе (процесс завершился между
     * перечислением и чтением, нет доступа) не прерывает перебор остальных.</p>
     */
    List<ChromeProcess> findChromeProcesses() {
        List<ChromeProcess> result = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(processHandle -> {
            try {
                ProcessHandle.Info info = processHandle.info();
                if (info == null) {
                    return;
                }
                String command = info.command().orElse("");
                String commandLine = info.commandLine().orElse(null);
                if (commandLine == null || commandLine.isBlank()) {
                    // На части систем commandLine() недоступен — собираем из command + arguments.
                    String args = info.arguments().map(a -> String.join(" ", a)).orElse("");
                    commandLine = (command + " " + args).trim();
                }
                if (isChromeExecutable(command) || isChromeExecutable(commandLine)) {
                    result.add(new ChromeProcess(processHandle.pid(), commandLine));
                }
            } catch (RuntimeException e) {
                // Процесс мог завершиться в момент чтения — пропускаем, это не ошибка.
                log.debug("Пропускаю недоступный процесс при поиске Chrome: {}", e.getMessage());
            }
        });
        return result;
    }

    /**
     * Принудительно завершает процесс: {@code taskkill /F /PID <pid>}.
     *
     * <p>Package-private: в тестах мокается, реальный taskkill не запускается. Любой
     * сбой (процесс уже завершён, нет прав, taskkill не найден, таймаут) логируется и
     * возвращает {@code false} — исключение наружу не пробрасывается.</p>
     *
     * @return {@code true}, если процесс завершён (код выхода taskkill = 0)
     */
    boolean killPid(long pid) {
        try {
            Process taskkill = new ProcessBuilder("taskkill", "/F", "/PID", Long.toString(pid))
                    .redirectErrorStream(true)
                    .start();
            // Читаем вывод ДО waitFor, чтобы не упереться в переполнение пайпа.
            String output = new String(taskkill.getInputStream().readAllBytes(),
                    Charset.defaultCharset());
            boolean finished = taskkill.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                taskkill.destroyForcibly();
                log.warn("taskkill не завершился за {} с (PID {})", TASKKILL_TIMEOUT_SECONDS, pid);
                return false;
            }
            int exitCode = taskkill.exitValue();
            if (exitCode == 0) {
                log.info("Chrome-процесс (PID {}) принудительно завершён", pid);
                return true;
            }
            // Процесс мог уже завершиться сам, либо нет прав — это не ошибка приложения.
            log.warn("taskkill не смог завершить PID {}: код выхода {}, вывод: {}",
                    pid, exitCode, output.trim());
            return false;
        } catch (IOException e) {
            log.warn("Не удалось запустить taskkill (PID {}): {}", pid, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ожидание taskkill прервано (PID {})", pid);
            return false;
        }
    }

    private static boolean isChromeExecutable(String value) {
        return value != null && normalize(value).contains(CHROME_EXECUTABLE_MARKER);
    }

    /**
     * Проверяет, что командная строка процесса содержит путь НАШЕГО профиля.
     *
     * <p>Сравнение без учёта регистра (Windows) и с проверкой «границы» пути: после
     * совпадения должен идти конец строки, пробел или кавычка — иначе
     * {@code ...\chrome-profile2} ошибочно совпал бы с {@code ...\chrome-profile}.</p>
     *
     * @param commandLine   полная командная строка процесса (может быть null)
     * @param profileNeedle нормализованный путь профиля ({@link #normalize(String)})
     * @return {@code true}, если процесс держит наш профиль
     */
    static boolean matchesProfile(String commandLine, String profileNeedle) {
        if (commandLine == null || profileNeedle == null || profileNeedle.isBlank()) {
            return false;
        }
        String normalized = normalize(commandLine);
        int index = normalized.indexOf(profileNeedle);
        while (index >= 0) {
            int end = index + profileNeedle.length();
            if (end == normalized.length()) {
                return true;
            }
            char next = normalized.charAt(end);
            if (next == ' ' || next == '"') {
                return true;
            }
            index = normalized.indexOf(profileNeedle, index + 1);
        }
        return false;
    }

    /**
     * Нормализация пути/строки для сравнения: нижний регистр (Windows не различает
     * регистр) и прямые слэши → обратные (Chrome в командной строке может передавать
     * путь в любом виде).
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    /** PID и полная командная строка процесса chrome.exe. */
    public record ChromeProcess(long pid, String commandLine) {
    }

}
