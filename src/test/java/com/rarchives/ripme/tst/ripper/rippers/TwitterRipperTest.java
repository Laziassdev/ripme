package com.rarchives.ripme.tst.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.TwitterRipper;

public class TwitterRipperTest extends RippersTest {

    @Test
    @Tag("flaky")
    public void testTwitterUserRip() throws IOException, URISyntaxException {
        TwitterRipper ripper = new TwitterRipper(new URI("https://twitter.com/danngamber01/media").toURL());
        testRipper(ripper);
    }

    @Test
    @Tag("flaky")
    public void testTwitterSearchRip() throws IOException, URISyntaxException {
        TwitterRipper ripper = new TwitterRipper(
                new URI("https://twitter.com/search?f=tweets&q=from%3Aalinalixxx%20filter%3Aimages&src=typd").toURL());
        testRipper(ripper);
    }

    @Test
    public void testSanitizeXDotComAccountUrl() throws Exception {
        TwitterRipper ripper = new TwitterRipper(new URI("https://x.com/exampleuser/media").toURL());
        assertEquals("account_exampleuser", ripper.getGID(ripper.getURL()));
        assertTrue(ripper.canRip(new URI("https://x.com/exampleuser").toURL()));
    }

    @Test
    public void testProfileUrlWithLangQueryParam() throws Exception {
        URL url = new URI("https://x.com/Sospoonable?lang=en").toURL();
        TwitterRipper ripper = new TwitterRipper(url);
        assertEquals("account_Sospoonable", ripper.getGID(ripper.getURL()));
        assertEquals("https://x.com/Sospoonable", ripper.getURL().toExternalForm());
        assertTrue(ripper.canRip(url));
    }

    @Test
    public void testProfileMediaUrlWithLangQueryParam() throws Exception {
        URL url = new URI("https://x.com/exampleuser/media?lang=en").toURL();
        TwitterRipper ripper = new TwitterRipper(url);
        assertEquals("account_exampleuser", ripper.getGID(ripper.getURL()));
        assertEquals("https://x.com/exampleuser/media", ripper.getURL().toExternalForm());
    }

    @Test
    public void testSanitizeSearchUrlPreservesQuery() throws Exception {
        TwitterRipper ripper = new TwitterRipper(
                new URI("https://x.com/search?q=from%3Aexampleuser%20filter%3Aimages&src=typd").toURL());
        assertTrue(ripper.getGID(ripper.getURL()).startsWith("search_"));
        assertTrue(ripper.getGID(ripper.getURL()).contains("exampleuser"));
        assertTrue(ripper.getGID(ripper.getURL()).contains("filter"));
        assertTrue(ripper.getGID(ripper.getURL()).contains("images"));
    }

    @Test
    public void testExtractQueryParam() {
        assertEquals("from:user filter:images",
                TwitterRipper.extractQueryParam("q=from%3Auser%20filter%3Aimages&src=typd", "q"));
        assertEquals(null, TwitterRipper.extractQueryParam("src=typd", "q"));
    }

    @Test
    public void testExtractUserRestId() throws Exception {
        JSONObject json = loadFixture("twitter/user_by_screen_name.json");
        assertEquals("1234567890", TwitterRipper.extractUserRestId(json));
    }

    @Test
    public void testParseTimelinePageExtractsTweetsAndCursor() throws Exception {
        JSONObject json = loadFixture("twitter/user_tweets_page.json");
        TwitterRipper.TimelinePage page = TwitterRipper.parseTimelinePage(json);
        assertEquals(4, page.tweets.size());
        assertEquals("CURSOR_BOTTOM_ABC", page.bottomCursor);
    }

    @Test
    public void testRewritePhotoUrl() {
        assertEquals("https://pbs.twimg.com/media/PhotoAbc123.jpg?format=jpg&name=orig",
                TwitterRipper.rewritePhotoUrl("https://pbs.twimg.com/media/PhotoAbc123.jpg"));
        assertEquals("https://pbs.twimg.com/media/PhotoAbc123.png?format=png&name=orig",
                TwitterRipper.rewritePhotoUrl("https://pbs.twimg.com/media/PhotoAbc123.png"));
        assertEquals("https://pbs.twimg.com/media/PhotoAbc123.jpg?format=jpg&name=orig",
                TwitterRipper.rewritePhotoUrl("https://pbs.twimg.com/media/PhotoAbc123.jpg:large"));
    }

    @Test
    public void testExtractMediaUrlsHonorsFilters() throws Exception {
        JSONObject json = loadFixture("twitter/user_tweets_page.json");
        TwitterRipper.TimelinePage page = TwitterRipper.parseTimelinePage(json);

        List<String> withDefaults = TwitterRipper.extractMediaUrls(page.tweets.get(0), true, true);
        assertEquals(1, withDefaults.size());
        assertTrue(withDefaults.get(0).contains("format=jpg"));
        assertTrue(withDefaults.get(0).contains("name=orig"));
        assertTrue(withDefaults.get(0).contains(".jpg?"));

        List<String> videoUrls = TwitterRipper.extractMediaUrls(page.tweets.get(1), true, true);
        assertEquals(1, videoUrls.size());
        assertEquals("https://video.twimg.com/ext_tw_video/high.mp4", videoUrls.get(0));

        List<String> skipRetweets = TwitterRipper.extractMediaUrls(page.tweets.get(2), false, true);
        assertTrue(skipRetweets.isEmpty());

        List<String> includeRetweets = TwitterRipper.extractMediaUrls(page.tweets.get(2), true, true);
        assertEquals(1, includeRetweets.size());
        assertTrue(includeRetweets.get(0).contains("RetweetPhoto"));

        List<String> excludeReplies = TwitterRipper.extractMediaUrls(page.tweets.get(3), true, true);
        assertTrue(excludeReplies.isEmpty());

        List<String> includeReplies = TwitterRipper.extractMediaUrls(page.tweets.get(3), true, false);
        assertEquals(1, includeReplies.size());
        assertTrue(includeReplies.get(0).contains("ReplyPhoto"));
    }

    @Test
    public void testOldestTweetDate() throws Exception {
        JSONObject json = loadFixture("twitter/user_tweets_page.json");
        TwitterRipper.TimelinePage page = TwitterRipper.parseTimelinePage(json);
        assertEquals("2026-07-15", TwitterRipper.oldestTweetDate(page.tweets));
    }

    @Test
    public void testUnwrapTweetResultHandlesVisibilityWrapper() throws Exception {
        JSONObject json = loadFixture("twitter/user_tweets_page.json");
        TwitterRipper.TimelinePage page = TwitterRipper.parseTimelinePage(json);
        assertNotNull(page.tweets.get(1).optJSONObject("legacy"));
        assertEquals("110", page.tweets.get(1).optJSONObject("legacy").optString("id_str"));
    }

    @Test
    public void testDefaultBearerTokenPresent() {
        assertFalse(TwitterRipper.DEFAULT_BEARER_TOKEN.isBlank());
        assertTrue(TwitterRipper.DEFAULT_BEARER_TOKEN.startsWith("AAAA"));
    }

    private static JSONObject loadFixture(String resourcePath) throws IOException {
        try (InputStream in = TwitterRipperTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing test fixture: " + resourcePath);
            }
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                return new JSONObject(scanner.hasNext() ? scanner.next() : "{}");
            }
        }
    }
}
