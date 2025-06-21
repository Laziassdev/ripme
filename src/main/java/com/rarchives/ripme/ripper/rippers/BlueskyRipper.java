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
        // Try to get credentials from config
        this.username = Utils.getConfigString("bluesky.username", null);
        this.appPassword = Utils.getConfigString("bluesky.apppassword", null);
        if (this.username == null || this.appPassword == null) {
            throw new IOException("Bluesky username and app password must be set in ripme config (bluesky.username, bluesky.apppassword)");
        }
        this.sessionToken = getSessionToken();
    }

    private String getSessionToken() throws IOException {
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
        if (status != 200) {
            throw new IOException("Bluesky login failed (HTTP " + status + "): " + body);
        }
        JSONObject json = new JSONObject(body);
        if (!json.has("accessJwt")) {
            throw new IOException("Bluesky login response missing accessJwt: " + body);
        }
        return json.getString("accessJwt");
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
        String apiUrl = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + handle + "&limit=100";
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
