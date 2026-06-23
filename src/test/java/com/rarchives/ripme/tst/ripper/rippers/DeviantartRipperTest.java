package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.DeviantartRipper;

public class DeviantartRipperTest extends RippersTest {

    @Test
    @Disabled("Requires live DeviantArt session and network access")
    public void testDeviantartAlbum() throws IOException, URISyntaxException {
        DeviantartRipper ripper = new DeviantartRipper(new URI("https://www.deviantart.com/airgee/gallery/").toURL());
        testRipper(ripper);
    }

    @Test
    @Disabled("Requires live DeviantArt session and network access")
    public void testDeviantartNSFWAlbum() throws IOException, URISyntaxException {
        DeviantartRipper ripper = new DeviantartRipper(new URI("https://www.deviantart.com/faterkcx/gallery/").toURL());
        testRipper(ripper);
    }

    @Test
    public void testGetGIDFeaturedGallery() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/airgee/gallery/").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertEquals("airgee_gallery_featured", ripper.getGID(url));
    }

    @Test
    public void testGetGIDSpecificGallery() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/justgenitals/gallery/64358240/special-minamishots").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertEquals("justgenitals_gallery_special-minamishots", ripper.getGID(url));
    }

    @Test
    public void testSanitizeURL() throws IOException, URISyntaxException {
        List<URL> urls = new ArrayList<>();
        urls.add(new URI("https://www.deviantart.com/airgee/").toURL());
        urls.add(new URI("https://www.deviantart.com/airgee").toURL());
        urls.add(new URI("https://www.deviantart.com/airgee/gallery/").toURL());

        for (URL url : urls) {
            DeviantartRipper ripper = new DeviantartRipper(url);
            Assertions.assertEquals("https://www.deviantart.com/airgee/gallery/",
                    ripper.sanitizeURL(url).toExternalForm());
        }
    }

    @Test
    public void testExtractCsrfToken() {
        String html = "<script>window.__CSRF_TOKEN__ = 'abc.def.ghi';</script>";
        Assertions.assertEquals("abc.def.ghi", DeviantartRipper.extractCsrfToken(html));
        Assertions.assertEquals("token123", DeviantartRipper.extractCsrfToken("{\"csrfToken\":\"token123\"}"));
    }

    @Test
    public void testParseDeviationIdFromUrl() {
        Assertions.assertEquals(1341398454L, DeviantartRipper.parseDeviationIdFromUrl(
                "https://www.deviantart.com/monsterlova/art/VID-Writhing-Worms-Hilda-1341398454"));
    }

    @Test
    public void testBuildImageUrlFromMedia() {
        JSONObject media = new JSONObject();
        media.put("baseUri",
                "https://images-wixmp.example.com/i/uuid/file.jpg");
        media.put("prettyName", "pretty_name");
        JSONArray types = new JSONArray();
        JSONObject fullview = new JSONObject();
        fullview.put("t", "fullview");
        fullview.put("c", "/v1/fill/w_1280,h_720/<prettyName>-fullview.jpg");
        types.put(fullview);
        media.put("types", types);

        String url = DeviantartRipper.buildImageUrlFromMedia(media);
        Assertions.assertEquals(
                "https://images-wixmp.example.com/f/uuid/file.jpg/v1/fill/w_1280,h_720/pretty_name-fullview.jpg",
                url);
    }

    @Test
    public void testFindBestVideoUrl() {
        JSONObject media = new JSONObject();
        JSONArray types = new JSONArray();
        types.put(new JSONObject()
                .put("t", "video")
                .put("h", 360)
                .put("b", "https://example.com/360.mp4"));
        types.put(new JSONObject()
                .put("t", "video")
                .put("h", 720)
                .put("b", "//example.com/720.mp4"));
        media.put("types", types);

        Assertions.assertEquals("https://example.com/720.mp4", DeviantartRipper.findBestVideoUrl(media));
    }
}
