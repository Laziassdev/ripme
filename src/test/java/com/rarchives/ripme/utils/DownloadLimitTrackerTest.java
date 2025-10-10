package com.rarchives.ripme.utils;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class DownloadLimitTrackerTest {

    @Test
    void trackerOnlyCountsSuccessfulDownloads() throws Exception {
        DownloadLimitTracker tracker = new DownloadLimitTracker(2);
        URL first = new URL("https://example.com/image1.jpg");
        URL second = new URL("https://example.com/image2.jpg");

        assertTrue(tracker.tryAcquire(first));
        tracker.onFailure(first);
        assertEquals(0, tracker.getSuccessfulDownloads());
        assertFalse(tracker.isLimitReached());

        assertTrue(tracker.tryAcquire(first));
        tracker.onSuccess(first);
        assertEquals(1, tracker.getSuccessfulDownloads());
        assertFalse(tracker.isLimitReached());

        assertTrue(tracker.tryAcquire(second));
        tracker.onSuccess(second);
        assertEquals(2, tracker.getSuccessfulDownloads());
        assertTrue(tracker.isLimitReached());
    }

    @Test
    void trackersDoNotShareState() throws Exception {
        DownloadLimitTracker trackerA = new DownloadLimitTracker(1);
        DownloadLimitTracker trackerB = new DownloadLimitTracker(1);

        URL urlA = new URL("https://example.com/a.jpg");
        URL urlB = new URL("https://example.com/b.jpg");

        assertTrue(trackerA.tryAcquire(urlA));
        trackerA.onSuccess(urlA);
        assertTrue(trackerA.isLimitReached());

        assertTrue(trackerB.tryAcquire(urlB));
        assertFalse(trackerB.isLimitReached());
        trackerB.onSuccess(urlB);
        assertTrue(trackerB.isLimitReached());
    }
}
