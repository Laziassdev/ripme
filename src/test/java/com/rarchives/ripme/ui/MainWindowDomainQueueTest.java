package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.DefaultListModel;

import org.junit.jupiter.api.Test;

public class MainWindowDomainQueueTest {

    @Test
    public void queuedAlbumsStartAfterDomainIsFree() throws IOException, InterruptedException {
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomains().clear();

        List<String> startedDomains = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLatch = new CountDownLatch(3);
        CountDownLatch finishLatch = new CountDownLatch(3);

        mainWindow.setRipperLauncher((url, domain) -> {
            startedDomains.add(domain + ":" + url);
            mainWindow.getActiveDomains().add(domain);
            startLatch.countDown();

            new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mainWindow.onRipperFinished(domain, null);
                finishLatch.countDown();
            }).start();
        });

        queue.addElement("http://example.com/first");
        queue.addElement("http://other.com/other");
        queue.addElement("http://example.com/second");

        mainWindow.ripNextAlbum();

        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "All rippers should have started");
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "All rippers should have finished");

        List<String> expectedOrder = List.of(
                "example.com:http://example.com/first",
                "other.com:http://other.com/other",
                "example.com:http://example.com/second");
        assertEquals(expectedOrder, startedDomains, "Queue should start unrelated domains while waiting for same-domain completion");
        assertTrue(mainWindow.getActiveDomains().isEmpty(), "All active domains should be cleared after completion");
        assertEquals(0, queue.getSize(), "Queue should be empty after processing");
    }
}
