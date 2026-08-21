package ru.importer.notes.imdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieData.MovieStatus;
import ru.importer.notes.movie.ImportProgress;

@Service
public class ImdbNotesExporter {

    private static final Logger log = LoggerFactory.getLogger(ImdbNotesExporter.class);

    private static final Duration IMPLICIT_WAIT = Duration.ofSeconds(2);
    private static final Duration PAGE_WAIT = Duration.ofSeconds(10);

    /**
     * Кнопка подтверждения Rate. Она НЕ внутри панели div.ipc-starbar__rating —
     * это сиблинг: button.ipc-rating-prompt__rate-button (в disabled-состоянии,
     * активируется после выбора звезды). Панельные селекторы оставлены на всякий
     * случай для других вариантов разметки. Кнопка должна быть активной.
     */
    private static final String SUBMIT_BUTTON_CSS =
            "button.ipc-rating-prompt__rate-button, " +
                    "div.ipc-starbar__rating button[aria-label=\"Rate\"], " +
                    "div.ipc-starbar__rating button[aria-label=\"Submit rating\"], " +
                    "div.ipc-starbar__rating button[aria-label=\"Submit\"], " +
                    "div.ipc-starbar__rating button[class*=\"submit\" i]";

    /**
     * Формирует поисковый запрос для IMDB.
     * Для API-парсинга nameEn — английское название (надёжнее ищется на IMDB),
     * для Selenium — оригинальное название. Если его нет, берём русское.
     */
    private String buildSearchQuery(MovieData movie) {
        String name = movie.getNameEn();
        if (name == null || name.isBlank()) {
            name = movie.getName();
        }
        if (name == null || name.isBlank()) {
            return "";
        }
        return name + " " + movie.getYear();
    }

    /**
     * Проходит по всем фильмам, ищет их на IMDB и проставляет оценки (этап «Проставление»).
     * Фильмы приходят из дампа kp-ratings.csv (провайдер {@code saved}); уже обработанные
     * в прошлых запусках пропускаются.
     * После обработки каждого фильма вызывается {@code onMovieProcessed}, чтобы
     * результаты (статусы/ошибки) сразу попадали в kp-ratings.csv — тогда при
     * остановке приложения дамп не остаётся пустым.
     *
     * @param movies           список фильмов с оценками (из дампа kp-ratings.csv)
     * @param driver           WebDriver для управления браузером
     * @param progress         прогресс этапа «Проставление»
     * @param onMovieProcessed колбэк сохранения дампа (вызывается после каждых 5 обработанных фильмов,
     *                         чтобы статусы появлялись в kp-ratings.csv даже при остановке приложения)
     */
    public void evaluate(List<MovieData> movies, WebDriver driver, ImportProgress progress,
                         Runnable onMovieProcessed) {
        log.info("IMDB import: начинаем обработку {} фильмов", movies.size());

        driver.manage().timeouts().implicitlyWait(IMPLICIT_WAIT);

        WebDriverWait wait = new WebDriverWait(driver, PAGE_WAIT);

        progress.init(movies.size());

        Map<String, String> seenErrors = new HashMap<>();

        int processed = 0;

        for (int i = 0; i < movies.size(); i++) {
            if (progress.isAborted()) {
                log.info("IMDB import: остановлен пользователем на фильме {}/{}", i + 1, movies.size());
                break;
            }
            progress.waitWhilePaused();

            MovieData movie = movies.get(i);
            boolean alreadyDone = isDone(movie.getStatus());
            if (alreadyDone) {
                log.info("Фильм {}/{}: {} — уже обработан ранее ({}), пропускаю",
                        i + 1, movies.size(), movie.getName(), movie.getStatusLabel());
            } else {
                log.info("Фильм {}/{}: {} ({})", i + 1, movies.size(), movie.getName(), movie.getYear());
                try {
                    processMovie(movie, driver, wait);
                } catch (Exception e) {
                    movie.setStatus(MovieStatus.ERROR);
                    String fullMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    String msg = truncateError(fullMsg);
                    movie.setErrorMessage(msg);

                    String key = normalizeError(fullMsg);
                    String firstRef = seenErrors.get(key);
                    if (firstRef != null) {
                        log.info("Фильм {}/{}: повторная ошибка, впервые у {}: {}", i + 1, movies.size(), firstRef, msg);
                    } else {
                        seenErrors.put(key, "фильма №" + (i + 1) + " («" + movie.getName() + "»)");
                        log.error("Фильм {}/{}: ошибка при обработке '{}': {}", i + 1, movies.size(), movie.getName(), msg);
                    }
                }
            }

            String displayName = movie.getYear() > 0
                    ? movie.getName() + " (" + movie.getYear() + ")"
                    : movie.getName();
            progress.advance(ImportProgress.PHASE_IMDB, displayName, movie.getStatus().name());

            if (++processed % 5 == 0) {
                onMovieProcessed.run();
            }

            if (i < movies.size() - 1 && !progress.isAborted() && !alreadyDone) {
                sleepRandom();
            }
        }

        log.info("IMDB import: завершено, обработано фильмов: {}", movies.size());
    }

