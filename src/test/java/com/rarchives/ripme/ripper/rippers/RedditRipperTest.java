package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedditRipperTest {

    private int originalMaxDownloads;

    @BeforeEach
    void captureOriginalConfig() {
        originalMaxDownloads = Utils.getConfigInteger("maxdownloads", -1);
    }

    @AfterEach
    void restoreOriginalConfig() {
        Utils.setConfigInteger("maxdownloads", originalMaxDownloads);
    }

    @Test
    void eachRipperInstanceHasIndependentDownloadLimitTracker() throws Exception {
        Utils.setConfigInteger("maxdownloads", 2);

        RedditRipper clownsRipper = new RedditRipper(new URL("https://clowns.reddit.com"));
        RedditRipper animalsRipper = new RedditRipper(new URL("https://animals.reddit.com"));

        DownloadLimitTracker clownsTracker = downloadLimitTrackerOf(clownsRipper);
        DownloadLimitTracker animalsTracker = downloadLimitTrackerOf(animalsRipper);

        assertNotSame(clownsTracker, animalsTracker);

        URL sampleUrl = new URL("https://example.com/post1.jpg");
        assertTrue(clownsTracker.tryAcquire(sampleUrl));
        clownsTracker.onSuccess(sampleUrl);

        assertEquals(1, clownsTracker.getSuccessfulDownloads());
        assertEquals(0, animalsTracker.getSuccessfulDownloads());
    }

    @Test
    void trimsNonAlphanumericEdgesFromUserGid() throws Exception {
        RedditRipper ripper = new RedditRipper(new URL("https://www.reddit.com/user/_JudgmentNatasha/"));

        assertEquals("user_JudgmentNatasha",
                ripper.getGID(new URL("https://www.reddit.com/user/_JudgmentNatasha/")));
        assertEquals("user_JudgmentNatasha",
                ripper.getGID(new URL("https://www.reddit.com/user/JudgmentNatasha_/")));
        assertEquals("user_JudgmentNatasha",
                ripper.getGID(new URL("https://www.reddit.com/user/-JudgmentNatasha/")));
        assertEquals("user_JudgmentNatasha",
                ripper.getGID(new URL("https://www.reddit.com/user/JudgmentNatasha-/")));
        assertEquals("user_JudgmentNatasha",
                ripper.getGID(new URL("https://www.reddit.com/user/JudgmentNatasha#/")));
        assertEquals("user_Judgment_Natasha",
                ripper.getGID(new URL("https://www.reddit.com/user/Judgment_Natasha-/")));
    }

    @Test
    void supportsSearchPageGid() throws Exception {
        RedditRipper ripper = new RedditRipper(new URL("https://www.reddit.com/search/?q=missy+mae&type=media"));

        assertEquals("search_missy_mae_media",
                ripper.getGID(new URL("https://www.reddit.com/search/?q=missy+mae&type=media")));
        assertEquals("search_missy_mae_all",
                ripper.getGID(new URL("https://www.reddit.com/search/?q=missy+mae")));
        assertEquals("search_Cats_Dogs_media",
                ripper.getGID(new URL("https://www.reddit.com/search/?q=Cats%20%26%20Dogs&type=media")));
    }

    private DownloadLimitTracker downloadLimitTrackerOf(RedditRipper ripper) throws Exception {
        Field field = RedditRipper.class.getDeclaredField("downloadLimitTracker");
        field.setAccessible(true);
        return (DownloadLimitTracker) field.get(ripper);
    }
}
