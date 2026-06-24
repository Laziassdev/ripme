package com.rarchives.ripme.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConsecutiveHttpFailureTrackerTest {

    @Test
    void tracksConsecutiveHttpErrors() {
        ConsecutiveHttpFailureTracker tracker = new ConsecutiveHttpFailureTracker(3);

        assertFalse(tracker.recordHttpError("Non-retriable status code 400 while downloading https://example.com/a.jpg"));
        assertEquals(1, tracker.getConsecutiveFailures());
        assertFalse(tracker.recordHttpError("HTTP status code 500 while downloading https://example.com/b.jpg"));
        assertEquals(2, tracker.getConsecutiveFailures());
        assertTrue(tracker.recordHttpError("Retriable status code 503 while downloading https://example.com/c.jpg"));
        assertEquals(3, tracker.getConsecutiveFailures());
    }

    @Test
    void ignoresNonHttpErrors() {
        ConsecutiveHttpFailureTracker tracker = new ConsecutiveHttpFailureTracker(2);

        assertFalse(tracker.recordHttpError("Download interrupted"));
        assertEquals(0, tracker.getConsecutiveFailures());
        assertFalse(tracker.recordHttpError("Failed to download https://example.com/a.jpg"));
        assertEquals(0, tracker.getConsecutiveFailures());
    }

    @Test
    void resetClearsCount() {
        ConsecutiveHttpFailureTracker tracker = new ConsecutiveHttpFailureTracker(2);
        tracker.recordHttpError("HTTP status code 400 while downloading https://example.com/a.jpg");
        tracker.reset();
        assertEquals(0, tracker.getConsecutiveFailures());
        assertFalse(tracker.recordHttpError("HTTP status code 400 while downloading https://example.com/b.jpg"));
    }

    @Test
    void disabledWhenThresholdZero() {
        ConsecutiveHttpFailureTracker tracker = new ConsecutiveHttpFailureTracker(0);
        assertFalse(tracker.isEnabled());
        assertFalse(tracker.recordHttpError("HTTP status code 500 while downloading https://example.com/a.jpg"));
        assertEquals(0, tracker.getConsecutiveFailures());
    }

    @Test
    void isHttpErrorDetectsKnownMessages() {
        assertTrue(ConsecutiveHttpFailureTracker.isHttpError("HTTP error fetching URL"));
        assertTrue(ConsecutiveHttpFailureTracker.isHttpError("HTTP status 404 while downloading from https://x"));
        assertFalse(ConsecutiveHttpFailureTracker.isHttpError("Connection reset"));
    }
}
