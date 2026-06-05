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
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
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
    public void testPhotoListingFallsBackWhenGraphqlPaginationFails() throws Exception {
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_by\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=800001&set=a.1\""
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/800001_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, Document> photoPages = new HashMap<>();
        photoPages.put("800001", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/800001_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=800001"));

        FailingGraphqlFacebookRipper ripper =
                new FailingGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), photoPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should fall back to permalink crawl when GraphQL pagination fails");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/800001_n.jpg?_nc_cat=1"));
    }

    @Test
    public void testPhotoListingFallsBackFromPhotosOfToPhotosBy() throws Exception {
        String photosOfId = "YXBwX2NvbGxlY3Rpb246UEhPVE9TT0Y=";
        String photosById = "YXBwX2NvbGxlY3Rpb246UEhPVE9TQlk=";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"all_collections\":{\"nodes\":[{\"tab_key\":\"photos_of\",\"id\":\"" + photosOfId + "\"},"
                + "{\"tab_key\":\"photos_by\",\"id\":\"" + photosById + "\"}]}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlByCollection = new HashMap<>();
        graphqlByCollection.put(photosOfId, "{\"errors\":[{\"message\":\"collection unavailable\"}]}");
        graphqlByCollection.put(photosById,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/by1.jpg\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":false}}}}}");

        CollectionAwareGraphqlFacebookRipper ripper =
                new CollectionAwareGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"),
                        graphqlByCollection);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should fall back to photos_by when photos_of pagination fails");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/by1.jpg"));
    }

    @Test
    public void testPhotoListingPaginatesCometTabKeyCollectionWithoutCursor() throws Exception {
        // Current Comet /photos pages expose collection ids via tab_key and often omit end_cursor in HTML.
        String collectionId = "YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"section_type\":\"PHOTOS\",\"tab_key\":\"photos\",\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos\","
                + "\"all_collections\":{\"nodes\":[{\"tab_key\":\"photos_of\",\"id\":\"" + collectionId + "\"},"
                + "{\"tab_key\":\"photos_by\",\"id\":\"YXBwX2NvbGxlY3Rpb246V1JPTkdPQkxFQ1RJT04=\"}]}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put(null,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p1.jpg\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":false}}}}}");

        NullCursorGraphqlFacebookRipper ripper =
                new NullCursorGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should paginate using photos_of tab_key even without an HTML cursor");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p1.jpg"));
    }

    @Test
    public void testPhotoListingPaginatesEntireAlbumViaGraphql() throws Exception {
        // A /photos listing embeds the tokens needed to replay Facebook's GraphQL pagination. The
        // ripper must walk every page (following end_cursor/has_next_page) and collect each photo's
        // full-resolution viewer_image, merged with the first batch already rendered in the page.
        String listingHtml = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/v/p0.jpg\">"
                + "</head><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_by\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put("C1",
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p1.jpg?stp=cp6_tt6\"}}}},"
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p2.jpg?stp=cp6_tt6\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":true}}}}}");
        graphqlPages.put("C2",
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p3.jpg?stp=cp6_tt6\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C3\",\"has_next_page\":false}}}}}");

        GraphqlFacebookRipper ripper =
                new GraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(4, urls.size(), "First batch plus every paginated photo should be collected");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p0.jpg"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p1.jpg?stp=cp6_tt6"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p2.jpg?stp=cp6_tt6"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p3.jpg?stp=cp6_tt6"));
    }

    private static class CrawlingFacebookRipper extends TestableFacebookRipper {
        private final Map<String, Document> photoPages;

        CrawlingFacebookRipper(URL url, Map<String, Document> photoPages) throws java.io.IOException {
            super(url);
            this.photoPages = photoPages;
        }

        @Override
        protected Document fetchPhotoPage(String fbid) {
            return photoPages.get(fbid);
        }
    }

    private static class FailingGraphqlFacebookRipper extends CrawlingFacebookRipper {
        FailingGraphqlFacebookRipper(URL url, Map<String, Document> photoPages) throws java.io.IOException {
            super(url, photoPages);
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            return "{\"errors\":[{\"message\":\"Invalid doc_id\"}]}";
        }
    }

    private static class GraphqlFacebookRipper extends TestableFacebookRipper {
        private static final java.util.regex.Pattern CURSOR =
                java.util.regex.Pattern.compile("\"cursor\":\"([^\"]+)\"");
        private final Map<String, String> graphqlPages;

        GraphqlFacebookRipper(URL url, Map<String, String> graphqlPages) throws java.io.IOException {
            super(url);
            this.graphqlPages = graphqlPages;
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            String variables = formData.getOrDefault("variables", "");
            java.util.regex.Matcher m = CURSOR.matcher(variables);
            String key = m.find() ? m.group(1) : null;
            if (key == null && variables.contains("\"cursor\":null")) {
                key = null;
            }
            return graphqlPages.get(key);
        }
    }

    private static class NullCursorGraphqlFacebookRipper extends GraphqlFacebookRipper {
        NullCursorGraphqlFacebookRipper(URL url, Map<String, String> graphqlPages) throws java.io.IOException {
            super(url, graphqlPages);
        }
    }

    private static class CollectionAwareGraphqlFacebookRipper extends TestableFacebookRipper {
        private static final java.util.regex.Pattern COLLECTION_ID =
                java.util.regex.Pattern.compile("\"id\":\"(YXBwX2NvbGxlY3Rpb246[^\"]+)\"");
        private final Map<String, String> responsesByCollection;

        CollectionAwareGraphqlFacebookRipper(URL url, Map<String, String> responsesByCollection)
                throws java.io.IOException {
            super(url);
            this.responsesByCollection = responsesByCollection;
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            java.util.regex.Matcher m = COLLECTION_ID.matcher(formData.getOrDefault("variables", ""));
            return m.find() ? responsesByCollection.get(m.group(1)) : null;
        }
    }

    private static String videoUrl(String name, String efgJson) {
        String efg = URLEncoder.encode(
                Base64.getEncoder().encodeToString(efgJson.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return "https://video.xx.fbcdn.net/o1/v/t2/f2/m367/" + name + "?_nc_cat=1&efg=" + efg + "&ccb=17-1";
    }
}
