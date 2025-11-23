package com.rarchives.ripme.ripper;

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple wrapper around a cached thread pool with per-domain throttling.
 */
public class DownloadThreadPool {

    private static final Logger logger = LogManager.getLogger(DownloadThreadPool.class);
    private ExecutorService threadPool = null;
    private final ConcurrentMap<String, Semaphore> domainPermits = new ConcurrentHashMap<>();
    private int maxPerDomain;

    public DownloadThreadPool() {
        initialize("Main");
    }

    public DownloadThreadPool(String threadPoolName) {
        initialize(threadPoolName);
    }
    
    /**
     * Initializes the threadpool.
     * @param threadPoolName Name of the threadpool.
     */
    private void initialize(String threadPoolName) {
        maxPerDomain = Utils.getConfigInteger("threads.size", 10);
        logger.debug("Initializing " + threadPoolName + " thread pool with up to " + maxPerDomain
                + " threads per domain");
        threadPool = Executors.newCachedThreadPool();
    }
    /**
     * For adding threads to execution pool.
     * @param t
     *      Thread to be added.
     */
    public void addThread(Runnable t) {
        threadPool.execute(t);
    }

    public void addThread(URL url, Runnable t) {
        threadPool.execute(wrapWithDomainLimit(url, t));
    }

    private Runnable wrapWithDomainLimit(URL url, Runnable task) {
        if (url == null) {
            return task;
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return task;
        }
        final Semaphore semaphore = domainPermits.computeIfAbsent(host.toLowerCase(),
                ignored -> new Semaphore(maxPerDomain));
        return () -> {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for permit for domain {}", host, e);
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        };
    }

    /**
     * Tries to shutdown threadpool.
     */
    public void waitForThreads() {
        threadPool.shutdown();
        try {
            threadPool.awaitTermination(3600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("[!] Interrupted while waiting for threads to finish: ", e);
        }
    }
}
