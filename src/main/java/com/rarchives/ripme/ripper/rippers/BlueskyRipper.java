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

    public BlueskyRipper(URL url) throws IOException {
        super(url);
        this.handle = getGID(url);
    }

    // Simple static cache for session tokens per username
    private static final java.util.Map<String, String> SESSION_TOKEN_CACHE = new java.util.HashMap<>();

    // Persistent session token cache file
    private static final String SESSION_TOKEN_FILE = System.getProperty("user.home") + "/.ripme_bluesky_token.json";

    private String getSessionToken() throws IOException {
        // Try persistent cache first
        String diskToken = loadTokenFromDisk(username);
        if (diskToken != null) {
            SESSION_TOKEN_CACHE.put(username, diskToken);
            return diskToken;
        }
        String loginUrl = "https://bsky.social/xrpc/com.atproto.server.createSession";
        JSONObject loginPayload = new JSONObject();
        loginPayload.put("identifier", username);
        loginPayload.put("password", appPassword);
        // Use Jsoup's requestBody for raw JSON POST
        Connection conn = Http.url(loginUrl).ignoreContentType().connection();
        conn.header("Content-Type", "application/json");
        conn.requestBody(loginPayload.toString());
        conn.method(Connection.Method.POST);
        Connection.Response resp = conn.execute();
        int status = resp.statusCode();
        String body = resp.body();
        if (status == 429) {
            // Rate limited: wait and retry once
            try {
                Thread.sleep(60000); // Wait 60 seconds
            } catch (InterruptedException ignored) {}
            resp = conn.execute();
            status = resp.statusCode();
            body = resp.body();
        }
        if (status != 200) {
            throw new IOException("Bluesky login failed (HTTP " + status + "): " + body);
        }
        JSONObject json = new JSONObject(body);
        if (!json.has("accessJwt")) {
            throw new IOException("Bluesky login response missing accessJwt: " + body);
        }
        String token = json.getString("accessJwt");
        SESSION_TOKEN_CACHE.put(username, token);
        saveTokenToDisk(username, token);
        return token;
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


    @Override
    protected JSONObject getFirstPage() throws IOException {
        // Try to use Firefox cookies if available
        java.util.Map<String, String> cookies = loadBlueskyCookiesFromFirefox();
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + handle + "&limit=100";
        Connection conn = Http.url(apiUrl)
                .ignoreContentType()
                .connection();
        if (!cookies.isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : cookies.entrySet()) {
                cookieHeader.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            conn.header("Cookie", cookieHeader.toString());
        } else {
            conn.header("Authorization", "Bearer " + sessionToken);
        }
        Connection.Response resp = conn.response();
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

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        JSONArray feed = json.getJSONArray("feed");
        for (int i = 0; i < feed.length(); i++) {
            JSONObject post = feed.getJSONObject(i).getJSONObject("post");
            if (post.has("embed")) {
                JSONObject embed = post.getJSONObject("embed");
                if (embed.has("images")) {
                    JSONArray images = embed.getJSONArray("images");
                    for (int j = 0; j < images.length(); j++) {
                        String imgUrl = images.getJSONObject(j).getString("fullsize");
                        urls.add(imgUrl);
                    }
                }
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }
}
