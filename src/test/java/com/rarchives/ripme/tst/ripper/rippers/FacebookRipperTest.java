package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.FacebookRipper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FacebookRipperTest {

    private static class TestableFacebookRipper extends FacebookRipper {
        TestableFacebookRipper(URL url) throws java.io.IOException {
            super(url);
        }

        List<String> extract(Document doc) throws java.io.UnsupportedEncodingException {
            return super.getURLsFromPage(doc);
        }
    }

    @Test
    public void testExtractedMediaUrlsDecodeHtmlAmpersands() throws Exception {
        String html = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/file.jpg?foo=1&amp;bar=2\">"
                + "</head><body></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/file.jpg?foo=1&bar=2"));
    }

    @Test
    public void testExtractsImagesFromEscapedScriptJson() throws Exception {
        // Facebook stores media inside <script> JSON with escaped slashes/unicode; these must be found.
        String html = "<html><body><script type=\"application/json\">"
                + "{\"image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo1.jpg?oh=abc\\u0026oe=def\"}}"
                + "{\"image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo2.png?stp=xyz\"}}"
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/photo1.jpg?oh=abc&oe=def"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/photo2.png?stp=xyz"));
    }

    @Test
    public void testReelPrefersHdVideoSource() throws Exception {
        String html = "<html><body><script type=\"application/json\">"
                + "{\"playable_url\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/sd.mp4?efg=1\","
                + "\"playable_url_quality_hd\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/hd.mp4?abc=1\"}"
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/reel/123456789");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/reel/123456789"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://video.xx.fbcdn.net/v/hd.mp4?abc=1"));
        // HD must be ordered ahead of SD.
        assertTrue(urls.indexOf("https://video.xx.fbcdn.net/v/hd.mp4?abc=1")
                < urls.indexOf("https://video.xx.fbcdn.net/v/sd.mp4?efg=1"));
    }

    @Test
    public void testJunkUiAssetsAreFiltered() throws Exception {
        String html = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/real.jpg\">"
                + "</head><body><script>"
                + "\"https:\\/\\/static.xx.fbcdn.net\\/rsrc.php\\/icon.png\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/real.jpg"));
        assertTrue(urls.stream().noneMatch(u -> u.contains("rsrc.php")));
    }
}
