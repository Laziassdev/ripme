package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.FacebookRipper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FacebookRipperTest {

    private static class TestableFacebookRipper extends FacebookRipper {
        TestableFacebookRipper(URL url) throws java.io.IOException {
            super(url);
        }

        List<String> extract(Document doc) throws java.io.UnsupportedEncodingException {
            return super.getURLsFromPage(doc);
        }

        // Keep tests hermetic by default: never touch the network unless a subclass supplies pages.
        @Override
        protected Document fetchPhotoPage(String fbid) {
            return null;
        }

        @Override
        protected Document fetchPhotoListingPage(URL listingUrl) {
            return null;
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

    @Test
    public void testImageSizeVariantsCollapseToLargest() throws Exception {
        // Same photo served at several sizes must collapse to a single, largest variant.
        String base = "https://scontent.xx.fbcdn.net/v/t39.30808-1/123_456_n.jpg";
        String html = "<html><body><script>"
                + "\"" + base + "?stp=cp0_dst-jpg_s80x80_tt6&_nc_cat=1\""
                + "\"" + base + "?stp=dst-jpg_s480x480_tt6&_nc_cat=1\""
                + "\"" + base + "?stp=dst-jpg_s320x320_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/photos");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/photos"));
        List<String> urls = ripper.extract(doc);

        assertEquals(1, urls.size(), "All size-variants of one photo should collapse to a single URL");
        assertTrue(urls.get(0).contains("s480x480"), "The largest available rendition should be kept");
    }

    @Test
    public void testReelRenditionsCollapseToOnePerVideoId() throws Exception {
        // One reel exposed as several renditions plus an audio-only track must collapse to a
        // single file: the highest-resolution rendition.
        String hd = videoUrl("dash.mp4", "{\"vencode_tag\":\"dash_vp9-basic-gen2_1080p\",\"video_id\":42,\"bitrate\":2000000}");
        String sd = videoUrl("dash2.mp4", "{\"vencode_tag\":\"dash_vp9-basic-gen2_360p\",\"video_id\":42,\"bitrate\":300000}");
        String prog = videoUrl("prog.mp4", "{\"vencode_tag\":\"progressive_h264-basic-gen2_360p\",\"video_id\":42,\"bitrate\":400000}");
        String audio = videoUrl("audio.mp4", "{\"vencode_tag\":\"dash_ln_heaac_vbr3_audio\",\"video_id\":42,\"bitrate\":50000}");

        String html = "<html><body><script>"
                + "\"" + hd + "\"\"" + sd + "\"\"" + prog + "\"\"" + audio + "\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/reel/42");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/reel/42"));
        List<String> urls = ripper.extract(doc);

        assertEquals(1, urls.size(), "One reel should produce exactly one downloadable file");
        assertTrue(urls.get(0).contains("dash.mp4"), "The highest-resolution (1080p) rendition should be preferred");
    }

    @Test
    public void testPhotoListingCrawlsPermalinksForFullResolution() throws Exception {
        // A /photos listing only embeds tiny thumbnails for most photos. The ripper must follow each
        // photo permalink (fbid) and pull the full-resolution image from that photo's page.
        String listingHtml = "<html><body><script>"
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=700001&set=a.1\""
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=700002&set=a.1\""
                // Only thumbnails for those photos are present on the listing itself.
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/700001_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/700002_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, Document> photoPages = new HashMap<>();
        photoPages.put("700001", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/700001_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=700001"));
        photoPages.put("700002", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/700002_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=700002"));

        CrawlingFacebookRipper ripper =
                new CrawlingFacebookRipper(new URL("https://www.facebook.com/example/photos"), photoPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(2, urls.size(), "Each photo should resolve to one full-resolution image");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/700001_n.jpg?_nc_cat=1"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/700002_n.jpg?_nc_cat=1"));
        assertTrue(urls.stream().noneMatch(u -> u.contains("s74x74")), "Thumbnails must not be downloaded");
    }

    @Test
    public void testMbasicPaginationEnumeratesEntireAlbum() throws Exception {
        // The desktop listing only exposes a couple of photos; mbasic must be walked (following its
        // "See more photos" link) so every photo in the album is discovered and resolved full-res.
        String listingHtml = "<html><body><script>"
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=900001&set=a.1\""
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, Document> listingPages = new HashMap<>();
        // mbasic page 1: two photos + a "See more photos" link to page 2.
        listingPages.put("https://mbasic.facebook.com/example/photos", Jsoup.parse(
                "<html><body>"
                        + "<a href=\"/photo.php?fbid=900001&id=1\">img</a>"
                        + "<a href=\"/photo.php?fbid=900002&id=1\">img</a>"
                        + "<a href=\"/example/photos?cursor=PAGE2\">See more photos</a>"
                        + "</body></html>",
                "https://mbasic.facebook.com/example/photos"));
        // mbasic page 2: one more photo, no further pagination link.
        listingPages.put("https://mbasic.facebook.com/example/photos?cursor=PAGE2", Jsoup.parse(
                "<html><body>"
                        + "<a href=\"/photo.php?fbid=900003&id=1\">img</a>"
                        + "</body></html>",
                "https://mbasic.facebook.com/example/photos?cursor=PAGE2"));

        Map<String, Document> photoPages = new HashMap<>();
        for (String fbid : new String[] {"900001", "900002", "900003"}) {
            photoPages.put(fbid, Jsoup.parse(
                    "<html><head><meta property=\"og:image\" "
                            + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/" + fbid + "_n.jpg?_nc_cat=1\">"
                            + "</head><body></body></html>",
                    "https://www.facebook.com/photo/?fbid=" + fbid));
        }

        CrawlingFacebookRipper ripper =
                new CrawlingFacebookRipper(new URL("https://www.facebook.com/example/photos"), photoPages, listingPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(3, urls.size(), "Both mbasic pages plus the desktop page should be merged");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/900001_n.jpg?_nc_cat=1"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/900002_n.jpg?_nc_cat=1"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/900003_n.jpg?_nc_cat=1"));
    }

    private static class CrawlingFacebookRipper extends TestableFacebookRipper {
        private final Map<String, Document> photoPages;
        private final Map<String, Document> listingPages;

        CrawlingFacebookRipper(URL url, Map<String, Document> photoPages) throws java.io.IOException {
            this(url, photoPages, null);
        }

        CrawlingFacebookRipper(URL url, Map<String, Document> photoPages, Map<String, Document> listingPages)
                throws java.io.IOException {
            super(url);
            this.photoPages = photoPages;
            this.listingPages = listingPages;
        }

        @Override
        protected Document fetchPhotoPage(String fbid) {
            return photoPages.get(fbid);
        }

        @Override
        protected Document fetchPhotoListingPage(URL listingUrl) {
            // When no mbasic pages are supplied, behave as if mbasic is unavailable (no network).
            return listingPages == null ? null : listingPages.get(listingUrl.toExternalForm());
        }
    }

    private static String videoUrl(String name, String efgJson) {
        String efg = URLEncoder.encode(
                Base64.getEncoder().encodeToString(efgJson.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return "https://video.xx.fbcdn.net/o1/v/t2/f2/m367/" + name + "?_nc_cat=1&efg=" + efg + "&ccb=17-1";
    }
}
