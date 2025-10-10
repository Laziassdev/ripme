package com.rarchives.ripme.utils;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility to keep track of ripper download limits while only counting
 * successfully completed downloads.
 */
public class DownloadLimitTracker {

    private final int maxDownloads;
    private final AtomicInteger successfulDownloads = new AtomicInteger(0);
    private final Set<String> trackedUrls = new HashSet<>();
    private boolean limitNotified = false;

    public DownloadLimitTracker(int maxDownloads) {
        this.maxDownloads = maxDownloads;
    }

    /**
     * Attempts to reserve a slot for the supplied URL.
     *
     * @param url URL that would be downloaded.
     * @return {@code true} if another download may be queued, {@code false} when
     *         the limit has already been exhausted.
     */
    public synchronized boolean tryAcquire(URL url) {
        if (!isEnabled()) {
            return true;
        }
        if (isLimitReachedInternal()) {
            return false;
        }
        String key = keyFor(url);
        if (trackedUrls.contains(key)) {
            return true;
        }
        if (successfulDownloads.get() + trackedUrls.size() >= maxDownloads) {
            return false;
        }
        trackedUrls.add(key);
        return true;
    }

    /**
     * Marks a download as successfully handled.
     *
     * @param url URL that completed successfully.
     * @return {@code true} once the maximum has been reached.
     */
    public synchronized boolean onSuccess(URL url) {
        if (!isEnabled()) {
            return false;
        }
        trackedUrls.remove(keyFor(url));
        successfulDownloads.incrementAndGet();
        return isLimitReachedInternal();
    }

    /**
     * Releases a previously reserved slot when a download fails or is skipped.
     *
     * @param url URL that failed or was skipped.
     */
    public synchronized void onFailure(URL url) {
        if (!isEnabled()) {
            return;
        }
        trackedUrls.remove(keyFor(url));
    }

    /**
     * @return {@code true} if the limiter is active.
     */
    public boolean isEnabled() {
        return maxDownloads > 0;
    }

    /**
     * @return {@code true} if the total number of successful downloads has
     *         reached the configured limit.
     */
    public boolean isLimitReached() {
        return isEnabled() && isLimitReachedInternal();
    }

    private boolean isLimitReachedInternal() {
        return successfulDownloads.get() >= maxDownloads;
    }

    public int getSuccessfulDownloads() {
        return successfulDownloads.get();
    }

    public int getMaxDownloads() {
        return maxDownloads;
    }

    public synchronized boolean shouldNotifyLimitReached() {
        if (!isEnabled() || !isLimitReachedInternal() || limitNotified) {
            return false;
        }
        limitNotified = true;
        return true;
    }

    private String keyFor(URL url) {
        return url == null ? "" : url.toExternalForm();
    }
}