    /** Фильм уже обработан в прошлом запуске — повторно проставлять не нужно. */
    private boolean isDone(MovieStatus status) {
        return status == MovieStatus.RATED
                || status == MovieStatus.NOT_FOUND
                || status == MovieStatus.SKIPPED_SAME
                || status == MovieStatus.SKIPPED_DIFFERENT;
    }

    /**
     * Ищет результат на странице поиска IMDB, сверяя с названием и годом фильма.
     * Совпадения проверяются строго: сначала название+год, потом только год, потом только название.
     * Если ничего не совпало — фильм считается не найденным (NOT_FOUND), чтобы случайно
     * не проставить оценку постороннему фильму (раньше брался первый результат списка,
     * который мог оказаться фильмом из каруселей/футера и вообще не существовать).
     */
    private WebElement findFirstResult(WebDriver driver, WebDriverWait wait, MovieData movie) {
        List<WebElement> results = collectTitleLinks(driver);
        if (results.isEmpty()) {
            sleepUninterruptibly(2000);
            results = collectTitleLinks(driver);
        }
        if (results.isEmpty()) {
            return null;
        }

        String nameEn = movie.getNameEn() != null ? movie.getNameEn().toLowerCase() : "";
        String name = movie.getName() != null ? movie.getName().toLowerCase() : "";
        String nameToken = !nameEn.isBlank() ? nameEn : name;
        if (nameToken.length() > 12) {
            nameToken = nameToken.substring(0, 12);
        }
        String yearStr = movie.getYear() > 0 ? "(" + movie.getYear() + ")" : null;

        if (yearStr != null && !nameToken.isBlank()) {
            for (WebElement el : results) {
                String text = el.getText().toLowerCase();
                if (text.contains(nameToken) && text.contains(yearStr)) {
                    return el;
                }
            }
        }
        if (yearStr != null) {
            for (WebElement el : results) {
                if (el.getText().toLowerCase().contains(yearStr)) {
                    return el;
                }
            }
        }
        if (!nameToken.isBlank()) {
            for (WebElement el : results) {
                if (el.getText().toLowerCase().contains(nameToken)) {
                    return el;
                }
            }
        }
        return null;
    }

