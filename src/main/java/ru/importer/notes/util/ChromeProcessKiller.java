package ru.importer.notes.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Завершает «висящие» chrome.exe, удерживающие профиль приложения, после жёсткого
 * падения (когда {@code driver.quit()} не успел выполниться). Через {@link ProcessHandle}
 * отбираются chrome.exe с путём к нашему {@code chrome-profile} в командной строке и
 * убиваются через {@code taskkill /F /PID}. Личный Chrome пользователя не трогается
 * никогда (нет пути к нашему профилю; сравнение с проверкой границы пути, чтобы
 * {@code chrome-profile2} не совпал с {@code chrome-profile}). Ошибки (нет прав,
 * процесс уже завершён) логируются, но не пробрасываются.
 */
@Slf4j
@Component
public class ChromeProcessKiller {

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
     * Завершает висящие chrome.exe, чья командная строка содержит {@code profileDir};
     * личные Chrome-процессы не затрагиваются.
     *
     * @param profileDir путь к профилю приложения
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
     * Перечисляет процессы chrome.exe (PID + командная строка) через
     * {@link ProcessHandle#allProcesses()} — без {@code wmic}/{@code tasklist},
     * недоступных в части версий Windows. Сбой чтения одного процесса перебор не прерывает.
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
     * {@code taskkill /F /PID <pid>}; любой сбой логируется и возвращает {@code false},
     * исключение наружу не пробрасывается.
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
     * Командная строка процесса содержит путь НАШЕГО профиля: без учёта регистра и с
     * проверкой «границы» пути (после совпадения — конец строки, пробел или кавычка),
     * чтобы {@code ...\chrome-profile2} не совпал с {@code ...\chrome-profile}.
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

    /** Нормализация для сравнения: нижний регистр (Windows) и прямые слэши → обратные. */
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
