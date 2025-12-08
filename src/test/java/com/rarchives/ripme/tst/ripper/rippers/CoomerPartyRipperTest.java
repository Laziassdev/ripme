package com.rarchives.ripme.tst.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;

import com.rarchives.ripme.ripper.rippers.CoomerPartyRipper;

public class CoomerPartyRipperTest extends RippersTest {

    private static class TestableCoomerRipper extends CoomerPartyRipper {
        public TestableCoomerRipper(URL url) throws IOException {
            super(url);
        }

        public List<String> publicGetURLsFromJSON(JSONObject json) {
            return super.getURLsFromJSON(json);
        }

        public JSONObject publicGetJsonPostsForOffset(int offset) throws IOException {
            return super.getJsonPostsForOffset(offset);
        }

        public List<String> publicBuildSubdomainCandidates(String base) {
            return super.buildSubdomainCandidates(base);
        }

        public URL publicRebuildUrlWithHost(URL url, String host) throws Exception {
            return super.rebuildUrlWithHost(url, host);
        }

        public JSONArray publicParsePostsArray(String raw) {
            return super.parsePostsArray(raw);
        }
    }
    @Test
    @Tag("flaky")
    public void testRip() throws IOException, URISyntaxException {
        URL url = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        CoomerPartyRipper ripper = new CoomerPartyRipper(url);
        testRipper(ripper);
    }

    @Test
    public void testUrlParsing() throws IOException, URISyntaxException {
        String expectedGid = "onlyfans_soogsx";
        String[] urls = new String[] {
                "https://coomer.st/onlyfans/user/soogsx", // normal url
                "http://coomer.st/onlyfans/user/soogsx", // http, not https
                "https://coomer.st/onlyfans/user/soogsx/", // with slash at the end
                "https://coomer.st/onlyfans/user/soogsx?whatever=abc", // with url params
                "https://coomer.party/onlyfans/user/soogsx", // alternate domain
                "https://coomer.su/onlyfans/user/soogsx", // legacy domain
        };
        for (String stringUrl : urls) {
            URL url = new URI(stringUrl).toURL();
            CoomerPartyRipper ripper = new CoomerPartyRipper(url);
            assertTrue(ripper.canRip(url));
            assertEquals(expectedGid, ripper.getGID(url));
        }
    }

