package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Date;

import org.junit.jupiter.api.Test;

public class HistoryNormalizationMergeTest {

    @Test
    public void mergesEntriesThatNormalizeToSameUrl() {
        History history = new History();

        HistoryEntry first = new HistoryEntry();
        first.url = "https://example.com/post?id=42&utm_source=twitter";
        first.count = 7;
        first.latestCount = 2;
        first.startDate = new Date(1000);
        first.modifiedDate = new Date(2000);
        first.title = "Album";
        first.dir = "/tmp/one";
        history.add(first);

        HistoryEntry second = new HistoryEntry();
        second.url = "https://example.com/post?id=42&fbclid=abc";
        second.count = 3;
        second.latestCount = 1;
        second.startDate = new Date(500);
        second.modifiedDate = new Date(3000);
        second.selected = true;
        history.add(second);

        history.normalizeAndMergeUrls(MainWindow::normalizeQueueUrl);

        assertEquals(1, history.toList().size());
        HistoryEntry merged = history.toList().get(0);
        assertEquals("https://example.com/post?id=42", merged.url);
        assertEquals(10, merged.count);
        assertEquals(3, merged.latestCount);
        assertEquals(new Date(500), merged.startDate);
        assertEquals(new Date(3000), merged.modifiedDate);
    }
}
