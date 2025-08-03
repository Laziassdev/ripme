package com.rarchives.ripme.tst.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import com.rarchives.ripme.ripper.rippers.CoomerPartyRipper;

public class CoomerPartyRipperTest extends RippersTest {

    private static class TestableCoomerRipper extends CoomerPartyRipper {
        public TestableCoomerRipper(URL url) throws IOException {
            super(url);
        }

        public List<String> publicGetURLsFromJSON(JSONObject json) {
            return super.getURLsFromJSON(json);
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
}
