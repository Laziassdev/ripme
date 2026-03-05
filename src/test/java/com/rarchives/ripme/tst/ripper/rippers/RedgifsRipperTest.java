package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.RedditRipper;
import com.rarchives.ripme.ripper.rippers.RedgifsRipper;
import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedgifsRipperTest extends RippersTest {

    /**
     * Rips correctly formatted URL directly from Redgifs
     */
    @Test
    public void testRedgifsGoodURL() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/watch/ashamedselfishcoypu").toURL());
        testRipper(ripper);
    }

    /**
     * Rips gifdeliverynetwork URL's by redirecting them to proper redgifs url
     */
    @Test
    public void testRedgifsBadRL() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.gifdeliverynetwork.com/consideratetrustworthypigeon").toURL());
        testRipper(ripper);
    }

    @Test
    public void testRedgifsIfrURLIsSanitizedToWatch() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/ifr/limegreenstarkkiwi").toURL());
        URL sanitized = ripper.sanitizeURL(new URI("https://www.redgifs.com/ifr/limegreenstarkkiwi").toURL());
        assertEquals("https://www.redgifs.com/watch/limegreenstarkkiwi", sanitized.toExternalForm());
    }

    @Test
    public void testRedgifsDirectImageURLIsSanitizedToWatch() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://i.redgifs.com/i/roastedstimulatinggrebe.jpg?v=wzil5999.jpg").toURL());
        URL sanitized = ripper.sanitizeURL(new URI("https://i.redgifs.com/i/roastedstimulatinggrebe.jpg?v=wzil5999.jpg").toURL());
        assertEquals("https://www.redgifs.com/watch/roastedstimulatinggrebe", sanitized.toExternalForm());
    }

    /**
     * Rips a Redgifs profile
     */
    @Test
    public void testRedgifsProfile() throws IOException, URISyntaxException {
        RedgifsRipper ripper  = new RedgifsRipper(new URI("https://www.redgifs.com/users/ra-kunv2").toURL());
        testRipper(ripper);
    }

    /**
     * Rips a Redgif search
     * @throws IOException
     */
    @Test
    public void testRedgifsSearch() throws IOException, URISyntaxException {
        RedgifsRipper ripper  = new RedgifsRipper(new URI("https://www.redgifs.com/search?query=take+a+shot+every+time").toURL());
        testRipper(ripper);
    }

    /**
     * Rips Redgif tags
     * @throws IOException
     */
    @Test
    public void testRedgifsTags() throws IOException, URISyntaxException {
        RedgifsRipper ripper  = new RedgifsRipper(new URI("https://www.redgifs.com/gifs/animation,sfw,funny?order=best&tab=gifs").toURL());
        testRipper(ripper);
    }

    @Test
    public void testRedgifsNicheGID() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/niches/puffies").toURL());
        assertEquals("puffies", ripper.getGID(new URI("https://www.redgifs.com/niches/puffies").toURL()));
    }

    @Test
    public void testRedgifsNicheAlbumTitleUsesNichesPrefix() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/niches/puffies").toURL());
        assertEquals("redgifs_niches_puffies", ripper.getAlbumTitle(new URI("https://www.redgifs.com/niches/puffies").toURL()));
    }

    @Test
    public void testRedgifsNicheAlbumTitleIgnoresAlbumTitlesSaveSetting() throws Exception {
        String previous = com.rarchives.ripme.utils.Utils.getConfigString("album_titles.save", "true");
        com.rarchives.ripme.utils.Utils.setConfigString("album_titles.save", "false");
        try {
            RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/niches/trans-women").toURL());
            ripper.setWorkingDir(new URI("https://www.redgifs.com/niches/trans-women").toURL());
            assertTrue(ripper.getWorkingDir().getName().startsWith("redgifs_niches_trans-women"));
        } finally {
            com.rarchives.ripme.utils.Utils.setConfigString("album_titles.save", previous);
        }
    }

    @Test
    public void testExtractMaxPagesTopLevelPages() {
        JSONObject json = new JSONObject("{\"pages\":42}");
        assertEquals(42, RedgifsRipper.extractMaxPages(json, 1));
    }

    @Test
    public void testExtractMaxPagesNestedPagination() {
        JSONObject json = new JSONObject("{\"page\":{\"totalPages\":7}}");
        assertEquals(7, RedgifsRipper.extractMaxPages(json, 1));
    }

    @Test
    public void testExtractMaxPagesFallbackWhenMissing() {
        JSONObject json = new JSONObject("{\"gifs\":[]}");
        assertEquals(3, RedgifsRipper.extractMaxPages(json, 3));
    }

    @Test
    public void testExtractMaxPagesFromTotalAndCount() {
        JSONObject json = new JSONObject("{\"total\":121,\"count\":40}");
        assertEquals(4, RedgifsRipper.extractMaxPages(json, 1));
    }

    @Test
    public void testExtractUrlsWhenEntriesAreInItemsArray() throws IOException, URISyntaxException {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/niches/puffies").toURL());
        JSONObject json = new JSONObject("""
                {
                  "items": [
                    {
                      "gallery": null,
                      "urls": {
                        "hd": "https://media.example.com/video1.mp4"
                      }
                    }
                  ]
                }
                """);

        List<String> urls = ripper.getURLsFromJSON(json);
        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://media.example.com/video1.mp4"));
    }



    @Test
    public void testNicheURLDefaultsToGifsTypeAndMapsVerified() throws Exception {
        RedgifsRipper ripper = new RedgifsRipper(new URI("https://www.redgifs.com/niches/puffies?verified=1").toURL());
        var method = RedgifsRipper.class.getDeclaredMethod("getNicheURL");
        method.setAccessible(true);

        URL nicheURL = (URL) method.invoke(ripper);
        String query = nicheURL.toURI().getQuery();

        assertTrue(query.contains("type=g"));
        assertTrue(query.contains("verified=yes"));
    }

    @Test
    @Tag("flaky")
    public void testRedditRedgifs() throws IOException, URISyntaxException {
        RedditRipper ripper = new RedditRipper(new URI("https://www.reddit.com/r/nsfwhardcore/comments/ouz5bw/me_cumming_on_his_face/").toURL());
        testRipper(ripper);
    }
}
