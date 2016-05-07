package com.vaadin.hummingbird.contexttest.ui;

import org.junit.Assert;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;

import com.vaadin.hummingbird.testutil.PhantomJSTest;

public abstract class AbstractContextIT extends PhantomJSTest {

    private static final String JETTY_CONTEXT = "/context";

    private static final String RED = "rgba(255, 0, 0, 1)";
    private static final String BLUE = "rgba(0, 0, 255, 1)";

    protected abstract String getAppContext();

    protected abstract void verifyCorrectUI();

    @Override
    protected String getTestPath() {
        return JETTY_CONTEXT + getAppContext();
    }

    @Test
    public void testStyleInjection() {
        open();
        verifyCorrectUI();
        styleInjection();
    }

    @Test
    public void testScriptInjection() {
        open();
        verifyCorrectUI();
        scriptInjection();
    }

    private void styleInjection() {
        // Initial stylesheet makes all text red
        Assert.assertEquals(RED, findElementById("hello").getCssValue("color"));

        // Inject stylesheet which makes text blue
        findElementById("loadBlue").click();

        // Wait as the framework will not stop until the stylesheet is loaded
        waitUntil(new ExpectedCondition<Boolean>() {
            @Override
            public Boolean apply(WebDriver input) {
                return findElementById("hello").getCssValue("color")
                        .equals(BLUE);
            }
        });
    }

    private void scriptInjection() {
        // Initial JS registers a body click handler
        findElement(By.cssSelector("body")).click();
        String addedBodyText = findElement(By.cssSelector(".body-click-added"))
                .getText();
        Assert.assertEquals(
                "Click on body, reported by Javascript click handler",
                addedBodyText);

        // Inject scripts
        findElementById("loadJs").click();
        String addedJsText = findElementById("appended-element").getText();
        Assert.assertEquals("Added by script", addedJsText);
    }

    protected WebElement findElementById(String id) {
        return findElement(By.id(id));
    }

}