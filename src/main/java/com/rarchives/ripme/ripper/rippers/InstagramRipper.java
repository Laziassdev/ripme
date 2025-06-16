package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import java.sql.Connection;
import java.nio.file.*;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.jsoup.Connection.Response;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class InstagramRipper extends AbstractJSONRipper {
    private static final Logger logger = LogManager.getLogger(InstagramRipper.class);
    private static final int WAIT_TIME = 2000;
    private static final int TIMEOUT = 20000;
    private static final int MAX_RETRIES = 3;
    private String csrftoken = null;
    
    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LogManager.getLogger(InstagramRipper.class).warn("SQLite JDBC driver not found. Firefox cookie authentication will not be available.");
        }
    }
    
    private String idString;
    private Map<String, String> cookies = new HashMap<>();
    private boolean hasNextPage = true;
    private String endCursor = null;

    public InstagramRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    protected String getDomain() {
        return "instagram.com";
    }

    @Override
    public String getHost() {
        return "instagram";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern pattern = Pattern.compile("https?://(?:www\\.)?instagram\\.com/(?<username>[^/?#]+)/?");
        Matcher matcher = pattern.matcher(url.toExternalForm());
        if (matcher.find()) {
            return matcher.group("username");
        }
        throw new MalformedURLException("Expected format: https://www.instagram.com/username/");
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }

    private String getFirefoxCookiesPath() {
        String userHome = System.getProperty("user.home");
        Path cookiesPath;
        
        if (System.getProperty("os.name").startsWith("Windows")) {
            cookiesPath = Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "Profiles");
        } else if (System.getProperty("os.name").startsWith("Mac")) {
            cookiesPath = Paths.get(userHome, "Library", "Application Support", "Firefox", "Profiles");
        } else {
            cookiesPath = Paths.get(userHome, ".mozilla", "firefox");
        }
        
        if (!Files.exists(cookiesPath)) {
            return null;
        }
        
        try {
            return Files.walk(cookiesPath)
                .filter(path -> path.getFileName().toString().equals("cookies.sqlite"))
                .findFirst()
                .map(Path::toString)
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> extractCookiesFromQuery(PreparedStatement stmt) throws SQLException {
        Map<String, String> extractedCookies = new HashMap<>();
        ResultSet rs = null;
        try {
            rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                String value = rs.getString("value");
                extractedCookies.put(name, value);
                
                if ("csrftoken".equals(name)) {
                    this.csrftoken = value;
                }
            }
            
            return extractedCookies;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    logger.warn("Error closing ResultSet: " + e.getMessage());
                }
            }
        }
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        String username = getGID(url);
        extractFirefoxCookies(); // Always try Firefox cookies first
        JSONObject json = getGraphQLUserPage(username, null);
        
        // Enhanced debug logging
        logger.debug("First page response: " + (json != null ? json.toString(2) : "null"));
        
        if (json == null || !json.has("data") || !json.getJSONObject("data").has("user")) {
            logger.error("Failed to fetch user data for " + username);
            throw new IOException("Failed to get user data from Instagram. Please ensure you're logged in with Firefox and have the latest cookies.");
        }

        if (!json.getJSONObject("data").getJSONObject("user").has("edge_owner_to_timeline_media")) {
            logger.error("No media found for user " + username);
            throw new IOException("No media data found for user. The account may be private or have no posts.");
        }

        return json;
    }    private JSONObject getGraphQLUserPage(String username, String endCursor) throws IOException {
        String userId = getUserID(username);
        if (userId == null || userId.isEmpty()) {
            throw new IOException("Failed to get user ID for " + username);
        }        // Use the user feed endpoint instead of GraphQL
        StringBuilder urlBuilder = new StringBuilder(String.format("https://www.instagram.com/api/v1/feed/user/%s/?count=50", userId));
        if (endCursor != null && !endCursor.isEmpty()) {
            urlBuilder.append("&max_id=").append(endCursor);
        }
        String url = urlBuilder.toString();
        logger.debug("Fetching API URL: " + url);

        try {            Response response = Http.url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-ASBD-ID", "129477")
                    .header("X-IG-WWW-Claim", "0")
                    .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                    .header("Origin", "https://www.instagram.com")
                    .header("DNT", "1")
                    .header("Connection", "keep-alive")
                    .header("Referer", "https://www.instagram.com/" + username + "/")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .cookies(cookies)
                    .ignoreContentType()
                    .response();

            int statusCode = response.statusCode();
            String jsonText = response.body();

            if (statusCode == 429) {
                throw new IOException("Rate limited by Instagram. Please wait a few minutes before trying again.");
            }
            
            if (statusCode != 200) {
                throw new IOException("HTTP error " + statusCode + " while fetching " + url);
            }

            if (jsonText == null || jsonText.isEmpty()) {
                throw new IOException("Empty response from Instagram API");
            }

            logger.debug("API Response: " + jsonText);            try {
                // Debug the raw response
                logger.debug("Raw response length: " + (jsonText != null ? jsonText.length() : 0));
                logger.debug("First 1000 chars of response: " + (jsonText != null ? jsonText.substring(0, Math.min(1000, jsonText.length())) : "null"));

                if (jsonText == null || jsonText.trim().isEmpty()) {
                    throw new IOException("Empty response from Instagram API");
                }

                if (!jsonText.trim().startsWith("{")) {
                    // Try to find JSON content within HTML response
                    Pattern pattern = Pattern.compile("window\\._sharedData = (\\{.*?\\});");
                    Matcher matcher = pattern.matcher(jsonText);
                    if (matcher.find()) {
                        jsonText = matcher.group(1);
                        logger.debug("Extracted JSON from HTML response");
                    } else {
                        // Try another common pattern
                        pattern = Pattern.compile("<script type=\"text/javascript\">window\\.__additionalDataLoaded\\('.*?',(\\{.*?\\})\\);</script>");
                        matcher = pattern.matcher(jsonText);
                        if (matcher.find()) {
                            jsonText = matcher.group(1);
                            logger.debug("Extracted JSON from additionalDataLoaded");
                        } else {
                            throw new IOException("Response is not valid JSON and couldn't extract JSON from HTML");
                        }
                    }
                }

                JSONObject json = new JSONObject(jsonText);
                if (!json.has("items") && !json.has("data")) {
                    if (json.has("message")) {
                        throw new IOException("Instagram API error: " + json.getString("message"));
                    }
                    throw new IOException("Invalid JSON response - missing items/data objects");
                }

                // Convert feed API response to match GraphQL structure if needed
                if (json.has("items")) {
                    JSONObject graphqlStyle = new JSONObject();
                    JSONObject data = new JSONObject();
                    JSONObject user = new JSONObject();
                    JSONObject timelineMedia = new JSONObject();
                    JSONArray edges = new JSONArray();

                    JSONArray items = json.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        JSONObject edge = new JSONObject();
                        JSONObject node = new JSONObject();
                        
                        // Copy relevant fields
                        node.put("__typename", item.has("video_versions") ? "GraphVideo" : "GraphImage");
                        node.put("display_url", item.has("image_versions2") ? 
                            item.getJSONObject("image_versions2").getJSONArray("candidates").getJSONObject(0).getString("url") : "");
                        if (item.has("video_versions")) {
                            node.put("video_url", item.getJSONArray("video_versions").getJSONObject(0).getString("url"));
                        }
                        
                        edge.put("node", node);
                        edges.put(edge);
                    }

                    timelineMedia.put("edges", edges);
                    if (json.has("more_available")) {
                        JSONObject pageInfo = new JSONObject();
                        pageInfo.put("has_next_page", json.getBoolean("more_available"));
                        if (json.has("next_max_id")) {
                            pageInfo.put("end_cursor", json.getString("next_max_id"));
                        }
                        timelineMedia.put("page_info", pageInfo);
                    }

                    user.put("edge_owner_to_timeline_media", timelineMedia);
                    data.put("user", user);
                    graphqlStyle.put("data", data);
                    
                    return graphqlStyle;
                }

                return json;
            } catch (JSONException e) {
                logger.error("Error parsing JSON response: " + e.getMessage());
                logger.debug("Raw response: " + jsonText);
                throw new IOException("Failed to parse Instagram API response: " + e.getMessage());
            }
        } catch (IOException e) {
            logger.error("Error fetching Instagram data: " + e.getMessage());
            throw e;
        }
    }


    private String getUserID(String username) throws IOException {
        // First try getting from cached cookies
        if (cookies.containsKey("ds_user_id")) {
            return cookies.get("ds_user_id");
        }
        
        // Otherwise fetch from profile page
        String profileUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=" + username;
        logger.debug("Fetching user ID from " + profileUrl);
        
        try {
            Response response = Http.url(profileUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .cookies(cookies)
                    .ignoreContentType()
                    .response();

            int statusCode = response.statusCode();
            String jsonText = response.body();

            if (statusCode != 200) {
                throw new IOException("Failed to get profile info: HTTP " + statusCode);
            }

            JSONObject json = new JSONObject(jsonText);
            if (!json.has("data") || !json.getJSONObject("data").has("user")) {
                throw new IOException("Invalid profile response - no user data found");
            }

            JSONObject user = json.getJSONObject("data").getJSONObject("user");
            if (!user.has("id")) {
                throw new IOException("No user ID found in profile response");
            }

            return user.getString("id");
        } catch (Exception e) {
            logger.error("Error getting user ID: " + e.getMessage());
            throw new IOException("Could not fetch user ID. You must be logged in via Firefox cookies.", e);
        }
    }

    private void extractFirefoxCookies() {
        try {
            String firefoxProfilePath = Utils.getConfigString("firefox.profile.path", "");
            if (firefoxProfilePath.isEmpty()) {
                // Try to find Firefox profile path automatically
                String appDataPath = System.getenv("APPDATA");
                if (appDataPath != null) {
                    firefoxProfilePath = appDataPath + "\\Mozilla\\Firefox\\Profiles";
                }
            }
            
            if (firefoxProfilePath == null || firefoxProfilePath.isEmpty()) {
                logger.warn("Firefox profile path not found. Cannot extract cookies.");
                return;
            }
            
            File profilesDir = new File(firefoxProfilePath);
            if (!profilesDir.exists()) {
                logger.warn("Firefox profiles directory does not exist: " + firefoxProfilePath);
                return;
            }

            // Find the newest default profile containing cookies.sqlite
            File newestProfile = null;
            long newestTime = 0;
            
            for (File profile : profilesDir.listFiles()) {
                if (profile.isDirectory() && profile.getName().endsWith(".default-release")) {
                    File cookiesFile = new File(profile, "cookies.sqlite");
                    if (cookiesFile.exists() && cookiesFile.lastModified() > newestTime) {
                        newestProfile = profile;
                        newestTime = cookiesFile.lastModified();
                    }
                }
            }

            if (newestProfile == null) {
                logger.warn("No Firefox profile with cookies.sqlite found");
                return;
            }

            File cookiesDB = new File(newestProfile, "cookies.sqlite");
            logger.info("Using cookies from: " + cookiesDB.getAbsolutePath());
            
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + cookiesDB.getAbsolutePath())) {
                String query = "SELECT name, value FROM moz_cookies WHERE host LIKE '%.instagram.com'";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    
                    while (rs.next()) {
                        String name = rs.getString("name");
                        String value = rs.getString("value");
                        cookies.put(name, value);
                        logger.debug("Extracted cookie: " + name + "=" + value.substring(0, Math.min(value.length(), 20)) + "...");
                    }
                }
                
                logger.info("Successfully extracted " + cookies.size() + " cookies from Firefox");
            } catch (SQLException e) {
                logger.error("Error reading Firefox cookies: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Error extracting Firefox cookies: " + e.getMessage(), e);
        }
    }    
    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        
        try {
            if (!json.has("data") || !json.getJSONObject("data").has("user")) {
                throw new RuntimeException("Invalid JSON response format - missing data or user object");
            }

            JSONObject user = json.getJSONObject("data").getJSONObject("user");
            if (!user.has("edge_owner_to_timeline_media")) {
                throw new RuntimeException("Invalid JSON response format - missing timeline media");
            }

            JSONObject timelineMedia = user.getJSONObject("edge_owner_to_timeline_media");
            JSONArray edges = timelineMedia.getJSONArray("edges");
            
            logger.debug("Found " + edges.length() + " media items");
            
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.getJSONObject(i).getJSONObject("node");
                
                String typename = edge.getString("__typename");
                switch (typename) {
                    case "GraphImage":
                        // Single image
                        urls.add(edge.getString("display_url"));
                        break;
                    case "GraphSidecar":
                        // Multiple images
                        JSONArray sidecarEdges = edge.getJSONObject("edge_sidecar_to_children").getJSONArray("edges");
                        for (int j = 0; j < sidecarEdges.length(); j++) {
                            JSONObject node = sidecarEdges.getJSONObject(j).getJSONObject("node");
                            urls.add(node.getString("display_url"));
                        }
                        break;
                    case "GraphVideo":
                        // Video
                        if (edge.has("video_url")) {
                            urls.add(edge.getString("video_url"));
                        } else {
                            // Fallback to thumbnail if video URL is not available
                            urls.add(edge.getString("display_url")); 
                        }
                        break;
                    default:
                        logger.warn("Unknown Instagram media type: " + typename);
                        break;
                }
            }
            
            // Handle pagination
            JSONObject pageInfo = timelineMedia.getJSONObject("page_info");
            if (pageInfo.getBoolean("has_next_page")) {
                this.endCursor = pageInfo.getString("end_cursor");
                this.hasNextPage = true;
            } else {
                this.hasNextPage = false;
            }
            
        } catch (JSONException e) {
            logger.error("Error parsing JSON response: " + e.getMessage());
            logger.debug("JSON data: " + json.toString(2));
            throw new RuntimeException("Error parsing Instagram response", e);
        }
        
        return urls;
    }

    @Override
    protected JSONObject getNextPage(JSONObject json) throws IOException {
        if (!hasNextPage) {
            throw new IOException("No more pages");
        }
        
        String username = getGID(url);
        return getGraphQLUserPage(username, endCursor);
    }
}
