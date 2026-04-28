package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.DefaultListModel;

import org.junit.jupiter.api.Test;

public class MainWindowQueuePauseTest {

    @Test
    public void pausedQueueDoesNotStartUntilResumed() throws IOException {
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomains().clear();

        AtomicInteger startedCount = new AtomicInteger(0);
        mainWindow.setRipperLauncher((url, domain) -> startedCount.incrementAndGet());

        queue.addElement("http://example.com/first");
        mainWindow.setQueuePaused(true);

        mainWindow.ripNextAlbum();
        assertTrue(mainWindow.isQueuePaused(), "Queue should remain paused");
        assertEquals(0, startedCount.get(), "No queued rip should start while paused");
        assertEquals(1, queue.getSize(), "Queue items should remain queued while paused");

        mainWindow.setQueuePaused(false);
        assertEquals(1, startedCount.get(), "Queued rip should start once queue is resumed");
        assertEquals(0, queue.getSize(), "Queue should drain after resume");
    }
}
