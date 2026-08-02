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

    private static final Duration IMPLICIT_WAIT = Duration.ofSeconds(5);
    private static final Duration PAGE_WAIT = Duration.ofSeconds(10);

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
     * Проходит по всем фильмам, ищет их на IMDB и проставляет оценки.
     *
     * @param movies список фильмов с оценками с КП
     * @param driver WebDriver для управления браузером
     */
    public void evaluate(List<MovieData> movies, WebDriver driver, ImportProgress progress) {
        log.info("IMDB import: начинаем обработку {} фильмов", movies.size());

        driver.manage().timeouts().implicitlyWait(IMPLICIT_WAIT);

        WebDriverWait wait = new WebDriverWait(driver, PAGE_WAIT);

        progress.init(movies.size());

        Map<String, String> seenErrors = new HashMap<>();

        for (int i = 0; i < movies.size(); i++) {
            if (progress.isAborted()) {
                log.info("IMDB import: остановлен пользователем на фильме {}/{}", i + 1, movies.size());
                break;
            }

            MovieData movie = movies.get(i);
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

            progress.advance(ImportProgress.PHASE_IMDB, movie.getName(), movie.getStatus().name());

            if (i < movies.size() - 1 && !progress.isAborted()) {
                sleepRandom();
            }
        }

        log.info("IMDB import: завершено, обработано фильмов: {}", movies.size());
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
    private Integer getExistingRating(WebDriver driver, WebDriverWait wait) {
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

        Integer existingRating = getExistingRating(driver, wait);
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

        Integer confirmed = getExistingRating(driver, wait);
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

        WebElement starbar = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                "div.ipc-starbar__rating"
        )));
        scrollIntoView(driver, starbar);

        WebElement star = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(
                String.format("div.ipc-starbar__rating button[aria-label=\"Rate %d\"]", rating)
        )));
        log.info("Кликаю звезду {} (через JS: реальный клик перехватывает оверлей .ipc-starbar__touch)", rating);
        jsClick(driver, star);

        if (waitForRating(driver, rating, 4000)) {
            log.info("Оценка {} сохранена сразу после выбора звезды", rating);
            return;
        }

        WebElement submit = findRateSubmitButton(driver);
        if (submit == null) {
            try {
                submit = new WebDriverWait(driver, Duration.ofSeconds(4))
                        .until(ExpectedConditions.elementToBeClickable(By.cssSelector(SUBMIT_BUTTON_CSS)));
            } catch (TimeoutException ignored) {
            }
        }
        if (submit == null) {
            log.warn("Кнопка подтверждения Rate в панели не найдена, оценка {} не появилась после клика по звезде", rating);
            dumpStarbarHtml(driver);
            return;
        }
        log.info("Кликаю кнопку подтверждения Rate");
        clickRealOrJs(driver, submit, "кнопка Rate");
        sleepUninterruptibly(800);
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
            Integer current = getExistingRating(driver, null);
            if (current != null && current == expected) {
                return true;
            }
            sleepUninterruptibly(400);
        }
        return false;
    }

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

    /** Временная диагностика: печатает HTML панели звёзд, чтобы понять, как выглядит кнопка подтверждения. */
    private void dumpStarbarHtml(WebDriver driver) {
        try {
            WebElement starbar = driver.findElement(By.cssSelector("div.ipc-starbar__rating"));
            log.warn("HTML панели звёзд:\n{}", starbar.getAttribute("outerHTML"));
        } catch (Exception ignored) {
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
            sleepUninterruptibly(300);
        } catch (Exception ignored) {}
    }

    private void sleepRandom() {
        int seconds = ThreadLocalRandom.current().nextInt(3, 6);
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