    private List<WebElement> collectTitleLinks(WebDriver driver) {
        try {
            return driver.findElements(By.cssSelector(
                    "[data-testid=\"find-results-section-title\"] a.ipc-metadata-list-summary-item__t, " +
                            ".findResult a, " +
                            "a[href*=\"/title/tt\"]"
            ));
        } catch (Exception ignored) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Проверяет, есть ли уже оценка на странице фильма IMDB.
     * Текущая оценка пользователя лежит в div[data-testid="hero-rating-bar__user-rating__score"]
     * (внутри один span с числом, дальше текст "/10").
     *
     * @return текущая оценка или null, если не оценён
     */
    private Integer getExistingRating(WebDriver driver) {
        Duration prev = driver.manage().timeouts().getImplicitWaitTimeout();
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        try {
            try {
                WebElement score = driver.findElement(By.cssSelector(
                        "[data-testid=\"hero-rating-bar__user-rating__score\"] span"
                ));
                String text = score.getText().trim();
                if (!text.isEmpty()) {
                    try {
                        return Integer.parseInt(text.replaceAll("\\D", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                WebElement ratingBar = driver.findElement(By.cssSelector(
                        "[data-testid=\"hero-rating-bar__stars\"], " +
                                ".star-rating-button, " +
                                "[data-testid=\"rating-stars\"]"
                ));

                String alreadyRated = ratingBar.getAttribute("aria-label");
                if (alreadyRated != null && alreadyRated.matches(".*\\d+.*")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(alreadyRated);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }

                WebElement ratingValue = ratingBar.findElement(By.cssSelector(".ratingValue, [class*=\"rated\"]"));
                if (ratingValue != null) {
                    String text = ratingValue.getText().trim();
                    try {
                        return Integer.parseInt(text.replaceAll("\\D", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        } finally {
            driver.manage().timeouts().implicitlyWait(prev);
        }
    }

    /**
     * Обрабатывает один фильм: переход по imdbId (если известен) или поиск,
     * проверка текущей оценки, проставление.
     */
    private void processMovie(MovieData movie, WebDriver driver, WebDriverWait wait) {
        String imdbId = movie.getImdbId();
        if (imdbId != null && !imdbId.isBlank()) {
            log.info("Открываю IMDB напрямую по id: {}", imdbId);
            driver.get("https://www.imdb.com/title/" + imdbId + "/");
        } else {
            String query = buildSearchQuery(movie);
            log.info("Поиск на IMDB: '{}'", query);
            driver.get("https://www.imdb.com/find/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));

            WebElement firstResult = findFirstResult(driver, wait, movie);
            if (firstResult == null) {
                movie.setStatus(MovieStatus.NOT_FOUND);
                log.info("Не найден на IMDB: {}", movie.getName());
                return;
            }

            String imdbHref = firstResult.getAttribute("href");
            if (imdbHref != null && imdbHref.contains("/title/")) {
                String ttId = imdbHref.replaceAll(".*/title/(tt\\d+).*", "$1");
                movie.setImdbId(ttId);
                log.info("Найден на IMDB: {} ({})", movie.getName(), ttId);
            }
            firstResult.click();
        }

        Integer existingRating = getExistingRating(driver);
        if (existingRating != null) {
            movie.setImdbRating(existingRating);
            if (existingRating == movie.getKpRating()) {
                movie.setStatus(MovieStatus.SKIPPED_SAME);
                log.info("Оценка уже стоит такая же ({}): {} ", existingRating, movie.getName());
            } else {
                movie.setStatus(MovieStatus.SKIPPED_DIFFERENT);
                log.info("Оценка отличается (IMDB={}, КП={}), оставляем руками: {}",
                        existingRating, movie.getKpRating(), movie.getName());
            }
            return;
        }

        log.info("Проставляю оценку {} для {}", movie.getKpRating(), movie.getName());
        setRating(driver, wait, movie.getKpRating());

        Integer confirmed = getExistingRating(driver);
        if (confirmed != null && confirmed == movie.getKpRating()) {
            movie.setImdbRating(confirmed);
            movie.setStatus(MovieStatus.RATED);
            log.info("Оценка {} подтверждена для {}", confirmed, movie.getName());
        } else {
            movie.setImdbRating(movie.getKpRating());
            movie.setStatus(MovieStatus.ERROR);
            movie.setErrorMessage("Оценка не подтвердилась на IMDB: ожидалось " + movie.getKpRating()
                    + ", по факту " + (confirmed != null ? confirmed : "оценка не видна"));
            log.error("Оценка не подтвердилась для {}: ожидалось {}, по факту {}",
                    movie.getName(), movie.getKpRating(), confirmed);
        }
    }

    /**
     * Проставляет оценку фильму на IMDB.
     * Поток: клик по виджету звёзд → открывается панель div.ipc-starbar__rating
     * (кнопки с aria-label="Rate 1".."Rate 10") → кликаем нужную звезду.
     * После выбора звезды IMDB может сохранить оценку сразу или показать кнопку "Rate" —
     * поэтому сначала ждём подтверждения оценки, и только если её нет, ищем кнопку
     * строго внутри панели. Клики — реальные (Selenium Actions, как раньше), и только
     * если браузер перехватывает клик, используем JS-фолбэк.
     */
    private void setRating(WebDriver driver, WebDriverWait wait, int rating) {
        WebElement rateButton = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                "[data-testid=\"hero-rating-bar__stars\"] button, " +
                        "button[aria-label*=\"Rate\"], " +
                        ".star-rating-button, " +
                        "[data-testid=\"rating-stars\"]"
        )));
        log.info("Кликаю виджет звёзд, чтобы открыть панель");
        clickRealOrJs(driver, rateButton, "виджет звёзд");

        WebElement starbar = waitForStarbar(driver, 3);
        if (starbar == null) {
            for (int attempt = 1; attempt <= 2 && starbar == null; attempt++) {
                log.warn("Панель div.ipc-starbar__rating не открылась (попытка {}): повторный клик по виджету "
                        + "полной последовательностью событий (реальный клик мог быть перехвачен)", attempt);
                jsClickSequence(driver, rateButton);
                starbar = waitForStarbar(driver, 3);
            }
        }
        if (starbar != null) {
            scrollIntoView(driver, starbar);
        }

        WebElement star = findStarButton(driver, wait, rating);
        log.info("Кликаю звезду {}", rating);
        boolean selected = clickStar(driver, star, rating);
        log.info("Звезда {} {} выбрана", rating, selected ? "" : "НЕ ");

        WebElement submit = findRateSubmitButton(driver);
        if (submit == null) {
            try {
                submit = new WebDriverWait(driver, Duration.ofSeconds(3))
                        .until(ExpectedConditions.elementToBeClickable(By.cssSelector(SUBMIT_BUTTON_CSS)));
            } catch (TimeoutException ignored) {
            }
        }
        if (submit != null) {
            log.info("Кликаю кнопку подтверждения Rate");
            clickRealOrJs(driver, submit, "кнопка Rate");
        }
        sleepUninterruptibly(300);

        if (waitForRating(driver, rating, 4000)) {
            log.info("Оценка {} подтверждена", rating);
            return;
        }

        if (submit == null) {
            log.warn("Кнопка подтверждения Rate не найдена, оценка {} не появилась после выбора звезды", rating);
            dumpStarbarHtml(driver);
        }
    }

    /** Ждёт появления панели звёзд (до 3 с); возвращает null, если панель не появилась. */
    private WebElement waitForStarbar(WebDriver driver, int seconds) {
        try {
            return new WebDriverWait(driver, Duration.ofSeconds(seconds))
                    .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div.ipc-starbar__rating")));
        } catch (TimeoutException e) {
            return null;
        }
    }

    /**
     * Ищет кнопку звезды с нужной оценкой: сначала внутри панели div.ipc-starbar__rating,
     * потом глобально среди видимых (IMDB раздаёт разные варианты разметки — в новых
     * звёзды могут лежать вне панели).
     */
    private WebElement findStarButton(WebDriver driver, WebDriverWait wait, int rating) {
        String labelCss = String.format("button[aria-label^=\"Rate %d\"]", rating);
        for (WebElement el : driver.findElements(By.cssSelector("div.ipc-starbar__rating " + labelCss))) {
            if (isVisible(el)) {
                return el;
            }
        }
        for (WebElement el : driver.findElements(By.cssSelector(labelCss))) {
            if (isVisible(el)) {
                return el;
            }
        }
        try {
            return wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(labelCss)));
        } catch (TimeoutException e) {
            dumpStarbarHtml(driver);
            throw e;
        }
    }

    /**
     * Кликает звезду и проверяет, что выбор прошёл (кнопка Rate активировалась или оценка видна).
     * Способы по порядку: простой JS-клик (работал в рабочей версии), полная синтетическая
     * последовательность pointer/mouse-событий, затем реальный клик с временно отключённым
     * оверлеем .ipc-starbar__touch.
     */
    private boolean clickStar(WebDriver driver, WebElement star, int rating) {
        jsClick(driver, star);
        if (isStarSelected(driver, rating)) {
            return true;
        }
        log.info("Простой JS-клик не выбрал звезду {} — пробую полную последовательность событий", rating);
        jsClickSequence(driver, star);
        if (isStarSelected(driver, rating)) {
            return true;
        }
        log.info("Синтетические события не выбрали звезду {} — отключаю оверлей .ipc-starbar__touch и кликаю реально", rating);
        disableTouchOverlay(driver);
        try {
            scrollIntoView(driver, star);
            new Actions(driver).moveToElement(star).click().perform();
        } catch (Exception e) {
            log.warn("Реальный клик по звезде не удался: {}", e.getMessage());
        } finally {
            restoreTouchOverlay(driver);
        }
        return isStarSelected(driver, rating);
    }

    /** Проверяет, что выбор звезды зарегистрировался: кнопка Rate активна или оценка уже видна. */
    private boolean isStarSelected(WebDriver driver, int rating) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (WebElement btn : driver.findElements(By.cssSelector("button.ipc-rating-prompt__rate-button"))) {
                if (isVisible(btn) && isEnabled(btn)) {
                    return true;
                }
            }
            Integer current = getExistingRating(driver);
            if (current != null && current == rating) {
                return true;
            }
            sleepUninterruptibly(200);
        }
        return false;
    }

    /**
     * Полная последовательность событий (pointerdown → mousedown → pointerup → mouseup → click)
     * через JS. В отличие от el.click() не зависит от оверлеев и срабатывает для обработчиков,
     * повешенных на pointer-события.
     */
    private void jsClickSequence(WebDriver driver, WebElement element) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var el = arguments[0];" +
                            "var rect = el.getBoundingClientRect();" +
                            "var x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;" +
                            "var types = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];" +
                            "for (var i = 0; i < types.length; i++) {" +
                            "  var evt; try {" +
                            "    evt = new PointerEvent(types[i], {bubbles: true, cancelable: true, composed: true, view: window, pointerType: 'mouse', isPrimary: true, buttons: 1, button: 0, clientX: x, clientY: y});" +
                            "  } catch (err) {" +
                            "    evt = new MouseEvent(types[i], {bubbles: true, cancelable: true, view: window, clientX: x, clientY: y});" +
                            "  }" +
                            "  el.dispatchEvent(evt);" +
                            "}", element
            );
        } catch (Exception e) {
            log.warn("Не удалось отправить синтетические события: {}", e.getMessage());
        }
    }

    /** Временно отключает приём pointer-событий оверлеем .ipc-starbar__touch (мешает реальным кликам). */
    private void disableTouchOverlay(WebDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var els = document.querySelectorAll('.ipc-starbar__touch');" +
                            "for (var i = 0; i < els.length; i++) { els[i].setAttribute('data-pe-saved', els[i].style.pointerEvents || ''); els[i].style.pointerEvents = 'none'; }"
            );
        } catch (Exception ignored) {
        }
    }

    private void restoreTouchOverlay(WebDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var els = document.querySelectorAll('.ipc-starbar__touch');" +
                            "for (var i = 0; i < els.length; i++) { els[i].style.pointerEvents = els[i].getAttribute('data-pe-saved') || ''; els[i].removeAttribute('data-pe-saved'); }"
            );
        } catch (Exception ignored) {
        }
    }

    /**
     * Реальный клик через Selenium Actions (двигает мышь в браузере, как раньше):
     * полная последовательность pointer-событий, видно на экране.
     * Если реальный клик перехвачен браузером (ElementClickInterceptedException) —
     * фолбэк на JS-клик.
     */
    private void clickRealOrJs(WebDriver driver, WebElement element, String description) {
        try {
            scrollIntoView(driver, element);
            sleepUninterruptibly(200);
            new Actions(driver).moveToElement(element).click().perform();
            log.info("Реальный клик по {} выполнен", description);
        } catch (Exception e) {
            log.warn("Реальный клик по {} не удался ({}), кликаю через JS", description, e.getClass().getSimpleName());
            jsClick(driver, element);
        }
    }

    /** Ждёт, пока на странице появится выставленная оценка. */
    private boolean waitForRating(WebDriver driver, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer current = getExistingRating(driver);
            if (current != null && current == expected) {
                return true;
            }
            sleepUninterruptibly(400);
        }
        return false;
    }

    /**
     * Кнопка подтверждения Rate: ищется по CSS (в т.ч. в панели звёзд для других
     * вариантов разметки) и по тексту кнопок внутри панели. Кнопка должна быть активной.
     */
    private WebElement findRateSubmitButton(WebDriver driver) {
        for (WebElement btn : driver.findElements(By.cssSelector(SUBMIT_BUTTON_CSS))) {
            if (isVisible(btn) && isEnabled(btn)) {
                return btn;
            }
        }
        for (WebElement btn : driver.findElements(By.cssSelector("div.ipc-starbar__rating button"))) {
            String label = btn.getAttribute("aria-label");
            if (label != null && label.matches("Rate\\s+\\d+")) {
                continue;
            }
            String text = btn.getText().trim().toLowerCase();
            if (isVisible(btn) && isEnabled(btn) && (text.equals("rate") || text.equals("submit"))) {
                return btn;
            }
        }
        return null;
    }

    private boolean isVisible(WebElement el) {
        try {
            return el.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEnabled(WebElement el) {
        try {
            return el.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Диагностика при неудаче: печатает HTML панели звёзд, виджета и rate-элементов страницы. */
    private void dumpStarbarHtml(WebDriver driver) {
        for (String css : new String[]{
                "div.ipc-starbar__rating",
                "[data-testid=\"hero-rating-bar__stars\"]",
                ".ipc-rating-prompt"
        }) {
            try {
                WebElement el = driver.findElement(By.cssSelector(css));
                String html = el.getAttribute("outerHTML");
                if (html != null && html.length() > 4000) {
                    html = html.substring(0, 4000) + "...(truncated)";
                }
                log.warn("HTML {}:\n{}", css, html);
            } catch (Exception ignored) {
            }
        }
        for (WebElement el : driver.findElements(By.cssSelector("button[aria-label^=\"Rate\"]"))) {
            try {
                log.warn("Rate-кнопка: visible={} enabled={} html={}",
                        isVisible(el), isEnabled(el),
                        el.getAttribute("outerHTML"));
            } catch (Exception ignored) {
            }
        }
    }

    private String truncateError(String msg) {
        if (msg == null) return null;
        int idx = msg.indexOf("\nBuild info:");
        if (idx > 0) msg = msg.substring(0, idx);
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return msg;
    }

    private String normalizeError(String msg) {
        if (msg == null) return null;
        return msg
                .replaceAll("at point \\(\\d+, \\d+\\)", "at point (X, Y)")
                .replaceAll("aria-label=\"Rate \\d+\"", "aria-label=\"Rate N\"")
                .replaceAll("\\d+", "N");
    }

    /**
     * Клик через JS: обычные клики Selenium стабильно перехватываются элементами на странице,
     * поэтому кликаем напрямую через JavaScript без попыток обычного клика.
     */
    private void jsClick(WebDriver driver, WebElement element) {
        scrollIntoView(driver, element);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }

    private void scrollIntoView(WebDriver driver, WebElement element) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", element
            );
            sleepUninterruptibly(100);
        } catch (Exception ignored) {}
    }

    private void sleepRandom() {
        int seconds = ThreadLocalRandom.current().nextInt(1, 3);
        sleepUninterruptibly(seconds * 1000L);
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
