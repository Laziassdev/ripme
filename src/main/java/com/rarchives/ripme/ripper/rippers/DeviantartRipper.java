package com.rarchives.ripme.ripper.rippers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection.Response;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

/**
 * Rips DeviantArt galleries and favourites via the site's JSON API.
 *
 * <p>Session cookies (for NSFW content and videos) are loaded in order:
 * serialized {@code DeviantartLogin.cookies}, {@code cookies.deviantart.com}
 * in rip.properties, then Firefox profile cookies when
 * {@code deviantart.firefox.cookies} is enabled (default true).
 */
public class DeviantartRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(DeviantartRipper.class);

    private static final String API_MINOR_VERSION = "20230710";
    private static final String GALLERY_API = "https://www.deviantart.com/_puppy/dashared/gallection/contents";
    private static final String DEVIATION_API = "https://www.deviantart.com/_puppy/dadeviation/init";
    private static final String COOKIES_CONFIG_KEY = "DeviantartLogin.cookies";

    private static final int PAGE_SIZE = 24;
    private static final int API_MAX_RETRIES = 5;
    private static final int API_RETRY_DELAY_SECONDS = 5;
    private static final int PAGE_SLEEP_MS = 1500;

    private static final List<String> FIREFOX_HOST_PATTERNS = Arrays.asList("%deviantart.com", "%.deviantart.com");

    private static final Pattern ARTIST_PATTERN = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9_-]+).*$");
    private static final Pattern FOLDER_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/[^/]+/(?:gallery|favourites)/([0-9]+)/.*$");
    private static final Pattern ALBUM_NAME_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/[a-zA-Z0-9_-]+/[a-zA-Z]+/[0-9]+/([a-zA-Z0-9-]+).*$");
    private static final Pattern CSRF_WINDOW_PATTERN = Pattern
            .compile("window\\.__CSRF_TOKEN__\\s*=\\s*'([^']+)'");
    private static final Pattern CSRF_JSON_PATTERN = Pattern.compile("\"csrfToken\":\"([^\"]+)\"");

    private final String artist;
    private final String collectionType;
    private final Integer folderId;
    private final boolean allFolders;

    private String csrfToken;
    private String referer;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, JSONObject> deviationCache = new HashMap<>();
    private final List<String> usedTitles = new ArrayList<>();
    private boolean warnedAboutMissingAuth = false;

    public DeviantartRipper(URL url) throws IOException {
        super(url);
        String pathUrl = stripQuery(url.toExternalForm());

        Matcher artistMatcher = ARTIST_PATTERN.matcher(pathUrl);
        if (!artistMatcher.matches()) {
            throw new IOException("Expected deviantart.com URL format: "
                    + "www.deviantart.com/<ARTIST>/gallery/ or .../favourites/ - got " + url);
        }
        artist = artistMatcher.group(1);

        if (pathUrl.contains("/favourites")) {
            collectionType = "favourites";
        } else if (pathUrl.contains("/gallery")) {
            collectionType = "gallery";
        } else {
            throw new IOException("Expected deviantart.com gallery or favourites URL - got " + url);
        }

        Matcher folderMatcher = FOLDER_PATTERN.matcher(pathUrl);
        if (folderMatcher.matches()) {
            folderId = Integer.valueOf(folderMatcher.group(1));
            allFolders = false;
        } else {
            folderId = null;
            allFolders = true;
        }
    }

    @Override
    protected String getDomain() {
        return "deviantart.com";
    }

    @Override
    public String getHost() {
        return "deviantart";
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        String pathUrl = stripQuery(url.toExternalForm());
        if (pathUrl.matches("https?://www\\.deviantart\\.com/[^/]+/?")) {
            String base = pathUrl.replaceAll("/?$", "");
            return new URI(base + "/gallery/").toURL();
        }
        return url;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String pathUrl = stripQuery(url.toExternalForm());
        Matcher artistMatcher = ARTIST_PATTERN.matcher(pathUrl);
        if (!artistMatcher.matches()) {
            throw new MalformedURLException("Invalid DeviantArt URL: " + url);
        }
        String artistName = artistMatcher.group(1);

        String what;
        if (pathUrl.contains("/gallery")) {
            what = "gallery";
        } else if (pathUrl.contains("/favourites")) {
            what = "favourites";
        } else {
            throw new MalformedURLException("Invalid DeviantArt URL: " + url);
        }

        String albumName;
        Matcher albumMatcher = ALBUM_NAME_PATTERN.matcher(pathUrl);
        if (pathUrl.endsWith("?catpath=/") || pathUrl.contains("catpath=/")) {
            albumName = "all";
        } else if (pathUrl.endsWith("/favourites/") || pathUrl.endsWith("/gallery/")
                || pathUrl.endsWith("/gallery") || pathUrl.endsWith("/favourites")) {
            albumName = "featured";
        } else if (albumMatcher.matches()) {
            albumName = albumMatcher.group(1);
        } else {
            albumName = "featured";
        }

        logger.info("Album Name: {}_{}_{}", artistName, what, albumName);
        return artistName + "_" + what + "_" + albumName;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException, URISyntaxException {
        initSession();
        return fetchGalleryPage(0);
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (!doc.optBoolean("hasMore", false)) {
            throw new IOException("No more pages");
        }
        int nextOffset = doc.optInt("nextOffset", -1);
        if (nextOffset < 0) {
            throw new IOException("No more pages");
        }
        Utils.sleep(PAGE_SLEEP_MS);
        return fetchGalleryPage(nextOffset);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject page) {
        List<String> urls = new ArrayList<>();
        JSONArray results = page.optJSONArray("results");
        if (results == null) {
            return urls;
        }

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            String deviationUrl = item.optString("url", "");
            if (deviationUrl.isBlank()) {
                continue;
            }
            deviationCache.put(deviationUrl, item);
            urls.add(deviationUrl);
        }

        logger.info("Found {} deviations on page", urls.size());
        return urls;
    }

    @Override
    protected void downloadURL(URL deviationPageUrl, int index) {
        String pageUrl = deviationPageUrl.toExternalForm();
        JSONObject item = deviationCache.get(pageUrl);

        try {
            ResolvedMedia media = resolveMedia(item, deviationPageUrl);
            if (media == null) {
                logger.warn("No downloadable media for {}", pageUrl);
                sendUpdate(STATUS.DOWNLOAD_SKIP, "No downloadable media for " + pageUrl);
                return;
            }

            String prefix = getPrefix(index);
            addURLToDownload(media.downloadUrl, prefix, "", referer, cookies, media.fileName, media.extension);
        } catch (IOException e) {
            logger.error("Failed to resolve media for {}", pageUrl, e);
            sendUpdate(STATUS.DOWNLOAD_ERRORED, pageUrl + ": " + e.getMessage());
        }
    }

    private void initSession() throws IOException {
        loadCookies();
        referer = stripQuery(this.url.toExternalForm());
        if (!referer.endsWith("/")) {
            referer += "/";
        }

        Response response = Http.url(referer).referrer("https://www.deviantart.com/").cookies(cookies).response();
        if (response.statusCode() == 404) {
            throw new IOException("Account not found or deactivated");
        }

        mergeCookies(response.cookies());
        csrfToken = extractCsrfToken(response.body());
        if (csrfToken == null || csrfToken.isBlank()) {
            throw new IOException("Could not find DeviantArt CSRF token on gallery page");
        }

        if (!hasAuthCookies()) {
            warnMissingAuth();
        } else {
            logger.info("DeviantArt auth cookies present (source: config and/or Firefox)");
        }

        persistCookies();
    }

    private void loadCookies() {
        cookies.clear();

        try {
            String stored = Utils.getConfigString(COOKIES_CONFIG_KEY, null);
            if (stored != null && !stored.isBlank()) {
                Map<String, String> storedCookies = deserialize(stored);
                cookies.putAll(storedCookies);
                logger.info("Loaded {} DeviantArt cookie(s) from {}", storedCookies.size(), COOKIES_CONFIG_KEY);
            }
        } catch (IOException | ClassNotFoundException e) {
            logger.warn("Failed to load stored DeviantArt cookies: {}", e.getMessage());
        }

        String configCookieString = Utils.getConfigString("cookies.deviantart.com", "");
        if (configCookieString != null && !configCookieString.isBlank()) {
            Map<String, String> configCookies = RipUtils.getCookiesFromString(configCookieString.trim());
            if (!configCookies.isEmpty()) {
                cookies.putAll(configCookies);
                logger.info("Loaded {} DeviantArt cookie(s) from cookies.deviantart.com", configCookies.size());
            }
        }

        if (Utils.getConfigBoolean("deviantart.firefox.cookies", true)) {
            loadCookiesFromFirefox();
        } else {
            logger.debug("Firefox cookie loading disabled via deviantart.firefox.cookies");
        }

        cookies.put("agegate_state", "1");
    }

    private void loadCookiesFromFirefox() {
        if (!FirefoxCookieUtils.isSQLiteDriverAvailable()) {
            logger.debug("SQLite JDBC driver not available; cannot read DeviantArt cookies from Firefox");
            return;
        }

        for (Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
            Map<String, String> profileCookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath,
                    FIREFOX_HOST_PATTERNS);
            if (profileCookies.isEmpty()) {
                continue;
            }

            int before = cookies.size();
            cookies.putAll(profileCookies);
            int added = cookies.size() - before;
            logger.info("Loaded {} DeviantArt cookie(s) from Firefox profile {} ({} new)",
                    profileCookies.size(), profilePath.getFileName(), added);

            if (hasAuthCookies()) {
                return;
            }
        }

        if (!hasAuthCookies()) {
            logger.debug("No DeviantArt auth cookies found in Firefox profiles");
        }
    }

    private boolean hasAuthCookies() {
        return cookies.containsKey("auth") || cookies.containsKey("auth_secure");
    }

    private void warnMissingAuth() {
        if (warnedAboutMissingAuth) {
            return;
        }
        warnedAboutMissingAuth = true;
        logger.warn(
                "No DeviantArt auth cookies found. Public images may still download, but NSFW galleries and videos "
                        + "usually require a logged-in session. Log into deviantart.com in Firefox (with "
                        + "deviantart.firefox.cookies=true), or set cookies.deviantart.com in rip.properties.");
    }

    private JSONObject fetchGalleryPage(int offset) throws IOException {
        String apiUrl = buildGalleryApiUrl(offset);
        logger.info("Fetching gallery page at offset {}", offset);
        return fetchApiJson(apiUrl);
    }

    private String buildGalleryApiUrl(int offset) throws IOException {
        StringBuilder sb = new StringBuilder(GALLERY_API);
        sb.append("?username=").append(encode(artist));
        sb.append("&type=").append(collectionType);
        sb.append("&order=personalized");
        sb.append("&offset=").append(offset);
        sb.append("&limit=").append(PAGE_SIZE);
        if (allFolders) {
            sb.append("&all_folder=true");
        } else if (folderId != null) {
            sb.append("&folder_id=").append(folderId);
        }
        sb.append("&da_minor_version=").append(API_MINOR_VERSION);
        sb.append("&csrf_token=").append(encode(csrfToken));
        return sb.toString();
    }

    private JSONObject fetchApiJson(String apiUrl) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", referer);
        String cookieHeader = FirefoxCookieUtils.toCookieHeader(cookies);
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            headers.put("Cookie", cookieHeader);
        }

        String body = Http.getWith429Retry(new URL(apiUrl), API_MAX_RETRIES, API_RETRY_DELAY_SECONDS,
                AbstractRipper.USER_AGENT, headers);
        return new JSONObject(body);
    }

    private ResolvedMedia resolveMedia(JSONObject galleryItem, URL deviationPageUrl) throws IOException {
        if (galleryItem == null) {
            galleryItem = new JSONObject();
        }

        String title = galleryItem.optString("title", deviationPageUrl.getPath());
        boolean isVideo = galleryItem.optBoolean("isVideo", false)
                || "film".equalsIgnoreCase(galleryItem.optString("type"))
                || "video".equalsIgnoreCase(galleryItem.optString("filetype"));

        JSONObject media = galleryItem.optJSONObject("media");
        String downloadUrl = null;
        String extension = null;

        if (isVideo) {
            downloadUrl = findBestVideoUrl(media);
            extension = "mp4";
            if (downloadUrl == null && hasAuthCookies()) {
                JSONObject deviation = fetchDeviation(galleryItem, deviationPageUrl);
                if (deviation != null) {
                    downloadUrl = findBestVideoUrl(deviation.optJSONObject("media"));
                }
            }
            if (downloadUrl == null) {
                warnMissingAuth();
                return null;
            }
        } else {
            JSONObject deviation = null;
            if (hasAuthCookies()) {
                deviation = fetchDeviation(galleryItem, deviationPageUrl);
                if (deviation != null) {
                    JSONObject extended = deviation.optJSONObject("extended");
                    if (extended != null) {
                        JSONObject download = extended.optJSONObject("download");
                        if (download != null) {
                            downloadUrl = download.optString("url", null);
                        }
                    }
                }
            }

            if (downloadUrl == null || downloadUrl.isBlank()) {
                if (deviation == null) {
                    deviation = fetchDeviation(galleryItem, deviationPageUrl);
                }
                JSONObject deviationMedia = deviation != null ? deviation.optJSONObject("media") : media;
                downloadUrl = buildImageUrlFromMedia(deviationMedia != null ? deviationMedia : media);
            }

            if (downloadUrl == null || downloadUrl.isBlank()) {
                return null;
            }

            extension = extensionFromUrlOrType(downloadUrl, galleryItem, deviation);
        }

        String safeTitle = uniqueTitle(title);
        if (extension == null || extension.isBlank()) {
            extension = guessExtension(downloadUrl);
        }
        return new ResolvedMedia(new URL(downloadUrl), safeTitle + "." + extension, extension);
    }

    private JSONObject fetchDeviation(JSONObject galleryItem, URL deviationPageUrl) throws IOException {
        long deviationId = galleryItem.optLong("deviationId", -1);
        if (deviationId < 0) {
            deviationId = parseDeviationIdFromUrl(deviationPageUrl.toExternalForm());
        }
        if (deviationId < 0) {
            return null;
        }

        String apiUrl = DEVIATION_API + "?deviationid=" + deviationId
                + "&username=" + encode(artist)
                + "&type=art&include_session=false"
                + "&csrf_token=" + encode(csrfToken)
                + "&expand=deviation.related&da_minor_version=" + API_MINOR_VERSION;

        JSONObject response = fetchApiJson(apiUrl);
        return response.optJSONObject("deviation");
    }

    public static long parseDeviationIdFromUrl(String url) {
        Matcher matcher = Pattern.compile("-([0-9]+)(?:/)?$").matcher(url);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return -1;
    }

    public static String extractCsrfToken(String html) {
        if (html == null) {
            return null;
        }
        Matcher windowMatcher = CSRF_WINDOW_PATTERN.matcher(html);
        if (windowMatcher.find()) {
            return windowMatcher.group(1);
        }
        Matcher jsonMatcher = CSRF_JSON_PATTERN.matcher(html);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1);
        }
        return null;
    }

    public static String buildImageUrlFromMedia(JSONObject media) {
        if (media == null) {
            return null;
        }

        String baseUri = media.optString("baseUri", "");
        String prettyName = media.optString("prettyName", "");
        if (baseUri.isBlank() || prettyName.isBlank()) {
            return null;
        }

        JSONObject type = findMediaType(media, "fullview");
        if (type == null) {
            type = findLargestImageType(media);
        }
        if (type == null) {
            return null;
        }

        String path = type.optString("c", "").replace("<prettyName>", prettyName);
        if (path.isBlank()) {
            return null;
        }

        String fileBase = baseUri.replace("/i/", "/f/").split("/v1/")[0];
        return fileBase + path;
    }

    public static String findBestVideoUrl(JSONObject media) {
        if (media == null) {
            return null;
        }

        JSONArray types = media.optJSONArray("types");
        if (types == null) {
            return null;
        }

        String bestUrl = null;
        int bestHeight = -1;
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            if (type == null || !"video".equals(type.optString("t"))) {
                continue;
            }
            String videoUrl = normalizeMediaUrl(type.optString("b", ""));
            if (videoUrl == null) {
                continue;
            }
            int height = type.optInt("h", 0);
            if (height > bestHeight) {
                bestHeight = height;
                bestUrl = videoUrl;
            }
        }
        return bestUrl;
    }

    private static JSONObject findMediaType(JSONObject media, String typeName) {
        JSONArray types = media.optJSONArray("types");
        if (types == null) {
            return null;
        }
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            if (type != null && typeName.equals(type.optString("t"))) {
                return type;
            }
        }
        return null;
    }

    private static JSONObject findLargestImageType(JSONObject media) {
        JSONArray types = media.optJSONArray("types");
        if (types == null) {
            return null;
        }

        JSONObject best = null;
        int bestPixels = -1;
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            if (type == null) {
                continue;
            }
            String t = type.optString("t");
            if ("video".equals(t) || t.endsWith("S")) {
                continue;
            }
            int pixels = type.optInt("w", 0) * type.optInt("h", 0);
            if (pixels > bestPixels) {
                bestPixels = pixels;
                best = type;
            }
        }
        return best;
    }

    private static String normalizeMediaUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (!url.startsWith("http")) {
            return "https://" + url;
        }
        return url;
    }

    private String uniqueTitle(String title) {
        String safe = title.replaceAll("[^a-zA-Z0-9\\.\\-]", "_").toLowerCase();
        if (safe.isBlank()) {
            safe = "untitled";
        }
        if (!usedTitles.contains(safe)) {
            usedTitles.add(safe);
            return safe;
        }
        int counter = 1;
        while (usedTitles.contains(safe + "_" + counter)) {
            counter++;
        }
        safe = safe + "_" + counter;
        usedTitles.add(safe);
        return safe;
    }

    private static String extensionFromUrlOrType(String downloadUrl, JSONObject galleryItem, JSONObject deviation) {
        String filetype = null;
        if (deviation != null) {
            filetype = deviation.optString("filetype", null);
        }
        if (filetype == null || filetype.isBlank()) {
            filetype = galleryItem.optString("filetype", null);
        }
        if (filetype != null && !filetype.isBlank()) {
            if ("jpeg".equalsIgnoreCase(filetype)) {
                return "jpg";
            }
            return filetype.toLowerCase();
        }
        return guessExtension(downloadUrl);
    }

    private static String guessExtension(String downloadUrl) {
        String path = downloadUrl.split("\\?")[0];
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            return path.substring(dot + 1).toLowerCase();
        }
        return "jpg";
    }

    private void mergeCookies(Map<String, String> newCookies) {
        if (newCookies != null && !newCookies.isEmpty()) {
            cookies.putAll(newCookies);
            cookies.put("agegate_state", "1");
        }
    }

    private void persistCookies() {
        try {
            Utils.setConfigString(COOKIES_CONFIG_KEY, serialize(new LinkedHashMap<>(cookies)));
            Utils.saveConfig();
        } catch (IOException e) {
            logger.warn("Failed to persist DeviantArt cookies: {}", e.getMessage());
        }
    }

    private static String serialize(Serializable value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> deserialize(String encoded) throws IOException, ClassNotFoundException {
        byte[] data = Base64.getDecoder().decode(encoded);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, String>) ois.readObject();
        }
    }

    private static String stripQuery(String url) {
        return url.split("\\?")[0];
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class ResolvedMedia {
        private final URL downloadUrl;
        private final String fileName;
        private final String extension;

        private ResolvedMedia(URL downloadUrl, String fileName, String extension) {
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.extension = extension;
        }
    }
}
