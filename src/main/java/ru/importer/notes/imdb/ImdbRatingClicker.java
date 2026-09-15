package ru.importer.notes.imdb;

import java.time.Duration;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Rate-UI IMDB: открытие панели звёзд, выбор звезды и подтверждение оценки; реальные клики, при перехвате — JS-фолбэк. */
@Slf4j
class ImdbRatingClicker {

    /**
     * Кнопка подтверждения Rate: сиблинг панели звёзд (не внутри неё), в disabled-состоянии
     * до выбора звезды. Панельные селекторы — фолбэк для других вариантов разметки.
     */
    private static final String SUBMIT_BUTTON_CSS =
            "button.ipc-rating-prompt__rate-button, " +
                    "div.ipc-starbar__rating button[aria-label=\"Rate\"], " +
                    "div.ipc-starbar__rating button[aria-label=\"Submit rating\"], " +
                    "div.ipc-starbar__rating button[aria-label=\"Submit\"], " +
                    "div.ipc-starbar__rating button[class*=\"submit\" i]";

    /** Проставляет оценку; при {@link TimeoutException} страница перезагружается и попытка повторяется один раз. */
    void setRating(WebDriver driver, WebDriverWait wait, int rating) {
        try {
            setRatingOnce(driver, wait, rating);
        } catch (TimeoutException first) {
            log.warn("Таймаут при проставлении оценки {}: перезагружаю страницу и пробую ещё раз", rating);
            reloadCurrentPage(driver);
            setRatingOnce(driver, wait, rating);
        }
    }

    /** Одна перезагрузка текущей страницы фильма (для ретрая после таймаута). */
    private void reloadCurrentPage(WebDriver driver) {
        try {
            String url = driver.getCurrentUrl();
            if (url != null && !url.isBlank()) {
                driver.get(url);
                ImdbNotesExporter.sleepUninterruptibly(500);
            } else {
                log.warn("Не удалось перезагрузить страницу: текущий URL неизвестен");
            }
        } catch (Exception e) {
            log.warn("Не удалось перезагрузить страницу: {}", e.getMessage());
        }
    }

    /**
     * Одно проставление оценки: клик по виджету звёзд → панель звёзд → выбор звезды.
     * После выбора звезды IMDB может сохранить оценку сразу или показать кнопку Rate,
     * поэтому сначала ждём подтверждения оценки и только потом ищем кнопку подтверждения.
     * Клики реальные (Selenium Actions), при перехвате — JS-фолбэк.
     */
    private void setRatingOnce(WebDriver driver, WebDriverWait wait, int rating) {
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
        ImdbNotesExporter.sleepUninterruptibly(300);

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
     * Кнопка звезды с нужной оценкой: сначала внутри панели звёзд, потом глобально
     * среди видимых (разметка IMDB меняется). Ожидание и проверка — по ТОЧНОМУ
     * aria-label «Rate N»: префиксный селектор «Rate 1» матчит и «Rate 10».
     */
    WebElement findStarButton(WebDriver driver, WebDriverWait wait, int rating) {
        String expectedLabel = "Rate " + rating;
        String prefixCss = String.format("button[aria-label^=\"Rate %d\"]", rating);
        String exactCss = String.format("button[aria-label=\"%s\"]", expectedLabel);
        for (WebElement el : driver.findElements(By.cssSelector("div.ipc-starbar__rating " + prefixCss))) {
            if (expectedLabel.equals(el.getAttribute("aria-label")) && isVisible(el)) {
                return el;
            }
        }
        for (WebElement el : driver.findElements(By.cssSelector(prefixCss))) {
            if (expectedLabel.equals(el.getAttribute("aria-label")) && isVisible(el)) {
                return el;
            }
        }
        try {
            return wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(exactCss)));
        } catch (TimeoutException e) {
            dumpStarbarHtml(driver);
            throw e;
        }
    }

    /**
     * Кликает звезду и проверяет, что выбор прошёл (кнопка Rate активировалась или оценка видна).
     * Способы по порядку: простой JS-клик, полная последовательность pointer/mouse-событий,
     * затем реальный клик с временно отключённым оверлеем .ipc-starbar__touch.
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
            Integer current = ImdbNotesExporter.getExistingRating(driver);
            if (current != null && current == rating) {
                return true;
            }
            ImdbNotesExporter.sleepUninterruptibly(200);
        }
        return false;
    }

    /** Последовательность pointer/mouse-событий через JS: не зависит от оверлеев и работает для pointer-обработчиков. */
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

    /** Реальный клик через Selenium Actions; при перехвате браузером — фолбэк на JS-клик. */
    private void clickRealOrJs(WebDriver driver, WebElement element, String description) {
        try {
            scrollIntoView(driver, element);
            ImdbNotesExporter.sleepUninterruptibly(200);
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
            Integer current = ImdbNotesExporter.getExistingRating(driver);
            if (current != null && current == expected) {
                return true;
            }
            ImdbNotesExporter.sleepUninterruptibly(400);
        }
        return false;
    }

    /** Кнопка подтверждения Rate: по CSS и по тексту кнопок внутри панели; должна быть активной. */
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

    /** Клик через JS: обычные клики Selenium стабильно перехватываются элементами страницы. */
    private void jsClick(WebDriver driver, WebElement element) {
        scrollIntoView(driver, element);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }

    private void scrollIntoView(WebDriver driver, WebElement element) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", element
            );
            ImdbNotesExporter.sleepUninterruptibly(100);
        } catch (Exception ignored) {}
    }
}
