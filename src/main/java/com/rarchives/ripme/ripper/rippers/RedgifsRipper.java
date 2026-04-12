package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.utils.URIBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RedgifsRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(RedgifsRipper.class);

    private static final String HOST = "redgifs.com";
    private static final String HOST_2 = "gifdeliverynetwork.com";
    private static final String GIFS_DETAIL_ENDPOINT = "https://api.redgifs.com/v2/gifs/%s";
    private static final String USERS_SEARCH_ENDPOINT = "https://api.redgifs.com/v2/users/%s/search";
    private static final String GALLERY_ENDPOINT = "https://api.redgifs.com/v2/gallery/%s";
    private static final String SEARCH_ENDPOINT = "https://api.redgifs.com/v2/search/%s";
    private static final String TAGS_ENDPOINT = "https://api.redgifs.com/v2/gifs/search";
    // Niche listing endpoint is path-based (…/niches/{slug}/gifs or …/images)
    private static final String NICHES_LISTING_ENDPOINT = "https://api.redgifs.com/v2/niches/%s/%s";
    private static final String TEMPORARY_AUTH_ENDPOINT = "https://api.redgifs.com/v2/auth/temporary";
    private static final Pattern PROFILE_PATTERN = Pattern
            .compile("^https?://[a-zA-Z0-9.]*redgifs\\.com/users/([a-zA-Z0-9_.-]+).*$");
    private static final Pattern SEARCH_PATTERN = Pattern.compile(
            "^https?:\\/\\/[a-zA-Z0-9.]*redgifs\\.com\\/search(?:\\/[a-zA-Z]+)?\\?.*?query=([a-zA-Z0-9-_+%]+).*$");
    private static final Pattern TAGS_PATTERN = Pattern
            .compile("^https?:\\/\\/[a-zA-Z0-9.]*redgifs\\.com\\/gifs\\/([a-zA-Z0-9_.,-]+).*$");
    private static final Pattern NICHES_PATTERN = Pattern
            .compile("^https?://[a-zA-Z0-9.]*redgifs\\.com/niches/([a-zA-Z0-9_.-]+).*$");
    private static final Pattern SINGLETON_PATTERN = Pattern
            .compile("^https?://[a-zA-Z0-9.]*redgifs\\.com/(?:watch|ifr)/([a-zA-Z0-9_-]+)(?:[/?#].*)?$");
    private static final Pattern DIRECT_IMAGE_PATTERN = Pattern
            .compile("^https?://i\\.redgifs\\.com/i/([a-zA-Z0-9_-]+)(?:\\.[a-zA-Z0-9]+)?(?:\\?.*)?$");
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern
            .compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
    private static final List<String> REDGIFS_COOKIE_DOMAINS = Arrays.asList("redgifs.com", "www.redgifs.com", "api.redgifs.com");

    /**
     * Keep a single auth token for the complete lifecycle of the app.
     * This should prevent fetching of multiple tokens.
     */
    private static String authToken = "";

    String username = "";
    int count = 40;
    int currentPage = 1;
    int maxPages = 1;

    public RedgifsRipper(URL url) throws IOException, URISyntaxException {
        super(new URI(url.toExternalForm().replace("thumbs.", "")).toURL());
    }

    @Override
    public String getDomain() {
        return "redgifs.com";
    }

    @Override
    public String getHost() {
        return "redgifs";
    }

    @Override
    public boolean canRip(URL url) {
        return url.getHost().endsWith(HOST) || url.getHost().endsWith(HOST_2);
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        String sUrl = url.toExternalForm();
        sUrl = sUrl.replace("/gifs/detail", "");
        sUrl = sUrl.replace("/amp", "");
        sUrl = sUrl.replace("/ifr/", "/watch/");
        sUrl = sUrl.replace("gifdeliverynetwork.com", "redgifs.com/watch");
        Matcher directImageMatcher = DIRECT_IMAGE_PATTERN.matcher(sUrl);
        if (directImageMatcher.matches()) {
            sUrl = "https://www.redgifs.com/watch/" + directImageMatcher.group(1);
        }
        return new URI(sUrl).toURL();
    }

    public Matcher isProfile() {
        return PROFILE_PATTERN.matcher(url.toExternalForm());
    }

    public Matcher isSearch() {
        return SEARCH_PATTERN.matcher(url.toExternalForm());
    }

    public Matcher isTags() {
        return TAGS_PATTERN.matcher(url.toExternalForm());
    }

    public Matcher isNiche() {
        return NICHES_PATTERN.matcher(url.toExternalForm());
    }

    private Matcher isNiche(URL targetUrl) {
        return NICHES_PATTERN.matcher(targetUrl.toExternalForm());
    }

    private Matcher isProfile(URL targetUrl) {
        return PROFILE_PATTERN.matcher(targetUrl.toExternalForm());
    }

    private Matcher isSearch(URL targetUrl) {
        return SEARCH_PATTERN.matcher(targetUrl.toExternalForm());
    }

    private Matcher isTags(URL targetUrl) {
        return TAGS_PATTERN.matcher(targetUrl.toExternalForm());
    }

    private Matcher isSingleton(URL targetUrl) {
        return SINGLETON_PATTERN.matcher(targetUrl.toExternalForm());
    }

    public Matcher isSingleton() {
        return SINGLETON_PATTERN.matcher(url.toExternalForm());
    }

    @Override
    public String getAlbumTitle(URL url) throws MalformedURLException, URISyntaxException {
        if (isNiche(url).matches()) {
            return getHost() + "_niches_" + getGID(url);
        }
        return super.getAlbumTitle(url);
    }

    @Override
    public void setWorkingDir(URL url) throws IOException, URISyntaxException {
        Path wd = Utils.getWorkingDirectory();
        String title;
        if (isNiche(this.url).matches()) {
            // Keep niche folder naming stable regardless of album_titles.save setting.
            title = getAlbumTitle(this.url);
        } else if (Utils.getConfigBoolean("album_titles.save", true)) {
            title = getAlbumTitle(this.url);
        } else {
            title = super.getAlbumTitle(this.url);
        }
        logger.debug("Using album title '{}'", title);

        title = Utils.normalizeFolderName(title);
        wd = wd.resolve(title);
        if (!Files.exists(wd)) {
            logger.info("[+] Creating directory: {}", Utils.removeCWD(wd));
            Files.createDirectory(wd);
        }
        this.workingDir = wd.toFile();
        logger.info("Set working directory to: {}", this.workingDir);
    }

    @Override
    public JSONObject getFirstPage() throws IOException {
        try {
            if (authToken == null || authToken.isBlank()) {
                fetchAuthToken();
            }

            if (isSingleton().matches()) {
                maxPages = 1;
                String gifDetailsURL = String.format(GIFS_DETAIL_ENDPOINT, getGID(url));
                return getJSONWithBearerAuth(gifDetailsURL);
            } else if (isSearch().matches() || isTags().matches() || isNiche().matches()) {
                var json = getJSONWithBearerAuth(getSearchTagsOrNicheURL());
                maxPages = extractMaxPages(json, currentPage);
                return json;
            } else {
                username = getGID(url);
                var uri = new URIBuilder(String.format(USERS_SEARCH_ENDPOINT, username));
                uri.addParameter("order", "new");
                uri.addParameter("count", Integer.toString(count));
                uri.addParameter("page", Integer.toString(currentPage));
                var json = getJSONWithBearerAuth(uri.build().toURL());
                maxPages = extractMaxPages(json, currentPage);
                return json;
            }
        } catch (URISyntaxException e) {
            throw new IOException("Failed to build first page url", e);
        }
    }

    @Override
    public void downloadURL(URL url, int index) {
         // redgifs is easy to trigger rate limit, so be a little cautious
        sleep(3000);
        addURLToDownload(url, getPrefix(index));
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = isProfile(url);
        if (m.matches()) {
            return m.group(1);
        }
        m = isSearch(url);
        if (m.matches()) {
            var sText = m.group(1);
            if (sText == null || sText.isBlank()) {
                throw new MalformedURLException(
                        String.format("Expected redgifs.com/search?query=searchtext\n Got %s", url));
            }
            sText = URLDecoder.decode(sText, StandardCharsets.UTF_8);
            sText = sText.replaceAll("[^A-Za-z0-9_-]", "-");
            return sText;
        }
        m = isTags(url);
        if (m.matches()) {
            var sText = m.group(1);
            if (sText == null || sText.isBlank()) {
                throw new MalformedURLException(String.format("Expected redgifs.com/gifs/searchtags\n Got %s", url));
            }
            sText = URLDecoder.decode(sText, StandardCharsets.UTF_8);
            var list = Arrays.asList(sText.split(","));
            if (list.size() > 1) {
                logger.warn("Url with multiple tags found. \nThey will be sorted alphabetically for folder name.");
            }
            Collections.sort(list);
            var gid = list.stream().reduce("", (acc, val) -> acc.concat("_" + val));
            gid = gid.replaceAll("[^A-Za-z0-9_-]", "-");
            return gid;
        }
        m = isNiche(url);
        if (m.matches()) {
            return m.group(1).replaceAll("[^A-Za-z0-9_-]", "-");
        }
        m = isSingleton(url);
        if (m.matches()) {
            return m.group(1).split("-")[0];
        }
        throw new MalformedURLException(
                "Expected redgifs.com format: "
                        + "redgifs.com/watch/id or "
                        + "redgifs.com/users/id or "
                        + "redgifs.com/gifs/id or "
                        + "redgifs.com/search?query=text"
                        + " Got: " + url);
    }

    @Override
    public JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        // rate limit on getting next page to not look too much like a crawler/bot
        sleep(1000);

        if (currentPage == maxPages || isSingleton().matches()) {
            return null;
        }
        currentPage++;
        if (isSearch().matches() || isTags().matches() || isNiche().matches()) {
            var json = getJSONWithBearerAuth(getSearchTagsOrNicheURL());
            // Handle rare maxPages change during a rip
            maxPages = extractMaxPages(json, currentPage);
            return json;
        } else if (isProfile().matches()) {
            var uri = new URIBuilder(String.format(USERS_SEARCH_ENDPOINT, getGID(url)));
            uri.addParameter("order", "new");
            uri.addParameter("count", Integer.toString(count));
            uri.addParameter("page", Integer.toString(currentPage));
            var json = getJSONWithBearerAuth(uri.build().toURL());
            // Handle rare maxPages change during a rip
            maxPages = extractMaxPages(json, currentPage);
            return json;
        } else {
            return null;
        }
    }

    @Override
    public List<String> getURLsFromJSON(JSONObject json) {
        List<String> result = new ArrayList<>();
        if (isProfile().matches() || isSearch().matches() || isTags().matches() || isNiche().matches()) {
            var gifs = getGifEntries(json);
            for (var gif : gifs) {
                if (((JSONObject) gif).isNull("gallery")) {
                    var hdURL = ((JSONObject) gif).getJSONObject("urls").getString("hd");
                    result.add(hdURL);
                } else {
                    var galleryID = ((JSONObject) gif).getString("gallery");
                    var gifID = ((JSONObject) gif).getString("id");
                    result.addAll(getURLsForGallery(galleryID, gifID));
                }
            }
        } else {
            var gif = json.getJSONObject("gif");
            if (gif.isNull("gallery")) {
                String hdURL = gif.getJSONObject("urls").getString("hd");
                result.add(hdURL);
            } else {
                var galleryID = gif.getString("gallery");
                var gifID = gif.getString("id");
                result.addAll(getURLsForGallery(galleryID, gifID));
            }
        }
        return result;
    }

    private static JSONArray getGifEntries(JSONObject json) {
        List<String> arrayKeys = Arrays.asList("gifs", "items", "posts", "results");
        for (String key : arrayKeys) {
            if (json.has(key) && !json.isNull(key) && json.get(key) instanceof JSONArray) {
                return json.getJSONArray(key);
            }
        }

        List<String> containerKeys = Arrays.asList("page", "pagination", "meta", "data", "result");
        for (String containerKey : containerKeys) {
            if (!json.has(containerKey) || json.isNull(containerKey) || !(json.get(containerKey) instanceof JSONObject)) {
                continue;
            }
            JSONObject container = json.getJSONObject(containerKey);
            for (String key : arrayKeys) {
                if (container.has(key) && !container.isNull(key) && container.get(key) instanceof JSONArray) {
                    return container.getJSONArray(key);
                }
            }
        }

        logger.warn("Redgifs response did not include a recognizable gif list field");
        return new JSONArray();
    }

    /**
     * Get all images for a gif url with multiple images
     *
     * @param galleryID gallery id
     * @param gifID     gif id with multiple images for logging
     * @return List<String>
     */
    private static List<String> getURLsForGallery(String galleryID, String gifID) {
        List<String> list = new ArrayList<>();
        if (galleryID == null || galleryID.isBlank()) {
            return list;
        }
        try {
            var json = getJSONWithBearerAuth(String.format(GALLERY_ENDPOINT, galleryID));
            for (var gif : json.getJSONArray("gifs")) {
                var hdURL = ((JSONObject) gif).getJSONObject("urls").getString("hd");
                list.add(hdURL);
            }
        } catch (IOException e) {
            logger.error(String.format("Error fetching gallery %s for gif %s", galleryID, gifID), e);
        }
        return list;
    }

    /**
     * Static helper method for retrieving video URLs for usage in RipUtils.
     * Most of the code is lifted from getFirstPage and getURLsFromJSON
     *
     * @param url URL to redgif page
     * @return URL to video
     * @throws IOException
     */
    public static String getVideoURL(URL url) throws IOException, URISyntaxException {
        logger.info("Retrieving " + url.toExternalForm());
        var m = SINGLETON_PATTERN.matcher(url.toExternalForm());
        if (!m.matches()) {
            m = DIRECT_IMAGE_PATTERN.matcher(url.toExternalForm());
        }
        if (!m.matches()) {
            throw new IOException(String.format("Cannot fetch redgif url %s", url.toExternalForm()));
        }
        if (authToken == null || authToken.isBlank()) {
            fetchAuthToken();
        }
        var gid = m.group(1).split("-")[0];
        var gifDetailsURL = String.format(GIFS_DETAIL_ENDPOINT, gid);
        var json = getJSONWithBearerAuth(gifDetailsURL);
        var gif = json.getJSONObject("gif");
        if (!gif.isNull("gallery")) {
            // TODO check how to handle a image gallery
            throw new IOException(String.format("Multiple images found for url %s", url));
        }
        return gif.getJSONObject("urls").getString("hd");
    }

    /**
     * Fetch a temorary auth token for the rip
     *
     * @throws IOException
     */
    private static void fetchAuthToken() throws IOException {
        String redgifsCookieToken = fetchAuthTokenFromConfiguredRedgifsCookies();
        if (redgifsCookieToken != null && !redgifsCookieToken.isBlank()) {
            authToken = redgifsCookieToken;
            logger.info("Loaded Redgifs auth token from configured cookies");
            return;
        }

        String redditToken = fetchAuthTokenFromRedditCookies();
        if (redditToken != null && !redditToken.isBlank()) {
            authToken = redditToken;
            logger.info("Loaded Redgifs auth token from Reddit Firefox cookies");
            return;
        }

        var json = Http.url(TEMPORARY_AUTH_ENDPOINT).getJSON();
        var token = json.getString("token");
        authToken = token;
        logger.info("Incase of redgif 401 errors, please restart the app to refresh the auth token");
    }

    private static JSONObject getJSONWithBearerAuth(URL requestUrl) throws IOException {
        return getJSONWithBearerAuth(requestUrl.toExternalForm());
    }

    private static JSONObject getJSONWithBearerAuth(String requestUrl) throws IOException {
        if (authToken == null || authToken.isBlank()) {
            fetchAuthToken();
        }

        try {
            return Http.url(requestUrl).header("Authorization", "Bearer " + authToken).getJSON();
        } catch (IOException e) {
            if (!isUnauthorizedError(e)) {
                throw e;
            }

            logger.info("Redgifs authorization failed; refreshing auth token and retrying once");
            authToken = "";
            fetchAuthToken();
            return Http.url(requestUrl).header("Authorization", "Bearer " + authToken).getJSON();
        }
    }

    private static boolean isUnauthorizedError(IOException e) {
        return e.getMessage() != null && e.getMessage().contains("Status Code 401");
    }

    private static String fetchAuthTokenFromConfiguredRedgifsCookies() {
        for (String cookieDomain : REDGIFS_COOKIE_DOMAINS) {
            String cookieStr = Utils.getConfigString("cookies." + cookieDomain, "");
            if (cookieStr == null || cookieStr.isBlank()) {
                continue;
            }

            Map<String, String> cookies = RipUtils.getCookiesFromString(cookieStr);
            String token = extractAccessTokenFromCookies(cookies);
            if (token != null && !token.isBlank()) {
                logger.info("Found Redgifs auth token in cookies.{}", cookieDomain);
                return token;
            }
        }

        return null;
    }

    private static String fetchAuthTokenFromRedditCookies() {
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                logger.debug("SQLite JDBC driver not available; cannot load Reddit cookies", e);
                return null;
            }

            String userHome = System.getProperty("user.home");
            Path profilesIniPath = Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "profiles.ini");
            if (!Files.exists(profilesIniPath)) {
                logger.debug("Firefox profiles.ini not found at {}", profilesIniPath);
                return null;
            }

            List<String> lines = Files.readAllLines(profilesIniPath, StandardCharsets.UTF_8);
            List<String> profilePaths = new ArrayList<>();
            for (String line : lines) {
                if (line != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Path=")) {
                        profilePaths.add(trimmed.substring(5));
                    }
                }
            }

            if (profilePaths.isEmpty()) {
                logger.debug("No Firefox profiles discovered in {}", profilesIniPath);
                return null;
            }

            logger.info("Searching Firefox profiles for Reddit auth token: {}", profilePaths);

            for (String profilePath : profilePaths) {
                String sqlitePathString = userHome + "/AppData/Roaming/Mozilla/Firefox/Profiles/" + profilePath + "/cookies.sqlite";
                sqlitePathString = sqlitePathString.replace("Profiles/Profiles/", "Profiles/");
                Path sqlitePath = Paths.get(sqlitePathString);
                if (!Files.exists(sqlitePath)) {
                    logger.debug("cookies.sqlite not found for profile {}", profilePath);
                    continue;
                }

                Path tempCopy = null;
                try {
                    tempCopy = Files.createTempFile("ripme-reddit-cookies", ".sqlite");
                    tempCopy.toFile().deleteOnExit();
                    Files.copy(sqlitePath, tempCopy, StandardCopyOption.REPLACE_EXISTING);
                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempCopy.toString());
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT name, value FROM moz_cookies WHERE host LIKE '%reddit.com'")) {
                        Map<String, String> cookies = new HashMap<>();
                        while (rs.next()) {
                            cookies.put(rs.getString("name"), rs.getString("value"));
                        }

                        String token = extractAccessTokenFromCookies(cookies);
                        if (token != null && !token.isBlank()) {
                            logger.info("Found Reddit access token in Firefox profile {}", profilePath);
                            return token;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Unable to read Reddit cookies from profile {}", profilePath, e);
                } finally {
                    if (tempCopy != null) {
                        try {
                            Files.deleteIfExists(tempCopy);
                        } catch (IOException e) {
                            logger.debug("Failed to delete temporary Firefox cookie copy", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Unexpected error while loading Reddit token from Firefox cookies", e);
        }

        return null;
    }

    private static String extractAccessTokenFromCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        List<String> candidates = Arrays.asList("token_v2", "token");
        for (String candidate : candidates) {
            if (cookies.containsKey(candidate)) {
                String token = decodeAccessTokenFromCookieValue(cookies.get(candidate));
                if (token != null && !token.isBlank()) {
                    return token;
                }
            }
        }

        for (String value : cookies.values()) {
            String token = decodeAccessTokenFromCookieValue(value);
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        return null;
    }

    private static String decodeAccessTokenFromCookieValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        String jsonPortion = extractJsonFromString(decoded);
        if (jsonPortion != null) {
            try {
                JSONObject json = new JSONObject(jsonPortion);
                if (json.has("accessToken")) {
                    return normalizeBearer(json.getString("accessToken"));
                }
            } catch (JSONException e) {
                logger.debug("Failed to parse JSON from Reddit token cookie", e);
            }
        }

        Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(decoded);
        if (matcher.find()) {
            return normalizeBearer(matcher.group(1));
        }

        if (decoded.startsWith("Bearer ")) {
            return normalizeBearer(decoded);
        }

        return null;
    }

    private static String extractJsonFromString(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private static String normalizeBearer(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            normalized = normalized.substring(7).trim();
        }
        return normalized;
    }

    public static int extractMaxPages(JSONObject json, int fallbackPage) {
        if (json == null) {
            return fallbackPage;
        }

        if (json.has("pages") && !json.isNull("pages")) {
            return json.getInt("pages");
        }

        List<String> containerKeys = Arrays.asList("page", "pagination", "meta");
        List<String> pageCountKeys = Arrays.asList("pages", "pageCount", "totalPages", "total_pages", "lastPage");
        List<String> totalCountKeys = Arrays.asList("total", "totalCount", "total_count", "itemsTotal");
        List<String> perPageKeys = Arrays.asList("count", "perPage", "per_page", "itemsPerPage", "limit");

        Integer pageCount = extractFirstIntValue(json, pageCountKeys);
        if (pageCount != null) {
            return pageCount;
        }

        Integer totalCount = extractFirstIntValue(json, totalCountKeys);
        Integer perPage = extractFirstIntValue(json, perPageKeys);
        if (totalCount != null && perPage != null && perPage > 0) {
            return Math.max(1, (int) Math.ceil((double) totalCount / perPage));
        }

        for (String containerKey : containerKeys) {
            if (!json.has(containerKey) || json.isNull(containerKey)) {
                continue;
            }
            Object paginationObj = json.get(containerKey);
            if (!(paginationObj instanceof JSONObject)) {
                continue;
            }
            JSONObject pagination = (JSONObject) paginationObj;
            pageCount = extractFirstIntValue(pagination, pageCountKeys);
            if (pageCount != null) {
                return pageCount;
            }

            totalCount = extractFirstIntValue(pagination, totalCountKeys);
            perPage = extractFirstIntValue(pagination, perPageKeys);
            if (totalCount != null && perPage != null && perPage > 0) {
                return Math.max(1, (int) Math.ceil((double) totalCount / perPage));
            }
        }

        logger.warn("Redgifs response did not include a recognizable page count field; defaulting max pages to {}", fallbackPage);
        return fallbackPage;
    }

    private static Integer extractFirstIntValue(JSONObject json, List<String> keys) {
        for (String key : keys) {
            if (json.has(key) && !json.isNull(key)) {
                return json.getInt(key);
            }
        }
        return null;
    }

    /**
     * Map browser url query params to search or tags endpoint query params and
     * return the complete url.
     *
     * Search text for search url comes from the query params, whereas search text
     * for tags url comes from the path.
     *
     * Tab type for search url comes from the path whereas, tab type for tags url
     * comes from query params.
     *
     * @return Search or tags endpoint url
     */
    private URL getSearchTagsOrNicheURL() throws IOException, URISyntaxException {
        if (isNiche().matches()) {
            return getNicheURL();
        }

        URIBuilder uri;
        Map<String, String> endpointQueryParams = new HashMap<>();
        var browserURLQueryParams = new URIBuilder(url.toString()).getQueryParams();
        for (var qp : browserURLQueryParams) {
            var name = qp.getName();
            var value = qp.getValue();
            switch (name) {
                case "query":
                    endpointQueryParams.put("query", URLDecoder.decode(value, StandardCharsets.UTF_8));
                    break;
                case "tab":
                    switch (value) {
                        case "gifs" -> endpointQueryParams.put("type", "g");
                        case "images" -> endpointQueryParams.put("type", "i");
                        default -> logger.warn(String.format("Unsupported tab for tags url %s", value));
                    }
                    break;
                case "verified":
                    if (value != null && value.equals("1")) {
                        if (isTags().matches()) {
                            endpointQueryParams.put("verified", "y");
                        } else {
                            endpointQueryParams.put("verified", "yes");
                        }
                    }
                    break;
                case "order":
                    endpointQueryParams.put("order", value);
                    break;
                case "viewMode":
                    break;
                default:
                    logger.warn(String.format("Unexpected query param %s for search url. Skipping.", name));
            }
        }

        // Build the search or tags url and add missing query params if any
        if (isTags().matches()) {
            var subpaths = url.getPath().split("/");
            if (subpaths.length != 0) {
                endpointQueryParams.put("search_text", subpaths[subpaths.length - 1]);
            } else {
                throw new IOException("Failed to get search tags for url");
            }
            // Check if it is the main tags page with all gifs, images, creator etc
            if (!endpointQueryParams.containsKey("type")) {
                logger.warn("No tab selected, defaulting to gifs");
                endpointQueryParams.put("type", "g");
            }
            uri = new URIBuilder(TAGS_ENDPOINT);
        } else {
            var tabType = "gifs";
            var subpaths = url.getPath().split("/");
            if (subpaths.length != 0) {
                switch (subpaths[subpaths.length - 1]) {
                    case "gifs" -> tabType = "gifs";
                    case "images" -> tabType = "images";
                    case "search" -> logger.warn("No tab selected, defaulting to gifs");
                    default -> logger.warn(String.format("Unsupported search tab %s, defaulting to gifs",
                            subpaths[subpaths.length - 1]));
                }
            }
            uri = new URIBuilder(String.format(SEARCH_ENDPOINT, tabType));
        }

        endpointQueryParams.put("page", Integer.toString(currentPage));
        endpointQueryParams.put("count", Integer.toString(count));
        endpointQueryParams.forEach((k, v) -> uri.addParameter(k, v));

        return uri.build().toURL();
    }

    private URL getNicheURL() throws IOException, URISyntaxException {
        Matcher nicheMatcher = isNiche();
        if (!nicheMatcher.matches()) {
            throw new IOException("Failed to get niche identifier for url");
        }

        // Default to gifs; switch to images for tab=images or type=i.
        var listingPath = "gifs";
        var listingType = "g";
        URIBuilder uri;
        var browserURLQueryParams = new URIBuilder(url.toString()).getQueryParams();
        for (var qp : browserURLQueryParams) {
            var name = qp.getName();
            var value = qp.getValue();
            switch (name) {
                case "type" -> {
                    if ("i".equalsIgnoreCase(value)) {
                        listingPath = "images";
                        listingType = "i";
                    }
                }
                case "tab" -> {
                    if ("images".equalsIgnoreCase(value)) {
                        listingPath = "images";
                        listingType = "i";
                    }
                }
                default -> {
                }
            }
        }

        uri = new URIBuilder(String.format(NICHES_LISTING_ENDPOINT, nicheMatcher.group(1), listingPath));
        for (var qp : browserURLQueryParams) {
            var name = qp.getName();
            var value = qp.getValue();
            switch (name) {
                case "order" -> uri.addParameter(name, value);
                case "verified" -> {
                    if ("1".equals(value)) {
                        uri.addParameter("verified", "yes");
                    }
                }
                default -> {
                }
            }
        }
        uri.addParameter("type", listingType);
        uri.addParameter("count", Integer.toString(count));
        uri.addParameter("page", Integer.toString(currentPage));
        return uri.build().toURL();
    }
}
