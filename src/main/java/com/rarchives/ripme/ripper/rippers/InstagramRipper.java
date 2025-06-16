package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.Connection.Response;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import java.sql.*;
import java.nio.file.*;

public class InstagramRipper extends AbstractJSONRipper {    private static final Logger logger = LogManager.getLogger(InstagramRipper.class);
    private static final int WAIT_TIME = 2000; // 2 seconds between requests
    private static final int TIMEOUT = 20000; // 20 seconds timeout
    private static final int MAX_RETRIES = 3;
    private String csrftoken = null; // Added CSRF token variable

    // SQLite driver registration
    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            // Log warning but don't fail - SQLite is only needed for Firefox cookie auth
            LogManager.getLogger(InstagramRipper.class).warn("SQLite JDBC driver not found. Firefox cookie authentication will not be available.");
        }
    }    private String idString;
    private Map<String, String> cookies = new HashMap<>();
    private String endCursor = null;
    private boolean hasNextPage = true;
    private boolean fallbackToGraphQL = true; // Always use Graph API in 2025
    private String accessToken = null;

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
    }    @Override
    public JSONObject getFirstPage() throws IOException {
        setAuthToken();
        String username = getGID(url);
        JSONObject json = getGraphQLUserPage(username, null);
        
        // Check if we got data back
        if (!json.has("data")) {
            throw new IOException("Failed to get media data from Instagram API. Check your access token.");
        }
        
        JSONObject paging = json.optJSONObject("paging");
        if (paging != null) {
            hasNextPage = paging.has("next");
            endCursor = paging.optJSONObject("cursors") != null ? 
                       paging.getJSONObject("cursors").optString("after", null) : null;
        } else {
            hasNextPage = false;
            endCursor = null;
        }
        
        return json;
    }    @Override
    public JSONObject getNextPage(JSONObject json) throws IOException {
        if (!hasNextPage || endCursor == null) {
            return null;
        }
        String username = getGID(url);
        return getGraphQLUserPage(username, endCursor);
    }    private JSONObject getGraphQLUserPage(String username, String afterCursor) throws IOException {
        if (idString == null) {
            Response response;
            
            if (accessToken != null) {
                // Use Graph API with access token
                String fullUrlUser = format("https://graph.instagram.com/v18.0/me/media?fields=id,media_type,media_url,thumbnail_url,permalink,caption,children{media_type,media_url}&access_token=%s", 
                    URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
                if (afterCursor != null) {
                    fullUrlUser += "&after=" + afterCursor;
                }
                response = Http.url(fullUrlUser)
                    .ignoreContentType()
                    .get();
            } else {
                // Use cookie-based auth with proper CSRF token
                String fullUrlUser = format("https://www.instagram.com/api/v1/users/web_profile_info/?username=%s", username);
                response = Http.url(fullUrlUser)
                    .cookies(cookies)
                    .ignoreContentType()
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .header("authority", "www.instagram.com")
                    .header("accept", "*/*")
                    .header("accept-language", "en-US,en;q=0.9")
                    .header("dpr", "1")
                    .header("referer", "https://www.instagram.com/")
                    .header("origin", "https://www.instagram.com")
                    .header("x-asbd-id", "129477")
                    .header("x-csrftoken", csrftoken)
                    .header("x-ig-app-id", "936619743392459")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("x-web-device-id", cookies.get("ig_did"))
                    .get();
            }
            
            String rawProfile = response.body().text();
            if (rawProfile.trim().startsWith("<")) {
                throw new IOException("Instagram returned HTML instead of JSON. You may need to refresh your authentication.");
            }
            
            JSONObject shared = new JSONObject(rawProfile);
            if (shared.has("status") && !shared.getString("status").equals("ok")) {
                throw new IOException("Instagram API error: " + shared.optString("message", "Unknown error"));
            }
            
            try {
                idString = shared.getJSONObject("data").getJSONObject("user").getString("id");
            } catch (JSONException e) {
                throw new IOException("Failed to get user ID from Instagram response: " + e.getMessage());
            }
        }
        
        // Get user media
        String mediaUrl;
        if (accessToken != null) {
            mediaUrl = format("https://graph.instagram.com/v18.0/%s/media?fields=id,media_type,media_url,thumbnail_url,permalink,caption,children{media_type,media_url}&access_token=%s",
                idString, URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
            if (afterCursor != null) {
                mediaUrl += "&after=" + afterCursor;
            }
        } else {
            mediaUrl = format("https://www.instagram.com/api/v1/feed/user/%s/username/?count=12", idString);
            if (afterCursor != null) {
                mediaUrl += "&max_id=" + afterCursor;
            }
        }
        
        try {
            Thread.sleep(WAIT_TIME);
        } catch (InterruptedException e) {
            logger.error("[!] Interrupted while waiting to load next page", e);
        }
        
        Response mediaResponse = Http.url(mediaUrl)
            .cookies(cookies)
            .ignoreContentType()
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.9")
            .header("origin", "https://www.instagram.com")
            .header("referer", url.toExternalForm());
            
        if (accessToken == null) {
            // Add additional headers for cookie auth
            mediaResponse.header("x-asbd-id", "129477")
                .header("x-csrftoken", csrftoken)
                .header("x-ig-app-id", "936619743392459")
                .header("x-requested-with", "XMLHttpRequest")
                .header("x-web-device-id", cookies.get("ig_did"));
        }
        
        String rawJson = mediaResponse.get().body().text();
        if (rawJson.trim().startsWith("<")) {
            throw new IOException("Instagram returned HTML instead of JSON. Authentication may have expired.");
        }
        
        JSONObject json = new JSONObject(rawJson);
        if (json.has("status") && !json.getString("status").equals("ok")) {
            throw new IOException("Instagram API error: " + json.optString("message", "Unknown error"));
        }
        
        // Update pagination info
        if (accessToken != null) {
            JSONObject paging = json.optJSONObject("paging");
            if (paging != null) {
                hasNextPage = paging.has("next");
                endCursor = paging.optJSONObject("cursors") != null ? 
                           paging.getJSONObject("cursors").optString("after", null) : null;
            } else {
                hasNextPage = false;
                endCursor = null;
            }
        } else {
            hasNextPage = json.optBoolean("more_available", false);
            endCursor = json.optString("next_max_id", null);
        }
        
        return json;
    }    private void setAuthToken() throws IOException {
        // First try access token
        accessToken = Utils.getConfigString("instagram.access_token", null);
        
        // If no access token, try getting cookies from Firefox
        if (accessToken == null) {
            try {
                extractFirefoxCookies();
            } catch (Exception e) {
                logger.warn("Could not extract Firefox cookies: " + e.getMessage());
                throw new IOException("No authentication method available. Please either:\n" +
                                   "1. Set 'instagram.access_token' in rip.properties, or\n" +
                                   "2. Login to Instagram in Firefox");
            }
        }
    }
    
    private Map<String, String> extractCookiesFromQuery(PreparedStatement stmt) throws SQLException {
        Map<String, String> extractedCookies = new HashMap<>();
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            String name = rs.getString("name");
            String value = rs.getString("value");
            extractedCookies.put(name, value);
            
            // Store CSRF token if we find it
            if ("csrftoken".equals(name)) {
                this.csrftoken = value;
            }
        }
        
        // Verify we got the essential cookies
        if (!extractedCookies.containsKey("sessionid")) {
            throw new SQLException("No valid Instagram session found in Firefox cookies");
        }
        
        if (this.csrftoken == null) {
            throw new SQLException("No CSRF token found in Instagram cookies");
        }
        
        return extractedCookies;
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

    private void extractFirefoxCookies() throws IOException {
        String cookiesPath = getFirefoxCookiesPath();
        if (cookiesPath == null) {
            throw new IOException("Firefox cookies.sqlite not found");
        }
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + cookiesPath)) {
            // Try newer Firefox cookie schema first
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT name, value FROM moz_cookies WHERE baseDomain='instagram.com'")) {
                cookies = extractCookiesFromQuery(stmt);
            } catch (SQLException e) {
                // Try older Firefox cookie schema
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT name, value FROM moz_cookies WHERE host LIKE '%instagram.com'")) {
                    cookies = extractCookiesFromQuery(stmt);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read Firefox cookies: " + e.getMessage());
        }
    }
    
    private JSONObject getJsonObjectFromDoc(Document document) {
        for (Element script : document.select("script[type=text/javascript]")) {
            String scriptText = script.data();
            if (scriptText.startsWith("window._sharedData")) {
                try {
                    String jsonText = scriptText.replaceAll("[^\\{]*([\\{].*})[^\\}]*", "$1");
                    return new JSONObject(jsonText);
                } catch (Exception e) {
                    logger.warn("Failed to parse embedded script JSON", e);
                }
            }
        }
        logger.error("No embedded JSON found in Instagram document");
        return null;
    }

    private JSONObject getJsonObjectByPath(JSONObject object, String key) {
        if (object == null) {
            throw new IllegalArgumentException("Null JSON object for path: " + key);
        }
        Pattern arrayPattern = Pattern.compile("(?<arr>.*)\\[(?<idx>\\d+)]");
        JSONObject result = object;
        for (String s : key.split("\\.")) {
            Matcher m = arrayPattern.matcher(s);
            result = m.matches() ?
                result.getJSONArray(m.group("arr")).getJSONObject(Integer.parseInt(m.group("idx"))) :
                result.getJSONObject(s);
        }
        return result;
    }    @Override
    public List<String> getURLsFromJSON(JSONObject json) {
        List<String> result = new ArrayList<>();
        try {
            if (accessToken != null) {
                // Handle Graph API response format
                JSONArray items = json.getJSONArray("data");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String mediaType = item.getString("media_type");
                    
                    switch (mediaType) {
                        case "IMAGE":
                            result.add(item.getString("media_url"));
                            break;
                            
                        case "VIDEO":
                            result.add(item.getString("media_url"));
                            break;
                            
                        case "CAROUSEL_ALBUM":
                            if (item.has("children")) {
                                JSONObject children = item.getJSONObject("children");
                                if (children.has("data")) {
                                    JSONArray childrenData = children.getJSONArray("data");
                                    for (int j = 0; j < childrenData.length(); j++) {
                                        JSONObject child = childrenData.getJSONObject(j);
                                        String childMediaUrl = child.getString("media_url");
                                        if (childMediaUrl != null) {
                                            result.add(childMediaUrl);
                                        }
                                    }
                                }
                            }
                            break;
                    }
                }
            } else {
                // Handle cookie-based API response format
                JSONArray items = json.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String mediaType = item.getString("media_type");
                    
                    switch (mediaType) {
                        case "1": // IMAGE
                            if (item.has("image_versions2")) {
                                JSONArray candidates = item.getJSONObject("image_versions2")
                                    .getJSONArray("candidates");
                                if (candidates.length() > 0) {
                                    result.add(candidates.getJSONObject(0).getString("url"));
                                }
                            }
                            break;
                            
                        case "2": // VIDEO
                            if (item.has("video_versions")) {
                                JSONArray versions = item.getJSONArray("video_versions");
                                if (versions.length() > 0) {
                                    result.add(versions.getJSONObject(0).getString("url"));
                                }
                            }
                            break;
                            
                        case "8": // CAROUSEL
                            if (item.has("carousel_media")) {
                                JSONArray carousel = item.getJSONArray("carousel_media");
                                for (int j = 0; j < carousel.length(); j++) {
                                    JSONObject carouselItem = carousel.getJSONObject(j);
                                    String carouselType = carouselItem.getString("media_type");
                                    
                                    if (carouselType.equals("1")) { // Image
                                        if (carouselItem.has("image_versions2")) {
                                            JSONArray candidates = carouselItem.getJSONObject("image_versions2")
                                                .getJSONArray("candidates");
                                            if (candidates.length() > 0) {
                                                result.add(candidates.getJSONObject(0).getString("url"));
                                            }
                                        }
                                    } else if (carouselType.equals("2")) { // Video
                                        if (carouselItem.has("video_versions")) {
                                            JSONArray versions = carouselItem.getJSONArray("video_versions");
                                            if (versions.length() > 0) {
                                                result.add(versions.getJSONObject(0).getString("url"));
                                            }
                                        }
                                    }
                                }
                            }
                            break;
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Failed to extract media URLs from response", ex);
            logger.debug("JSON response was: " + json.toString());
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", null, cookies);
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }
}
