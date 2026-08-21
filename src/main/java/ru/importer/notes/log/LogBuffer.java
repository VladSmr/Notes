package ru.importer.notes.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Кольцевой буфер последних строк лога (до {@link #CAPACITY} записей) для показа
 * на веб-страницах прогресса (парсинг/проставление). Перехватывает сообщения наших классов
 * через logback-appender и раздаёт их подписчикам SSE ({@code /notes/log});
 * при подключении отдаёт снимок буфера.
 */
@Service
public class LogBuffer {

    private static final int CAPACITY = 1000;
    private static final String APP_PACKAGE = "ru.importer.notes";

    private final Deque<String> lines = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public LogBuffer() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (!isStarted()) {
                    return;
                }
                if (!event.getLoggerName().startsWith(APP_PACKAGE)) {
                    return;
                }
                addLine(event.getFormattedMessage());
            }
        };
        appender.setName("webLogBuffer");
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    public synchronized SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        for (String line : new ArrayList<>(lines)) {
            try {
                emitter.send(line);
            } catch (Exception e) {
                emitters.remove(emitter);
                break;
            }
        }
        return emitter;
    }

    private synchronized void addLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        lines.addLast(line);
        while (lines.size() > CAPACITY) {
            lines.removeFirst();
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(line);
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

}
