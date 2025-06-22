package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.jsoup.Connection;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;

public class BlueskyRipper extends AbstractJSONRipper {

    private static final String DOMAIN = "bsky.app";
    private static final String HOST = "bluesky";
    private String handle;
    private String sessionToken = null;
    private String appPassword = null;
    private String username = null;

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(BlueskyRipper.class);

    public BlueskyRipper(URL url) throws IOException {
        super(url);
        logger.info("BlueskyRipper constructor called for URL: {}", url);
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
        if (diskToken != null) {
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
            org.json.JSONObject obj = new org.json.JSONObject(content);
            if (obj.has(username)) return obj.getString(username);
        } catch (Exception ignored) {}
        return null;
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
    public String getHost() {
        return HOST;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        try {
            System.out.println("🔍 Checking GID for: " + url);
            Matcher m = Pattern.compile("^https?://bsky\\.(?:app|social)/profile/([^/]+)").matcher(url.toExternalForm());
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        // Try to use Firefox cookies if available
        java.util.Map<String, String> cookies = loadBlueskyCookiesFromFirefox();
        // Always resolve handle to DID for API call
        String did = resolveHandleToDID(handle);
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + did + "&limit=100";
        Connection.Response resp = null;
        int status = -1;
        String body = null;
        boolean triedCookies = false;
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
        return new org.json.JSONObject(body);
    }

    @Override
    protected JSONObject getNextPage(JSONObject json) throws IOException {
        if (!json.has("cursor")) throw new IOException("No more pages");
        String cursor = json.getString("cursor");
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + handle + "&limit=100&cursor=" + cursor;
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

    private int totalDownloaded = 0; // Track total downloads across all pages

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        JSONArray feed = json.getJSONArray("feed");
        int maxDownloads = Utils.getConfigInteger("maxdownloads", -1);
        for (int i = 0; i < feed.length(); i++) {
            if (maxDownloads > 0 && totalDownloaded >= maxDownloads) break;
            JSONObject post = feed.getJSONObject(i).getJSONObject("post");
            if (post.has("embed")) {
                JSONObject embed = post.getJSONObject("embed");
                // Images
                if (embed.has("images")) {
                    JSONArray images = embed.getJSONArray("images");
                    for (int j = 0; j < images.length(); j++) {
                        if (maxDownloads > 0 && totalDownloaded >= maxDownloads) break;
                        String imgUrl = images.getJSONObject(j).getString("fullsize");
                        urls.add(imgUrl);
                        totalDownloaded++;
                    }
                }
                // Videos (Bluesky video embeds)
                if (embed.has("video")) {
                    JSONObject video = embed.getJSONObject("video");
                    if (video.has("uri")) {
                        if (maxDownloads > 0 && totalDownloaded >= maxDownloads) break;
                        urls.add(video.getString("uri"));
                        totalDownloaded++;
                    }
                }
                // External (could be a video, e.g. mp4/gif hosted elsewhere)
                if (embed.has("external")) {
                    JSONObject external = embed.getJSONObject("external");
                    if (external.has("uri")) {
                        String extUrl = external.getString("uri");
                        // Only add if it looks like a video (mp4, webm, etc)
                        if (extUrl.matches(".*\\.(mp4|webm|mov|m4v|gif)(\\?.*)?$")) {
                            if (maxDownloads > 0 && totalDownloaded >= maxDownloads) break;
                            urls.add(extUrl);
                            totalDownloaded++;
                        }
                    }
                }
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        // Fix @ext to .ext in filename
        String fileName = url.getPath();
        String ext = null;
        int atIdx = fileName.lastIndexOf('@');
        if (atIdx != -1 && atIdx < fileName.length() - 1) {
            ext = fileName.substring(atIdx + 1);
        } else {
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot != -1 && lastDot < fileName.length() - 1) {
                ext = fileName.substring(lastDot + 1);
            }
        }
        String prefix = getPrefix(index);
        java.util.HashMap<String, String> options = new java.util.HashMap<>();
        options.put("prefix", prefix);
        if (ext != null) {
            options.put("extension", ext);
        }
        // Remove @ext from the fileName if present
        if (atIdx != -1) {
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1, atIdx);
        } else {
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }
        options.put("fileName", fileName);
        addURLToDownload(url, options);
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }
}
