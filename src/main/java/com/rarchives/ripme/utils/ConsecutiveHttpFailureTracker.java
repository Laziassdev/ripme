package com.rarchives.ripme.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks consecutive HTTP 4xx/5xx failures for a single ripper instance.
 */
public class ConsecutiveHttpFailureTracker {

    private static final Pattern STATUS_CODE_PATTERN = Pattern.compile(
            "(?:status\\s*(?:code)?\\s*|HTTP\\s+)([45]\\d{2})\\b", Pattern.CASE_INSENSITIVE);

    private final int threshold;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public ConsecutiveHttpFailureTracker(int threshold) {
        this.threshold = threshold;
    }

    public boolean isEnabled() {
        return threshold > 0;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * @return {@code true} when the configured threshold has been reached.
     */
    public boolean recordHttpError(String reason) {
        if (!isEnabled() || !isHttpError(reason)) {
            return false;
        }
        return consecutiveFailures.incrementAndGet() >= threshold;
    }

    /**
     * @return {@code true} when the configured threshold has been reached.
     */
    public boolean recordHttpStatusCode(int statusCode) {
        if (!isEnabled() || statusCode < 400 || statusCode > 599) {
            return false;
        }
        return consecutiveFailures.incrementAndGet() >= threshold;
    }

    public void reset() {
        consecutiveFailures.set(0);
    }

    public static boolean isHttpError(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        if (reason.contains("HTTP error fetching URL")) {
            return true;
        }
        Matcher matcher = STATUS_CODE_PATTERN.matcher(reason);
        if (matcher.find()) {
            int code = Integer.parseInt(matcher.group(1));
            return code >= 400 && code <= 599;
        }
        return false;
    }
}
