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
}
