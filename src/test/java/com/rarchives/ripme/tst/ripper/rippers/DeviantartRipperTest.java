package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
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

import com.rarchives.ripme.ripper.AbstractRipper;
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
    public void testGetGIDTag() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/tag/bondage").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertEquals("tag_bondage", ripper.getGID(url));
    }

    @Test
    public void testGetGIDSearch() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/search?q=dildopants").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertEquals("search_dildopants", ripper.getGID(url));
    }

    @Test
    public void testGetGIDSearchWithSpaces() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/search?q=bondage+art").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertEquals("search_bondage_art", ripper.getGID(url));
    }

    @Test
    public void testCanRipSearchUrl() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/search?q=dildopants").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertTrue(ripper.canRip(url));
    }

    @Test
    public void testCanRipSearchUrlWithoutQuery() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/search").toURL();
        DeviantartRipper ripper = new DeviantartRipper(new URI("https://www.deviantart.com/tag/bondage").toURL());
        Assertions.assertFalse(ripper.canRip(url));
    }

    @Test
    public void testGetRipperSearchUrl() throws Exception {
        URL url = new URI("https://www.deviantart.com/search?q=dildopants").toURL();
        AbstractRipper ripper = AbstractRipper.getRipper(url);
        Assertions.assertTrue(ripper instanceof DeviantartRipper);
    }

    @Test
    public void testSearchUrlRequiresQuery() {
        Assertions.assertThrows(IOException.class, () -> new DeviantartRipper(
                new URI("https://www.deviantart.com/search").toURL()));
        Assertions.assertThrows(IOException.class, () -> new DeviantartRipper(
                new URI("https://www.deviantart.com/search?q=").toURL()));
    }

    @Test
    public void testGetSearchQuery() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/search?q=dildopants").toURL();
        Assertions.assertEquals("dildopants", DeviantartRipper.getSearchQuery(url));
        Assertions.assertEquals("bondage art",
                DeviantartRipper.getSearchQuery(new URI("https://www.deviantart.com/search?q=bondage+art").toURL()));
        Assertions.assertNull(DeviantartRipper.getSearchQuery(
                new URI("https://www.deviantart.com/search").toURL()));
        Assertions.assertNull(DeviantartRipper.getSearchQuery(
                new URI("https://www.deviantart.com/tag/bondage").toURL()));
    }

    @Test
    public void testParseSearchPageState() throws IOException {
        String html = "<script>window.__INITIAL_STATE__ = JSON.parse(\"{"
                + "\\\"@@streams\\\":{\\\"@@BROWSE_PAGE_STREAM\\\":{"
                + "\\\"items\\\":[789],\\\"hasMore\\\":false}},"
                + "\\\"@@entities\\\":{\\\"deviation\\\":{"
                + "\\\"789\\\":{\\\"deviationId\\\":789,\\\"url\\\":\\\"https://www.deviantart.com/foo/art/Search-789\\\"}"
                + "}}}\");</script>";
        JSONObject page = DeviantartRipper.parseTagPageState(html);
        Assertions.assertFalse(page.getBoolean("hasMore"));
        Assertions.assertEquals(1, page.getJSONArray("results").length());
        Assertions.assertEquals("https://www.deviantart.com/foo/art/Search-789",
                page.getJSONArray("results").getJSONObject(0).getString("url"));
    }

    @Test
    public void testCanRipTagUrl() throws IOException, URISyntaxException {
        URL url = new URI("https://www.deviantart.com/tag/bondage").toURL();
        DeviantartRipper ripper = new DeviantartRipper(url);
        Assertions.assertTrue(ripper.canRip(url));
    }

    @Test
    public void testGetRipperTagUrl() throws Exception {
        URL url = new URI("https://www.deviantart.com/tag/bondage").toURL();
        AbstractRipper ripper = AbstractRipper.getRipper(url);
        Assertions.assertTrue(ripper instanceof DeviantartRipper);
    }

    @Test
    public void testUsernameFromDeviationUrl() {
        Assertions.assertEquals("eropalo", DeviantartRipper.usernameFromDeviationUrl(
                "https://www.deviantart.com/eropalo/art/Bondage-807770670"));
    }

    @Test
    public void testParseTagPageState() throws IOException {
        String html = "<script>window.__INITIAL_STATE__ = JSON.parse(\"{"
                + "\\\"@@streams\\\":{\\\"@@BROWSE_PAGE_STREAM\\\":{"
                + "\\\"items\\\":[123],\\\"hasMore\\\":true}},"
                + "\\\"@@entities\\\":{\\\"deviation\\\":{"
                + "\\\"123\\\":{\\\"deviationId\\\":123,\\\"url\\\":\\\"https://www.deviantart.com/foo/art/Test-123\\\"}"
                + "}}}\");</script>";
        JSONObject page = DeviantartRipper.parseTagPageState(html);
        Assertions.assertTrue(page.getBoolean("hasMore"));
        Assertions.assertEquals(1, page.getJSONArray("results").length());
        Assertions.assertEquals("https://www.deviantart.com/foo/art/Test-123",
                page.getJSONArray("results").getJSONObject(0).getString("url"));
    }

    @Test
    public void testParseTagPageStateWithCompositeItemIds() throws IOException {
        String html = "<script>window.__INITIAL_STATE__ = JSON.parse(\"{"
                + "\\\"@@streams\\\":{\\\"@@BROWSE_PAGE_STREAM\\\":{"
                + "\\\"items\\\":[\\\"82-1083980628\\\",456],\\\"hasMore\\\":true}},"
                + "\\\"@@entities\\\":{\\\"deviation\\\":{"
                + "\\\"82-1083980628\\\":{\\\"deviationId\\\":1083980628,"
                + "\\\"url\\\":\\\"https://www.deviantart.com/foo/art/Composite-1083980628\\\"},"
                + "\\\"456\\\":{\\\"deviationId\\\":456,\\\"url\\\":\\\"https://www.deviantart.com/foo/art/Test-456\\\"}"
                + "}}}\");</script>";
        JSONObject page = DeviantartRipper.parseTagPageState(html);
        Assertions.assertTrue(page.getBoolean("hasMore"));
        JSONArray results = page.getJSONArray("results");
        Assertions.assertEquals(2, results.length());
        Assertions.assertEquals("https://www.deviantart.com/foo/art/Composite-1083980628",
                results.getJSONObject(0).getString("url"));
        Assertions.assertEquals("https://www.deviantart.com/foo/art/Test-456",
                results.getJSONObject(1).getString("url"));
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
    public void testBuildImageUrlFromMediaWithJwt() {
        JSONObject media = new JSONObject();
        media.put("baseUri",
                "https://images-wixmp.example.com/f/uuid/file.jpg");
        media.put("prettyName", "pretty_name");
        media.put("token", new JSONArray().put("eyJ.test.token"));
        JSONArray types = new JSONArray();
        JSONObject fullview = new JSONObject();
        fullview.put("t", "fullview");
        fullview.put("c", "/v1/fill/w_1280,h_720/<prettyName>-fullview.jpg");
        types.put(fullview);
        media.put("types", types);

        String url = DeviantartRipper.buildImageUrlFromMedia(media);
        Assertions.assertTrue(url.contains("?token=eyJ.test.token"));
        Assertions.assertTrue(url.contains("pretty_name-fullview.jpg"));
    }

    @Test
    public void testBuildImageUrlFromMediaOriginal() {
        JSONObject media = new JSONObject();
        media.put("baseUri",
                "https://images-wixmp.example.com/f/uuid/original.png");
        media.put("prettyName", "title_by_artist_id");
        media.put("token", new JSONArray().put("eyJ.original.token"));
        JSONArray types = new JSONArray();
        JSONObject fullview = new JSONObject();
        fullview.put("t", "fullview");
        fullview.put("r", 1);
        fullview.put("h", 3000);
        fullview.put("w", 2000);
        types.put(fullview);
        media.put("types", types);

        String url = DeviantartRipper.buildImageUrlFromMedia(media);
        Assertions.assertEquals("https://images-wixmp.example.com/f/uuid/original.png?token=eyJ.original.token", url);
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

    @Test
    public void testFileNameWithoutExtension() {
        Assertions.assertEquals("earthly_delights",
                DeviantartRipper.fileNameWithoutExtension("earthly_delights.mp4", "mp4"));
        Assertions.assertEquals("tiny_delights",
                DeviantartRipper.fileNameWithoutExtension("tiny_delights.jpg", "jpg"));
        Assertions.assertEquals("title", DeviantartRipper.fileNameWithoutExtension("title", "jpg"));
        Assertions.assertEquals("mixed_case",
                DeviantartRipper.fileNameWithoutExtension("mixed_case.JPG", "jpg"));
    }

    @Test
    public void testGetFileNameNoDoubleExtension() throws MalformedURLException {
        URL url = new URL("https://www.deviantart.com/download/12345/file.mp4");
        String resolvedName = DeviantartRipper.fileNameWithoutExtension("earthly_delights.mp4", "mp4");
        Assertions.assertEquals("earthly_delights.mp4",
                AbstractRipper.getFileName(url, "", resolvedName, "mp4"));

        resolvedName = DeviantartRipper.fileNameWithoutExtension("tiny_delights.jpg", "jpg");
        Assertions.assertEquals("tiny_delights.jpg",
                AbstractRipper.getFileName(url, "", resolvedName, "jpg"));
    }

    @Test
    public void testReplaceCsrfTokenInUrl() {
        String url = "https://www.deviantart.com/_puppy/dashared/gallection/contents"
                + "?username=test&csrf_token=old.token&offset=0";
        String updated = DeviantartRipper.replaceCsrfTokenInUrl(url, "new.token");
        Assertions.assertTrue(updated.contains("csrf_token=new.token"));
        Assertions.assertFalse(updated.contains("old.token"));
    }
}
