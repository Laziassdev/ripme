package com.rarchives.ripme.ripper;

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple wrapper around a FixedThreadPool.
 */
public class DownloadThreadPool {

    private static final Logger logger = LogManager.getLogger(DownloadThreadPool.class);
    private ThreadPoolExecutor threadPool = null;
    private final ConcurrentMap<String, DomainState> domainStates = new ConcurrentHashMap<>();
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
        threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(maxPerDomain);
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
        String domainKey = host.toLowerCase();
        DomainState domainState = domainStates.computeIfAbsent(domainKey, ignored -> new DomainState());
        return () -> scheduleDomainTask(domainState, task);
    }

    private void scheduleDomainTask(DomainState state, Runnable task) {
        state.enqueue(task);
        drainDomainQueue(state);
    }

    private void drainDomainQueue(DomainState state) {
        synchronized (state) {
            while (state.running < maxPerDomain) {
                Runnable next = state.poll();
                if (next == null) {
                    return;
                }
                state.running += 1;
                threadPool.execute(() -> {
                    try {
                        next.run();
                    } finally {
                        stateFinished(state);
                    }
                });
            }
        }
    }

    private void stateFinished(DomainState state) {
        synchronized (state) {
            state.running -= 1;
            if (!state.isEmpty()) {
                drainDomainQueue(state);
            }
        }
    }

    private static class DomainState {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private int running = 0;

        void enqueue(Runnable task) {
            queue.add(task);
        }

        Runnable poll() {
            return queue.poll();
        }

        boolean isEmpty() {
            return queue.isEmpty();
        }
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
