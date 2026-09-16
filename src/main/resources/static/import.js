/*
 * Общий клиент страниц хода импорта (importing-parsing.html, importing-proset.html).
 * Подписки SSE (/notes/progress — ход процесса, /notes/log — окно лога), кнопки
 * «Пауза / Прекратить / Продолжить» и панель «нужен новый путь» (change-log-dir).
 * Специфичная для страницы отрисовка прогресса передаётся колбэком onProgress(data);
 * адрес страницы результата — data-result-url на <body>, текст статуса паузы —
 * data-pause-message (тексты задаются на страницах).
 */
(function () {
    'use strict';

    window.initImportPage = function (onProgress) {
        var resultUrl = document.body.getAttribute('data-result-url');
        var pauseMessage = document.body.getAttribute('data-pause-message');
        var startTime = Date.now();
        var source = new EventSource('/notes/progress');
        var logSource = new EventSource('/notes/log');

        logSource.onmessage = function (event) {
            var win = document.getElementById('log-window');
            var line = document.createElement('div');
            line.className = 'log-line';
            line.textContent = event.data;
            win.appendChild(line);
            while (win.children.length > 1000) {
                win.removeChild(win.firstChild);
            }
            win.scrollTop = win.scrollHeight;
        };

        logSource.onerror = function () {
            logSource.close();
        };

        source.onmessage = function (event) {
            var data = JSON.parse(event.data);

            if (data.finished) {
                source.close();
                // Страница результата (/notes/parsing-result или /notes/result) сама
                // различит ошибку и успех (по result.errorMessage) и передаст на
                // страницу полный лог ошибки (errorDetails).
                window.location.href = resultUrl;
                return;
            }

            if (data.paused) {
                document.getElementById('pause-btn').style.display = 'none';
                document.getElementById('stop-btn').disabled = false;
                if (data.status === 'need-new-dir') {
                    document.getElementById('resume-btn').style.display = 'none';
                    document.getElementById('new-dir-panel').style.display = 'block';
                    document.getElementById('status-text').textContent = 'Не удалось записать дамп (нет прав). Укажите новый путь.';
                } else {
                    document.getElementById('new-dir-panel').style.display = 'none';
                    document.getElementById('resume-btn').style.display = 'inline-block';
                    if (data.status === 'paused-still-busy') {
                        document.getElementById('status-text').textContent = 'Файл всё ещё занят: закройте его и нажмите «Продолжить»';
                    } else if (data.status === 'paused-user') {
                        document.getElementById('status-text').textContent = 'Пауза. Нажмите «Продолжить», чтобы возобновить.';
                    } else {
                        document.getElementById('status-text').textContent = 'Пауза: закройте CSV-файл и нажмите «Продолжить»';
                    }
                }
                return;
            }

            if (data.status === 'resumed') {
                document.getElementById('resume-btn').style.display = 'none';
                document.getElementById('new-dir-panel').style.display = 'none';
                document.getElementById('pause-btn').style.display = 'inline-block';
                document.getElementById('pause-btn').disabled = false;
                document.getElementById('stop-btn').disabled = false;
                document.getElementById('status-text').textContent = 'Продолжаю...';
                return;
            }

            onProgress(data);

            var elapsed = Math.floor((Date.now() - startTime) / 1000);
            var mins = Math.floor(elapsed / 60);
            var secs = elapsed % 60;
            document.getElementById('elapsed').textContent = 'Прошло: ' + mins + 'м ' + secs + 'с';
        };

        source.onerror = function () {
            source.close();
            document.getElementById('status-text').textContent = 'Соединение потеряно. Перенаправление...';
            setTimeout(function () { window.location.href = resultUrl; }, 2000);
        };

        document.getElementById('pause-btn').addEventListener('click', function () {
            fetch('/notes/pause', { method: 'POST' });
            document.getElementById('status-text').textContent = pauseMessage;
            document.getElementById('pause-btn').disabled = true;
        });

        document.getElementById('stop-btn').addEventListener('click', function () {
            fetch('/notes/stop', { method: 'POST' });
            document.getElementById('status-text').textContent = 'Остановка...';
            document.getElementById('stop-btn').disabled = true;
            document.getElementById('pause-btn').disabled = true;
        });

        document.getElementById('resume-btn').addEventListener('click', function () {
            fetch('/notes/resume', { method: 'POST' });
            document.getElementById('resume-btn').disabled = true;
            document.getElementById('status-text').textContent = 'Продолжаю...';
        });

        document.getElementById('change-dir-btn').addEventListener('click', changeLogDir);

        function changeLogDir() {
            var dir = document.getElementById('new-dir-input').value.trim();
            var errEl = document.getElementById('new-dir-error');
            if (!dir) {
                errEl.textContent = 'Введите путь к директории.';
                return;
            }
            errEl.textContent = '';
            document.getElementById('change-dir-btn').disabled = true;
            fetch('/notes/change-log-dir', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'logDirectory=' + encodeURIComponent(dir)
            }).then(function (resp) { return resp.text(); }).then(function (text) {
                document.getElementById('change-dir-btn').disabled = false;
                if (text === 'changed') {
                    document.getElementById('status-text').textContent = 'Применяю новый путь...';
                } else if (text === 'empty') {
                    errEl.textContent = 'Введите путь к директории.';
                } else {
                    errEl.textContent = 'Не удалось применить путь. Попробуйте ещё раз.';
                }
            }).catch(function () {
                document.getElementById('change-dir-btn').disabled = false;
                errEl.textContent = 'Ошибка сети. Попробуйте ещё раз.';
            });
        }
    };
})();
