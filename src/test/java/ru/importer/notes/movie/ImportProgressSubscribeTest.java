package ru.importer.notes.movie;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.importer.notes.dto.AppResult;
import ru.importer.notes.dto.ProgressEvent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Отложенная отдача финального события: если этап завершился до подписки страницы
 * (быстрый фатальный сбой), новый подписчик сразу получает {@code finished} — иначе
 * UI завис бы. Реальный SseEmitter заменяется моком через {@link ImportProgress#createEmitter()}.
 */
class ImportProgressSubscribeTest {

    /** ImportProgress с подменённым SseEmitter (реальный эмиттер без HTTP не наблюдать). */
    private static class TestImportProgress extends ImportProgress {
        final SseEmitter emitter = mock(SseEmitter.class);

        @Override
        protected SseEmitter createEmitter() {
            return emitter;
        }
    }

    @Test
    void subscribe_afterCompletion_replaysFinishedEvent() throws Exception {
        TestImportProgress progress = new TestImportProgress();
        AppResult error = new AppResult();
        error.setErrorMessage("Токен недействителен (401). Проверьте ключ КП");

        // Этап завершился до подключения страницы.
        progress.complete("parsing", error);

        SseEmitter emitter = progress.subscribe();

        assertSame(progress.emitter, emitter);
        verify(progress.emitter).send(any(ProgressEvent.class));
        verify(progress.emitter).complete();
    }

    @Test
    void subscribe_beforeCompletion_waitsForBroadcast() throws Exception {
        TestImportProgress progress = new TestImportProgress();

        SseEmitter emitter = progress.subscribe();
        assertSame(progress.emitter, emitter);
        verify(progress.emitter, never()).send(any(ProgressEvent.class));

        AppResult ok = new AppResult();
        progress.complete("parsing", ok);

        verify(progress.emitter).send(any(ProgressEvent.class));
        verify(progress.emitter).complete();
    }

    @Test
    void subscribe_afterBegin_doesNotReplayStaleResult() throws Exception {
        TestImportProgress progress = new TestImportProgress();
        AppResult stale = new AppResult();
        stale.setErrorMessage("старый финал");
        progress.complete("parsing", stale);
        progress.begin(); // новый этап стартовал, результат прошлого сброшен

        progress.subscribe();

        verify(progress.emitter, never()).send(any(ProgressEvent.class));
    }
}