    @Test
    public void testAbsoluteFileUrl() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);
        JSONObject fileObj = new JSONObject().put("path", "https://img.coomer.st/thumbnail/data/test.jpg");
        JSONObject postObj = new JSONObject().put("file", fileObj);
        JSONArray posts = new JSONArray().put(postObj);
        JSONObject wrapper = new JSONObject().put("array", posts);
        List<String> urls = ripper.publicGetURLsFromJSON(wrapper);
        assertEquals(1, urls.size());
        assertEquals("https://img.coomer.st/thumbnail/data/test.jpg", urls.get(0));
    }

    @Test
    public void testImagePathWithoutDataPrefix() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);
        JSONObject fileObj = new JSONObject().put("path", "/ab/cd/test.jpg");
        JSONObject postObj = new JSONObject().put("file", fileObj);
        JSONArray posts = new JSONArray().put(postObj);
        JSONObject wrapper = new JSONObject().put("array", posts);
        List<String> urls = ripper.publicGetURLsFromJSON(wrapper);
        assertEquals(1, urls.size());
        // The ripper now builds media URLs on the same domain as the page being
        // ripped and prefixes image paths with "/thumbnail/data". Ensure the
        // generated URL reflects this behavior.
        assertEquals("https://coomer.st/thumbnail/data/ab/cd/test.jpg", urls.get(0));
    }

    @Test
    public void testLocationsFallback() throws Exception {
        URL base = new URI("https://coomer.st/fansly/user/1234").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        JSONObject fileObj = new JSONObject()
                .put("locations", new JSONArray().put(new JSONObject().put("location", "https://c1.coomer.st/data/ab/cd/test.png")));
        JSONObject postObj = new JSONObject().put("file", fileObj);
        JSONArray posts = new JSONArray().put(postObj);
        JSONObject wrapper = new JSONObject().put("array", posts);

        List<String> urls = ripper.publicGetURLsFromJSON(wrapper);
        assertEquals(1, urls.size());
        assertEquals("https://c1.coomer.st/data/ab/cd/test.png", urls.get(0));
    }

    @Test
    public void testAttachmentStringPath() throws Exception {
        URL base = new URI("https://coomer.st/fansly/user/5678").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        JSONArray attachments = new JSONArray()
                .put("/data/ff/aa/file1.jpg")
                .put(new JSONObject().put("locations", new JSONArray().put("/data/ff/aa/file2.mp4")));
        JSONObject postObj = new JSONObject().put("attachments", attachments);
        JSONArray posts = new JSONArray().put(postObj);
        JSONObject wrapper = new JSONObject().put("array", posts);

        List<String> urls = ripper.publicGetURLsFromJSON(wrapper);
        assertEquals(2, urls.size(), urls.toString());
        assertTrue(urls.contains("https://coomer.st/data/ff/aa/file1.jpg"), urls.toString());
        assertTrue(urls.contains("https://coomer.st/data/ff/aa/file2.mp4"), urls.toString());
    }

    @Test
    public void testSubdomainCandidates() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        List<String> hosts = ripper.publicBuildSubdomainCandidates("coomer.st");
        assertEquals(10, hosts.size());
        assertEquals("n1.coomer.st", hosts.get(0));
        assertEquals("n10.coomer.st", hosts.get(9));

        URL rebuilt = ripper.publicRebuildUrlWithHost(new URL("https://coomer.st/data/ab/cd/file.jpg"), "n3.coomer.st");
        assertEquals("https://n3.coomer.st/data/ab/cd/file.jpg", rebuilt.toString());

        URL rebuiltNoData = ripper.publicRebuildUrlWithHost(new URL("https://coomer.st/ab/cd/file.jpg"), "n4.coomer.st");
        assertEquals("https://n4.coomer.st/data/ab/cd/file.jpg", rebuiltNoData.toString());
    }

    @Test
    public void testParsePostsArraySkipsLeadingGarbage() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        String payload = "\n<!-- cached -->\n   [ {\"id\":1}, {\"id\":2} ]";
        JSONArray parsed = ripper.publicParsePostsArray(payload);
        assertEquals(2, parsed.length());
        assertEquals(1, parsed.getJSONObject(0).getInt("id"));
        assertEquals(2, parsed.getJSONObject(1).getInt("id"));
    }

    @Test
    public void testParsePostsArrayEmbeddedInHtml() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        JSONArray array = new JSONArray().put(new JSONObject().put("id", 5));
        String payload = "<html><body>Noise before array<script>var data = " + array.toString() + "</script></body></html>";
        JSONArray parsed = ripper.publicParsePostsArray(payload);
        assertEquals(1, parsed.length());
        assertEquals(5, parsed.getJSONObject(0).getInt("id"));
    }

    @Test
    public void testParsePostsArraySkipsBrokenArrays() throws Exception {
        URL base = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        TestableCoomerRipper ripper = new TestableCoomerRipper(base);

        String payload = "[1,]<!--bad--> some text [ {\"id\":9}, {\"id\":10} ] trailer";
        JSONArray parsed = ripper.publicParsePostsArray(payload);

        assertEquals(2, parsed.length());
        assertEquals(9, parsed.getJSONObject(0).getInt("id"));
        assertEquals(10, parsed.getJSONObject(1).getInt("id"));
    }

    @Test
    public void testHeaderVariantFallbackSkipsCookieFailure() throws Exception {
        URL base = new URI("https://coomer.st/fansly/user/1234").toURL();

        class FallbackRipper extends TestableCoomerRipper {
            public FallbackRipper(URL url) throws IOException {
                super(url);
            }

            @Override
            protected List<Map<String, String>> buildApiHeaderVariants() {
                List<Map<String, String>> variants = new ArrayList<>();

                Map<String, String> withCookie = new HashMap<>();
                withCookie.put("Cookie", "bad_cookie");
                variants.add(withCookie);

                variants.add(new HashMap<>());
                return variants;
            }

            @Override
            protected String fetchRawPosts(String apiUrl, Map<String, String> headers) throws IOException {
                if (headers.containsKey("Cookie")) {
                    throw new HttpStatusException("Forbidden", 403, apiUrl);
                }
                return "[{\"id\":42}]";
            }
        }

        FallbackRipper ripper = new FallbackRipper(base);
        JSONObject result = ripper.publicGetJsonPostsForOffset(0);
        JSONArray parsed = result.getJSONArray("array");

        assertEquals(1, parsed.length());
        assertEquals(42, parsed.getJSONObject(0).getInt("id"));
    }
}
