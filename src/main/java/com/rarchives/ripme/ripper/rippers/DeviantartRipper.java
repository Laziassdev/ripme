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
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection.Response;
import org.jsoup.HttpStatusException;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

/**
 * Rips DeviantArt galleries, favourites, and tag browse pages.
 *
 * <p>Galleries and favourites use the site's JSON API. Tag pages load embedded page
 * state from HTML and paginate with {@code ?page=N}.
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
    private static final Object COOKIE_PERSIST_LOCK = new Object();

    private static final int PAGE_SIZE = 24;
    /** Outer retries for 403 / session recovery after {@link Http#getWith429Retry} exhausts 429 handling. */
    private static final int API_MAX_RETRIES = 3;
    private static final int API_RETRY_DELAY_SECONDS = 30;
    private static final int API_MAX_DELAY_SECONDS = 600;
    private static final int PAGE_SLEEP_MS = 1500;
    private static final Random RATE_LIMIT_RANDOM = new Random();

    private static final List<String> FIREFOX_HOST_PATTERNS = Arrays.asList("%deviantart.com", "%.deviantart.com");

    private static final Pattern ARTIST_PATTERN = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9_-]+).*$");
    private static final Pattern FOLDER_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/[^/]+/(?:gallery|favourites)/([0-9]+)/.*$");
    private static final Pattern ALBUM_NAME_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/[a-zA-Z0-9_-]+/[a-zA-Z]+/[0-9]+/([a-zA-Z0-9-]+).*$");
    private static final Pattern CSRF_WINDOW_PATTERN = Pattern
            .compile("window\\.__CSRF_TOKEN__\\s*=\\s*'([^']+)'");
    private static final Pattern CSRF_JSON_PATTERN = Pattern.compile("\"csrfToken\":\"([^\"]+)\"");
    private static final Pattern TAG_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/tag/([a-zA-Z0-9_-]+)/?$");
    private static final Pattern DEVIATION_ARTIST_PATTERN = Pattern
            .compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9_-]+)/art/.*$");

    private static final String INITIAL_STATE_MARKER = "window.__INITIAL_STATE__ = JSON.parse(\"";

    private final boolean tagMode;
    private final String tagName;
    private final String artist;
    private final String collectionType;
    private final Integer folderId;
    private final boolean allFolders;

    private int tagPage = 1;

    private String csrfToken;
    private String referer;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, JSONObject> deviationCache = new HashMap<>();
    private final List<String> usedTitles = new ArrayList<>();
    private boolean warnedAboutMissingAuth = false;

    public DeviantartRipper(URL url) throws IOException {
        super(url);
        String pathUrl = stripQuery(url.toExternalForm());

        Matcher tagMatcher = TAG_PATTERN.matcher(pathUrl);
        if (tagMatcher.matches()) {
            tagMode = true;
            tagName = tagMatcher.group(1);
            artist = null;
            collectionType = null;
            folderId = null;
            allFolders = false;
            return;
        }

        tagMode = false;
        tagName = null;

        Matcher artistMatcher = ARTIST_PATTERN.matcher(pathUrl);
        if (!artistMatcher.matches()) {
            throw new IOException("Expected deviantart.com URL format: "
                    + "www.deviantart.com/<ARTIST>/gallery/, .../favourites/, or .../tag/<TAG> - got " + url);
        }
        artist = artistMatcher.group(1);

        if (pathUrl.contains("/favourites")) {
            collectionType = "favourites";
        } else if (pathUrl.contains("/gallery")) {
            collectionType = "gallery";
        } else if (pathUrl.matches("https?://www\\.deviantart\\.com/[^/]+/?")) {
            collectionType = "gallery";
        } else {
            throw new IOException("Expected deviantart.com gallery, favourites, or tag URL - got " + url);
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
    public boolean canRip(URL url) {
        if (!url.getHost().endsWith(getDomain())) {
            return false;
        }
        String pathUrl = stripQuery(url.toExternalForm());
        if (TAG_PATTERN.matcher(pathUrl).matches()) {
            return true;
        }
        if (!ARTIST_PATTERN.matcher(pathUrl).matches()) {
            return false;
        }
        return pathUrl.contains("/gallery") || pathUrl.contains("/favourites")
                || pathUrl.matches("https?://www\\.deviantart\\.com/[^/]+/?");
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
        Matcher tagMatcher = TAG_PATTERN.matcher(pathUrl);
        if (tagMatcher.matches()) {
            return "tag_" + tagMatcher.group(1).toLowerCase();
        }

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
        if (tagMode) {
            tagPage = 1;
            return fetchTagPage(tagPage);
        }
        return fetchGalleryPage(0);
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (!doc.optBoolean("hasMore", false)) {
            throw new IOException("No more pages");
        }
        Utils.sleep(PAGE_SLEEP_MS);
        if (tagMode) {
            tagPage++;
            return fetchTagPage(tagPage);
        }
        int nextOffset = doc.optInt("nextOffset", -1);
        if (nextOffset < 0) {
            throw new IOException("No more pages");
        }
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
            String downloadReferer = pageUrl;
            Map<String, String> downloadCookies = new LinkedHashMap<>(cookies);
            addURLToDownload(media.downloadUrl, prefix, "", downloadReferer, downloadCookies, media.fileName,
                    media.extension);
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

        IOException lastError = null;
        for (int attempt = 0; attempt <= API_MAX_RETRIES; attempt++) {
            try {
                refreshSession(attempt == 0);
                return;
            } catch (IOException e) {
                lastError = e;
                if (attempt < API_MAX_RETRIES) {
                    long waitSeconds = rateLimitBackoffSeconds(attempt);
                    logger.warn("DeviantArt session init failed (attempt {}/{}), waiting {}s before retry: {}",
                            attempt + 1, API_MAX_RETRIES + 1, waitSeconds, e.getMessage());
                    Utils.sleep(waitSeconds * 1000L);
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Could not initialize DeviantArt session");
    }

    private void refreshSession(boolean persist) throws IOException {
        Response response = Http.url(referer).referrer("https://www.deviantart.com/").cookies(cookies)
                .retries(0).response();
        int status = response.statusCode();
        if (status == 404) {
            throw new IOException("Account not found or deactivated");
        }
        if (status == 403) {
            throw new IOException("Gallery page returned 403 Forbidden");
        }
        if (status >= 400) {
            throw new IOException("Gallery page returned HTTP " + status);
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

        if (persist) {
            persistCookies();
        }
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

    private JSONObject fetchTagPage(int page) throws IOException {
        String pageUrl = buildTagPageUrl(page);
        logger.info("Fetching tag page {} for tag {}", page, tagName);
        Response response = Http.url(pageUrl).referrer("https://www.deviantart.com/").cookies(cookies)
                .retries(0).response();
        int status = response.statusCode();
        if (status == 404) {
            throw new IOException("Tag page not found: " + tagName);
        }
        if (status == 403) {
            throw new IOException("Tag page returned 403 Forbidden");
        }
        if (status >= 400) {
            throw new IOException("Tag page returned HTTP " + status);
        }

        mergeCookies(response.cookies());
        if (csrfToken == null || csrfToken.isBlank()) {
            csrfToken = extractCsrfToken(response.body());
        }

        return parseTagPageState(response.body());
    }

    private String buildTagPageUrl(int page) {
        String base = "https://www.deviantart.com/tag/" + tagName;
        if (page <= 1) {
            return base;
        }
        return base + "?page=" + page;
    }

    public static JSONObject parseTagPageState(String html) throws IOException {
        String stateJson = extractInitialStateJson(html);
        if (stateJson == null || stateJson.isBlank()) {
            throw new IOException("Could not find DeviantArt page state");
        }

        JSONObject state = new JSONObject(stateJson);
        JSONObject streams = state.optJSONObject("@@streams");
        if (streams == null) {
            throw new IOException("DeviantArt page state missing @@streams");
        }
        JSONObject stream = streams.optJSONObject("@@BROWSE_PAGE_STREAM");
        if (stream == null) {
            throw new IOException("DeviantArt page state missing @@BROWSE_PAGE_STREAM");
        }

        JSONArray itemIds = stream.optJSONArray("items");
        JSONObject deviations = state.optJSONObject("@@entities");
        JSONObject deviationMap = deviations != null ? deviations.optJSONObject("deviation") : null;

        JSONArray results = new JSONArray();
        if (itemIds != null && deviationMap != null) {
            for (int i = 0; i < itemIds.length(); i++) {
                String id = String.valueOf(itemIds.getLong(i));
                JSONObject deviation = deviationMap.optJSONObject(id);
                if (deviation != null) {
                    results.put(deviation);
                }
            }
        }

        JSONObject page = new JSONObject();
        page.put("results", results);
        page.put("hasMore", stream.optBoolean("hasMore", false));
        return page;
    }

    public static String extractInitialStateJson(String html) {
        if (html == null) {
            return null;
        }
        int start = html.indexOf(INITIAL_STATE_MARKER);
        if (start < 0) {
            return null;
        }
        start += INITIAL_STATE_MARKER.length();
        int end = html.indexOf("\");", start);
        if (end < 0) {
            return null;
        }
        return StringEscapeUtils.unescapeJavaScript(html.substring(start, end));
    }

    public static String usernameFromDeviationUrl(String url) {
        Matcher matcher = DEVIATION_ARTIST_PATTERN.matcher(stripQuery(url));
        return matcher.matches() ? matcher.group(1) : null;
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
        String requestUrl = apiUrl;
        IOException lastError = null;
        for (int attempt = 0; attempt <= API_MAX_RETRIES; attempt++) {
            try {
                Map<String, String> headers = buildApiHeaders();
                String body = Http.getWith429Retry(new URL(requestUrl), API_MAX_RETRIES, API_RETRY_DELAY_SECONDS,
                        AbstractRipper.USER_AGENT, headers);
                return new JSONObject(body);
            } catch (HttpStatusException e) {
                lastError = e;
                int status = e.getStatusCode();
                if (isRateLimitedStatus(status) && attempt < API_MAX_RETRIES) {
                    long waitSeconds = rateLimitBackoffSeconds(attempt);
                    logger.warn("DeviantArt API returned {}, waiting {}s before session refresh (attempt {}/{})",
                            status, waitSeconds, attempt + 1, API_MAX_RETRIES + 1);
                    Utils.sleep(waitSeconds * 1000L);
                    refreshSession(false);
                    requestUrl = replaceCsrfTokenInUrl(apiUrl, csrfToken);
                    continue;
                }
                throw new IOException("DeviantArt API error: HTTP " + status + " for " + requestUrl, e);
            } catch (IOException e) {
                lastError = e;
                if (attempt < API_MAX_RETRIES && isRateLimitMessage(e)) {
                    long waitSeconds = rateLimitBackoffSeconds(attempt);
                    logger.warn("DeviantArt API rate limited ({}), waiting {}s before session refresh (attempt {}/{})",
                            e.getMessage(), waitSeconds, attempt + 1, API_MAX_RETRIES + 1);
                    Utils.sleep(waitSeconds * 1000L);
                    try {
                        refreshSession(false);
                        requestUrl = replaceCsrfTokenInUrl(apiUrl, csrfToken);
                    } catch (IOException refreshError) {
                        logger.warn("DeviantArt session refresh failed during backoff: {}",
                                refreshError.getMessage());
                    }
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError : new IOException("DeviantArt API failed after retries");
    }

    private static long rateLimitBackoffSeconds(int attempt) {
        return Http.calculate429WaitSeconds(attempt, API_RETRY_DELAY_SECONDS, API_MAX_DELAY_SECONDS, null,
                RATE_LIMIT_RANDOM);
    }

    private static boolean isRateLimitedStatus(int status) {
        return status == 403 || status == 429;
    }

    private static boolean isRateLimitMessage(IOException e) {
        String message = e.getMessage();
        return message != null && (message.contains("403") || message.contains("429"));
    }

    private Map<String, String> buildApiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", referer);
        String cookieHeader = FirefoxCookieUtils.toCookieHeader(cookies);
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            headers.put("Cookie", cookieHeader);
        }
        return headers;
    }

    public static String replaceCsrfTokenInUrl(String apiUrl, String newToken) {
        if (apiUrl.contains("csrf_token=")) {
            return apiUrl.replaceAll("csrf_token=[^&]+", "csrf_token=" + encode(newToken));
        }
        return apiUrl;
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
        String fileName = fileNameWithoutExtension(safeTitle, extension);
        fileName = fileNameWithoutExtension(fileName, guessExtension(downloadUrl));
        return new ResolvedMedia(new URL(downloadUrl), fileName, extension);
    }

    private JSONObject fetchDeviation(JSONObject galleryItem, URL deviationPageUrl) throws IOException {
        long deviationId = galleryItem.optLong("deviationId", -1);
        if (deviationId < 0) {
            deviationId = parseDeviationIdFromUrl(deviationPageUrl.toExternalForm());
        }
        if (deviationId < 0) {
            return null;
        }

        String deviationArtist = artist;
        if (deviationArtist == null || deviationArtist.isBlank()) {
            deviationArtist = usernameFromDeviationUrl(deviationPageUrl.toExternalForm());
        }
        if (deviationArtist == null || deviationArtist.isBlank()) {
            return null;
        }

        String apiUrl = DEVIATION_API + "?deviationid=" + deviationId
                + "&username=" + encode(deviationArtist)
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

    /**
     * Strips a trailing extension from {@code fileName} when it already matches
     * {@code extension}, so {@link AbstractRipper#getFileName} does not append it twice.
     */
    public static String fileNameWithoutExtension(String fileName, String extension) {
        if (fileName == null || fileName.isBlank() || extension == null || extension.isBlank()) {
            return fileName;
        }
        String suffix = "." + extension.toLowerCase();
        if (fileName.toLowerCase().endsWith(suffix)) {
            return fileName.substring(0, fileName.length() - suffix.length());
        }
        return fileName;
    }

    private void mergeCookies(Map<String, String> newCookies) {
        if (newCookies != null && !newCookies.isEmpty()) {
            cookies.putAll(newCookies);
            cookies.put("agegate_state", "1");
        }
    }

    private void persistCookies() {
        synchronized (COOKIE_PERSIST_LOCK) {
            try {
                Map<String, String> toStore = new LinkedHashMap<>();
                String stored = Utils.getConfigString(COOKIES_CONFIG_KEY, null);
                if (stored != null && !stored.isBlank()) {
                    try {
                        toStore.putAll(deserialize(stored));
                    } catch (ClassNotFoundException e) {
                        logger.warn("Failed to merge stored DeviantArt cookies: {}", e.getMessage());
                    }
                }
                toStore.putAll(cookies);
                toStore.put("agegate_state", "1");
                Utils.setConfigString(COOKIES_CONFIG_KEY, serialize(new HashMap<>(toStore)));
                Utils.saveConfig();
            } catch (IOException e) {
                logger.warn("Failed to persist DeviantArt cookies: {}", e.getMessage());
            }
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
