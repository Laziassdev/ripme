package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.jsoup.Connection;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlueskyRipper extends AbstractJSONRipper {

    private static final String DOMAIN = "bsky.app";
    private static final String HOST = "bluesky";
    private String handle;
    private String actorDid;
    private String sessionToken = null;
    private String appPassword = null;
    private String username = null;

    private final int maxDownloads;
    private final DownloadLimitTracker downloadLimitTracker;
    private volatile boolean maxDownloadLimitReached = false;

    private static final Pattern HLS_BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern VIDEO_URL_PATTERN =
            Pattern.compile(".*\\.(mp4|webm|mov|m4v|gif|m3u8)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(BlueskyRipper.class);

    public BlueskyRipper(URL url) throws IOException {
        super(url);
        logger.info("BlueskyRipper constructor called for URL: {}", url);
        this.maxDownloads = Utils.getConfigInteger("maxdownloads", -1);
        this.downloadLimitTracker = new DownloadLimitTracker(maxDownloads);
        this.handle = getGID(url);
        // Try to get credentials from config
        this.username = Utils.getConfigString("bluesky.username", null);
        this.appPassword = Utils.getConfigString("bluesky.apppassword", null);
        if (this.username == null || this.appPassword == null) {
            logger.error("Bluesky username and app password must be set in ripme config (bluesky.username, bluesky.apppassword)");
            throw new IOException("Bluesky username and app password must be set in ripme config (bluesky.username, bluesky.apppassword)");
        }
        logger.info("Attempting to get session token for user: {}", this.username);
        this.sessionToken = getSessionToken();
        logger.info("Session token after getSessionToken(): {}", this.sessionToken);
        if (this.sessionToken == null) {
            logger.error("Session token is null after login! Aborting.");
            throw new IOException("Bluesky session token is null after login!");
        }
    }

    // Simple static cache for session tokens per username
    private static final java.util.Map<String, String> SESSION_TOKEN_CACHE = new java.util.HashMap<>();

    // Persistent session token cache file
    private static final String SESSION_TOKEN_FILE = System.getProperty("user.home") + "/.ripme_bluesky_token.json";

    private String getSessionToken() throws IOException {
        // Try persistent cache first
        String diskToken = loadTokenFromDisk(username);
        if (diskToken != null && !diskToken.trim().isEmpty()) {
            logger.info("Loaded session token from disk for {}: {}", username, diskToken);
            SESSION_TOKEN_CACHE.put(username, diskToken);
            return diskToken;
        }
        logger.info("No valid session token found on disk for {}. Logging in...", username);
        String loginUrl = "https://bsky.social/xrpc/com.atproto.server.createSession";
        JSONObject loginPayload = new JSONObject();
        loginPayload.put("identifier", username);
        loginPayload.put("password", appPassword);
        try {
            Connection conn = Http.url(loginUrl).ignoreContentType().connection();
            conn.header("Content-Type", "application/json");
            conn.requestBody(loginPayload.toString());
            conn.method(Connection.Method.POST);
            Connection.Response resp = conn.execute();
            int status = resp.statusCode();
            String body = resp.body();
            logger.info("Bluesky login response: HTTP {} - {}", status, body);
            if (status != 200) {
                logger.error("Bluesky login failed (HTTP {}): {}", status, body);
                throw new IOException("Bluesky login failed (HTTP " + status + "): " + body);
            }
            JSONObject json = new JSONObject(body);
            if (!json.has("accessJwt")) {
                logger.error("Bluesky login response missing accessJwt: {}", body);
                throw new IOException("Bluesky login response missing accessJwt: " + body);
            }
            String token = json.getString("accessJwt");
            logger.info("Obtained new session token for {}: {}", username, token);
            SESSION_TOKEN_CACHE.put(username, token);
            saveTokenToDisk(username, token);
            // Check if file was written
            java.nio.file.Path tokenPath = java.nio.file.Paths.get(SESSION_TOKEN_FILE);
            if (java.nio.file.Files.exists(tokenPath)) {
                logger.info("Token file successfully written: {}", tokenPath);
            } else {
                logger.error("Token file was NOT written: {}", tokenPath);
            }
            return token;
        } catch (Exception e) {
            logger.error("Exception during Bluesky login: {}", e.getMessage(), e);
            throw new IOException("Bluesky login error: " + e.getMessage(), e);
        }
    }

    private static void saveTokenToDisk(String username, String token) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put(username, token);
            java.nio.file.Files.write(java.nio.file.Paths.get(SESSION_TOKEN_FILE), obj.toString().getBytes());
        } catch (Exception ignored) {}
    }

    private static String loadTokenFromDisk(String username) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(SESSION_TOKEN_FILE);
            if (!java.nio.file.Files.exists(path)) return null;
            String content = new String(java.nio.file.Files.readAllBytes(path));
            if (content.trim().isEmpty()) return null;
            org.json.JSONObject obj = new org.json.JSONObject(content);
            if (obj.has(username)) {
                String token = obj.getString(username);
                if (token == null || token.trim().isEmpty()) return null;
                return token;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // Remove token for a username from disk
    private static void removeTokenFromDisk(String username) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(SESSION_TOKEN_FILE);
            if (!java.nio.file.Files.exists(path)) return;
            String content = new String(java.nio.file.Files.readAllBytes(path));
            org.json.JSONObject obj = new org.json.JSONObject(content);
            obj.remove(username);
            java.nio.file.Files.write(path, obj.toString().getBytes());
        } catch (Exception ignored) {}
    }

    // Loads Bluesky cookies from Firefox (Windows)
    private static java.util.Map<String, String> loadBlueskyCookiesFromFirefox() {
        java.util.Map<String, String> cookies = new java.util.HashMap<>();
        try {
            String userHome = System.getProperty("user.home");
            String profilesIniPath = userHome + "/AppData/Roaming/Mozilla/Firefox/profiles.ini";
            java.nio.file.Path iniPath = java.nio.file.Paths.get(profilesIniPath);
            if (!java.nio.file.Files.exists(iniPath)) return cookies;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(iniPath);
            String profilePath = null;
            for (String line : lines) {
                if (line.trim().startsWith("Path=")) {
                    profilePath = line.trim().substring(5);
                    break;
                }
            }
            if (profilePath == null) return cookies;
            String sqlitePath = userHome + "/AppData/Roaming/Mozilla/Firefox/Profiles/" + profilePath + "/cookies.sqlite";
            Class.forName("org.sqlite.JDBC");
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
                String sql = "SELECT name, value FROM moz_cookies WHERE (host LIKE '%bsky.app' OR host LIKE '%bsky.social')";
                try (java.sql.Statement stmt = conn.createStatement(); java.sql.ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        cookies.put(rs.getString("name"), rs.getString("value"));
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors, just return empty map
        }
        return cookies;
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost().toLowerCase();
        // Accept both bsky.app and bsky.social profile URLs
        if ((host.endsWith("bsky.app") || host.endsWith("bsky.social")) && url.getPath().startsWith("/profile/")) {
            // Accept any handle after /profile/ (including dots)
            String[] parts = url.getPath().split("/");
            return parts.length > 2 && !parts[2].isEmpty();
        }
        return false;
    }

    @Override
    public String getDomain() {
        return "bsky.app";
    }

    @Override
    protected boolean usesCustomDownloadLimitTracking() {
        return true;
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = Pattern.compile("^https?://bsky\\.(?:app|social)/profile/([^/]+)").matcher(url.toExternalForm());
        if (m.find()) {
            return m.group(1);
        }
        throw new MalformedURLException("Expected format: https://bsky.app/profile/username or https://bsky.social/profile/username");
    }

    // Resolves a Bluesky handle to a DID using the Bluesky API
    private String resolveHandleToDID(String handle) throws IOException {
        String url = "https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=" + handle;
        Connection.Response resp = Http.url(url)
                .ignoreContentType()
                .connection()
                .execute();
        int status = resp.statusCode();
        String body = resp.body();
        logger.info("resolveHandleToDID: HTTP {} - {}", status, body);
        if (status != 200) {
            throw new IOException("Failed to resolve handle to DID (HTTP " + status + "): " + body);
        }
        org.json.JSONObject json = new org.json.JSONObject(body);
        if (!json.has("did")) {
            throw new IOException("No DID found in resolveHandleToDID response: " + body);
        }
        return json.getString("did");
    }

    private static void logRequestAndResponse(Connection conn, Connection.Response resp) {
        try {
            logger.info("Request URL: {}", conn.request().url());
            logger.info("Request Method: {}", conn.request().method());
            logger.info("Request Headers: {}", conn.request().headers());
            logger.info("Request Body: {}", conn.request().requestBody());
            logger.info("Response Status: {}", resp.statusCode());
            logger.info("Response Headers: {}", resp.headers());
            logger.info("Response Body: {}", resp.body());
        } catch (Exception e) {
            logger.warn("Error logging request/response: {}", e.getMessage());
        }
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        java.util.Map<String, String> cookies = loadBlueskyCookiesFromFirefox();
        String did = resolveHandleToDID(handle);
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + did + "&limit=100";
        Connection.Response resp = null;
        int status = -1;
        String body = null;
        boolean triedCookies = false;
        boolean retriedToken = false;
        boolean triedToken = false;
        try {
            // Try with cookies first
            if (!cookies.isEmpty()) {
                triedCookies = true;
                logger.info("Trying Bluesky API with Firefox cookies");
                Connection conn = Http.url(apiUrl)
                        .ignoreContentType()
                        .connection();
                conn.ignoreHttpErrors(true);
                StringBuilder cookieHeader = new StringBuilder();
                for (java.util.Map.Entry<String, String> entry : cookies.entrySet()) {
                    cookieHeader.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                }
                conn.header("Cookie", cookieHeader.toString());
                resp = conn.execute();
                logRequestAndResponse(conn, resp);
                status = resp.statusCode();
                body = resp.body();
                logger.info("Bluesky API with cookies: HTTP {} - {}", status, body);
            }
            // If cookies failed or not present, try with session token
            if (status != 200) {
                triedToken = true;
                logger.info("Trying Bluesky API with session token");
                Connection conn = Http.url(apiUrl)
                        .ignoreContentType()
                        .connection();
                conn.ignoreHttpErrors(true);
                conn.header("Authorization", "Bearer " + sessionToken);
                resp = conn.execute();
                logRequestAndResponse(conn, resp);
                status = resp.statusCode();
                body = resp.body();
                logger.info("Bluesky API with token: HTTP {} - {}", status, body);
                // If token expired, clear cache and retry ONCE
                if (status == 400 && body != null && body.contains("ExpiredToken") && !retriedToken) {
                    logger.warn("Session token expired, clearing cache and retrying login...");
                    SESSION_TOKEN_CACHE.remove(username);
                    removeTokenFromDisk(username); // Remove from disk
                    this.sessionToken = null;
                    this.sessionToken = getSessionToken(); // Force new login
                    retriedToken = true;
                    conn = Http.url(apiUrl)
                        .ignoreContentType()
                        .connection();
                    conn.ignoreHttpErrors(true);
                    conn.header("Authorization", "Bearer " + sessionToken);
                    resp = conn.execute();
                    logRequestAndResponse(conn, resp);
                    status = resp.statusCode();
                    body = resp.body();
                    logger.info("Bluesky API with new token: HTTP {} - {}", status, body);
                }
            }
        } catch (Exception e) {
            logger.error("Exception during Bluesky API call: {}", e.getMessage());
            throw new IOException("Bluesky API error: " + e.getMessage(), e);
        }
        if (status == 401) {
            throw new IOException("Bluesky API returned 401 Unauthorized. Please check your username/app password in the config.");
        }
        if (status != 200) {
            throw new IOException("Bluesky API error (HTTP " + status + "): " + body + (triedCookies ? " (tried cookies)" : "") + (triedToken ? " (tried token)" : ""));
        }
        this.actorDid = did;
        return new org.json.JSONObject(body);
    }

    @Override
    protected JSONObject getNextPage(JSONObject json) throws IOException {
        if (!json.has("cursor")) throw new IOException("No more pages");
        String cursor = json.getString("cursor");
        if (this.actorDid == null) {
            this.actorDid = resolveHandleToDID(handle);
        }
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + this.actorDid
                + "&limit=100&cursor=" + cursor;
        Connection.Response resp = Http.url(apiUrl)
                .ignoreContentType()
                .header("Authorization", "Bearer " + sessionToken)
                .response();
        int status = resp.statusCode();
        String body = resp.body();
        if (status == 401) {
            throw new IOException("Bluesky API returned 401 Unauthorized. Please check your username/app password in the config.");
        }
        if (status != 200) {
            throw new IOException("Bluesky API error (HTTP " + status + "): " + body);
        }
        return new JSONObject(body);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        JSONArray feed = json.getJSONArray("feed");
        if (downloadLimitTracker.isLimitReached()) {
            maxDownloadLimitReached = true;
            return urls;
        }
        for (int i = 0; i < feed.length(); i++) {
            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                break;
            }
            JSONObject post = feed.getJSONObject(i).getJSONObject("post");
            if (post.has("embed")) {
                for (String mediaUrl : extractMediaUrlsFromEmbed(post.getJSONObject("embed"))) {
                    if (downloadLimitTracker.isLimitReached()) {
                        maxDownloadLimitReached = true;
                        break;
                    }
                    urls.add(mediaUrl);
                }
            }
        }
        return urls;
    }

    /**
     * Extracts downloadable media URLs from a Bluesky post embed view object.
     */
    public static List<String> extractMediaUrlsFromEmbed(JSONObject embed) {
        List<String> urls = new ArrayList<>();
        if (embed == null) {
            return urls;
        }
        String embedType = embed.optString("$type", "");

        // Native Bluesky videos are exposed as HLS playlists on post.embed views.
        if (embed.has("playlist")) {
            urls.add(embed.getString("playlist"));
        } else if (embedType.contains("embed.video")) {
            JSONObject video = embed.optJSONObject("video");
            if (video != null && video.has("uri")) {
                urls.add(video.getString("uri"));
            }
        } else if (embed.has("video")) {
            JSONObject video = embed.getJSONObject("video");
            if (video.has("uri")) {
                urls.add(video.getString("uri"));
            }
        }

        // Images
        if (embed.has("images")) {
            JSONArray images = embed.getJSONArray("images");
            for (int j = 0; j < images.length(); j++) {
                JSONObject image = images.getJSONObject(j);
                if (image.has("fullsize")) {
                    urls.add(image.getString("fullsize"));
                }
            }
        }

        // External link embeds (direct video files only)
        if (embed.has("external")) {
            JSONObject external = embed.getJSONObject("external");
            if (external.has("uri")) {
                String extUrl = external.getString("uri");
                if (VIDEO_URL_PATTERN.matcher(extUrl).matches()) {
                    urls.add(extUrl);
                }
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        if (isHlsPlaylist(url)) {
            downloadHlsVideo(url, index);
            return;
        }

        String path = url.getPath();
        if (path == null) path = "";
        String ext = null;
        int atIdx = path.lastIndexOf('@');
        if (atIdx != -1 && atIdx < path.length() - 1) {
            ext = path.substring(atIdx + 1);
        } else {
            int lastDot = path.lastIndexOf('.');
            if (lastDot != -1 && lastDot < path.length() - 1) {
                String afterDot = path.substring(lastDot + 1);
                if (afterDot.matches("[a-zA-Z0-9]{2,5}")) {
                    ext = afterDot;
                }
            }
        }
        // Bluesky CDN URLs (cdn.bsky.app/img/.../bafkre...) have no extension; default from path
        if (ext == null || ext.isEmpty()) {
            String lower = path.toLowerCase();
            if (lower.contains("/img/") || lower.contains("feed_fullsize")) {
                ext = "jpg";
            } else {
                ext = "mp4";
            }
        }
        String prefix = getPrefix(index);
        String resolvedFileName;
        if (atIdx != -1) {
            resolvedFileName = path.substring(path.lastIndexOf('/') + 1, atIdx);
        } else {
            resolvedFileName = path.substring(path.lastIndexOf('/') + 1);
        }

        boolean countTowardsLimit = true;
        if (downloadLimitTracker.isEnabled()) {
            try {
                Path existingPath = getFilePath(url, "", prefix, resolvedFileName, ext);
                if (Files.exists(existingPath)) {
                    if (!Utils.getConfigBoolean("file.overwrite", false)) {
                        logger.debug("Skipping existing file due to max download limit: {}", existingPath);
                        super.downloadExists(url, existingPath);
                        return;
                    }
                    countTowardsLimit = false;
                }
            } catch (IOException e) {
                logger.warn("Unable to determine existing file path for {}: {}", url, e.getMessage());
            }
        }

        if (!downloadLimitTracker.tryAcquire(url, countTowardsLimit)) {
            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                if (downloadLimitTracker.shouldNotifyLimitReached()) {
                    String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                    logger.info(message);
                    sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                }
            } else {
                logger.debug("Max download limit of {} currently allocated, deferring {}", maxDownloads, url);
            }
            return;
        }

        java.util.HashMap<String, String> options = new java.util.HashMap<>();
        options.put("prefix", prefix);
        options.put("extension", ext);
        options.put("fileName", resolvedFileName);
        boolean added = addURLToDownload(url, options);
        if (added) {
            if (Utils.getConfigBoolean("urls_only.save", false)) {
                handleSuccessfulDownload(url);
            }
        } else {
            downloadLimitTracker.onFailure(url);
        }
    }

    private static boolean isHlsPlaylist(URL url) {
        String external = url.toExternalForm().toLowerCase();
        return external.contains(".m3u8");
    }

    private void downloadHlsVideo(URL playlistUrl, int index) {
        String cid = extractVideoCid(playlistUrl);
        String prefix = getPrefix(index);
        String fileName = cid != null ? cid : ("video_" + index);
        boolean countTowardsLimit = true;

        if (downloadLimitTracker.isEnabled()) {
            try {
                Path existingPath = getFilePath(playlistUrl, "", prefix, fileName, "mp4");
                if (Files.exists(existingPath)) {
                    if (!Utils.getConfigBoolean("file.overwrite", false)) {
                        logger.debug("Skipping existing Bluesky video due to max download limit: {}", existingPath);
                        super.downloadExists(playlistUrl, existingPath);
                        return;
                    }
                    countTowardsLimit = false;
                }
            } catch (IOException e) {
                logger.warn("Unable to determine existing Bluesky video path for {}: {}", playlistUrl, e.getMessage());
            }
        }

        if (!downloadLimitTracker.tryAcquire(playlistUrl, countTowardsLimit)) {
            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                if (downloadLimitTracker.shouldNotifyLimitReached()) {
                    String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                    logger.info(message);
                    sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                }
            }
            return;
        }

        try {
            URL mediaPlaylist = resolveBestHlsVariant(playlistUrl);
            List<URL> segments = parseHlsSegmentUrls(mediaPlaylist);
            if (segments.isEmpty()) {
                throw new IOException("No HLS segments found in " + mediaPlaylist);
            }
            Path saveAs = getFilePath(playlistUrl, "", prefix, fileName, "mp4");
            Files.createDirectories(saveAs.getParent());
            long totalBytes = 0;
            try (OutputStream out = Files.newOutputStream(saveAs, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (URL segment : segments) {
                    totalBytes += appendHlsSegment(segment, out);
                }
            }
            if (totalBytes <= 0) {
                Files.deleteIfExists(saveAs);
                throw new IOException("Downloaded Bluesky HLS video is empty: " + playlistUrl);
            }
            logger.info("Downloaded Bluesky HLS video {} ({} segment(s), {} bytes)", saveAs, segments.size(), totalBytes);
            downloadCompleted(playlistUrl, saveAs);
        } catch (IOException e) {
            logger.warn("Bluesky HLS download failed for {}: {}", playlistUrl, e.getMessage());
            downloadLimitTracker.onFailure(playlistUrl);
            downloadErrored(playlistUrl, e.getMessage());
        }
    }

    private static String extractVideoCid(URL playlistUrl) {
        String path = playlistUrl.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (part.isBlank() || "watch".equals(part) || part.endsWith(".m3u8")) {
                continue;
            }
            if (part.startsWith("did:") || part.startsWith("did%3A")) {
                continue;
            }
            return part;
        }
        return null;
    }

    private URL resolveBestHlsVariant(URL playlistUrl) throws IOException {
        String body = fetchBlueskyText(playlistUrl);
        if (!body.contains("#EXT-X-STREAM-INF")) {
            return playlistUrl;
        }
        String[] lines = body.split("\\R");
        String bestVariant = null;
        long bestBandwidth = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }
            long bandwidth = 0;
            Matcher matcher = HLS_BANDWIDTH_PATTERN.matcher(line);
            if (matcher.find()) {
                bandwidth = Long.parseLong(matcher.group(1));
            }
            String variant = null;
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                    variant = candidate;
                    break;
                }
            }
            if (variant != null && bandwidth >= bestBandwidth) {
                bestBandwidth = bandwidth;
                bestVariant = variant;
            }
        }
        if (bestVariant == null) {
            throw new IOException("No HLS variants found in " + playlistUrl);
        }
        return resolveRelativeUrl(playlistUrl, bestVariant);
    }

    private List<URL> parseHlsSegmentUrls(URL mediaPlaylist) throws IOException {
        String body = fetchBlueskyText(mediaPlaylist);
        List<URL> segments = new ArrayList<>();
        for (String line : body.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            segments.add(resolveRelativeUrl(mediaPlaylist, line));
        }
        return segments;
    }

    private long appendHlsSegment(URL segmentUrl, OutputStream out) throws IOException {
        Map<String, String> headers = blueskyMediaHeaders();
        int retries = Utils.getConfigInteger("download.retries", 3);
        int baseDelaySeconds = Math.max(1, Utils.getConfigInteger("download.retry.sleep", 1000) / 1000);
        int readTimeoutMs = Math.max(120_000, Utils.getConfigInteger("download.timeout", 6000) * 10);
        return Http.transferWithRetry(segmentUrl, out, retries, baseDelaySeconds, AbstractRipper.USER_AGENT,
                headers, 15_000, readTimeoutMs);
    }

    private String fetchBlueskyText(URL resourceUrl) throws IOException {
        Map<String, String> headers = blueskyMediaHeaders();
        int retries = Utils.getConfigInteger("download.retries", 3);
        int baseDelaySeconds = Math.max(1, Utils.getConfigInteger("download.retry.sleep", 1000) / 1000);
        return Http.getWith429Retry(resourceUrl, retries, baseDelaySeconds, AbstractRipper.USER_AGENT, headers);
    }

    private static Map<String, String> blueskyMediaHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://bsky.app/");
        headers.put("Accept", "*/*");
        return headers;
    }

    private static URL resolveRelativeUrl(URL base, String relative) throws MalformedURLException {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return new URL(relative);
        }
        return new URL(base, relative);
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }

    @Override
    public void downloadCompleted(URL url, Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadExists(URL url, Path file) {
        super.downloadExists(url, file);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        downloadLimitTracker.onFailure(url);
        super.downloadErrored(url, reason);
    }

    @Override
    public boolean hasASAPRipping() {
        return maxDownloadLimitReached;
    }

    private void handleSuccessfulDownload(URL url) {
        if (downloadLimitTracker.onSuccess(url)) {
            maxDownloadLimitReached = true;
            if (downloadLimitTracker.shouldNotifyLimitReached()) {
                String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                logger.info(message);
                sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
            }
        }
    }
}
