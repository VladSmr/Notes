package ru.importer.notes.imdb;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты ImdbRatingClicker на моках WebDriver: точный матч «Rate N»
 * (без коллизии с «Rate 10») и ретрай на TimeoutException в setRating.
 */
class ImdbRatingTest extends ImdbTestSupport {

    // ------------------------------------------------------------------
    // Точный матч «Rate N» (без коллизии с «Rate 10»)
    // ------------------------------------------------------------------

    @Test
    void findStarButton_exactLabelMatch_notPrefix() {
        // Префиксный селектор ^="Rate 1" матчит и «Rate 10»; в DOM «Rate 10» может идти раньше.
        WebDriver driver = mock(WebDriver.class);
        WebElement rateTen = mock(WebElement.class);
        when(rateTen.getAttribute("aria-label")).thenReturn("Rate 10");
        when(rateTen.isDisplayed()).thenReturn(true);
        WebElement rateOne = mock(WebElement.class);
        when(rateOne.getAttribute("aria-label")).thenReturn("Rate 1");
        when(rateOne.isDisplayed()).thenReturn(true);
        when(driver.findElements(any(By.class))).thenReturn(List.of(rateTen, rateOne));

        WebDriverWait wait = mock(WebDriverWait.class);

        WebElement star = clicker.findStarButton(driver, wait, 1);
        assertSame(rateOne, star, "должна найтись «Rate 1», а не первая префиксная «Rate 10»");

        WebElement starTen = clicker.findStarButton(driver, wait, 10);
        assertSame(rateTen, starTen);
    }

    // ------------------------------------------------------------------
    // Ретрай на TimeoutException в setRating
    // ------------------------------------------------------------------

    @Test
    void setRating_onTimeout_reloadsPageOnceAndRetriesBeforeGivingUp() {
        WebDriver driver = mockDriver(Collections.emptyList());
        when(driver.getCurrentUrl()).thenReturn("https://www.imdb.com/title/tt13622970/");

        WebDriverWait wait = mock(WebDriverWait.class);
        when(wait.until(any())).thenThrow(new TimeoutException());

        assertThrows(TimeoutException.class, () -> clicker.setRating(driver, wait, 7));

        // Одна перезагрузка страницы и ровно две попытки перед сдачей.
        verify(driver, times(1)).getCurrentUrl();
        verify(driver, times(1)).get("https://www.imdb.com/title/tt13622970/");
        verify(wait, times(2)).until(any());
    }
}
