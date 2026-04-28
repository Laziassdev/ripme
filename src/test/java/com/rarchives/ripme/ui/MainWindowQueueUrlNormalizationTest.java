package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

public class MainWindowQueueUrlNormalizationTest {

    @Test
    public void normalizesInstagramTrackingParameters() {
        assertEquals(
                "https://www.instagram.com/beccapoppyhaigh",
                MainWindow.normalizeQueueUrl("https://www.instagram.com/beccapoppyhaigh/?g=5"));
        assertEquals(
                "https://www.instagram.com/lyssaurora",
                MainWindow.normalizeQueueUrl(
                        "https://www.instagram.com/lyssaurora/?e=ee8f574d-9a29-4bb3-b0e5-e5a04685a595&g=5"));
    }

    @Test
    public void leavesNonInstagramUrlsUntouched() {
        String reddit = "https://www.reddit.com/search/?q=missy+mae&type=media";
        assertEquals(reddit, MainWindow.normalizeQueueUrl(reddit));
    }

    @Test
    public void stripsCommonTrackingParametersForNonInstagramUrls() {
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&utm_source=twitter&fbclid=abc"));
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&amp;utm_source=twitter&amp;fbclid=abc"));
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&utm%5Fsource=twitter"));
    }

    @Test
    public void addUrlToQueueStoresNormalizedInstagramUrl() throws IOException {
        MainWindow mainWindow = new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://www.instagram.com/beccapoppyhaigh/?g=5");

        assertEquals("https://www.instagram.com/beccapoppyhaigh", MainWindow.getQueueListModel().get(0));
    }

    @Test
    public void addUrlToQueueDeduplicatesEquivalentUrls() throws IOException {
        MainWindow mainWindow = new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://example.com/post?id=42&utm_source=twitter");
        MainWindow.addUrlToQueue("https://example.com/post?id=42");

        assertEquals(1, MainWindow.getQueueListModel().size());
        assertEquals("https://example.com/post?id=42", MainWindow.getQueueListModel().get(0));
    }
}
