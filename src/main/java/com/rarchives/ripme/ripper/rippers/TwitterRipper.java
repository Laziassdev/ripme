package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class TwitterRipper extends AbstractJSONRipper {
    private static final Logger logger = LogManager.getLogger(TwitterRipper.class);

    private static final String[] DOMAINS = {"twitter.com", "x.com"};
    private static final String DOMAIN = "twitter.com", HOST = "twitter";

    private static final int MAX_REQUESTS = Utils.getConfigInteger("twitter.max_requests", 10);
    private static final boolean RIP_RETWEETS = Utils.getConfigBoolean("twitter.rip_retweets", true);
    private static final boolean EXCLUDE_REPLIES = Utils.getConfigBoolean("twitter.exclude_replies", true);
    private static final int MAX_ITEMS_REQUEST = Utils.getConfigInteger("twitter.max_items_request", 200);
    private static final int WAIT_TIME = 2000;

    // Base 64 of consumer key : consumer secret
    private String authKey;
    private String accessToken;

    private enum ALBUM_TYPE {
        ACCOUNT, SEARCH
    }

    private ALBUM_TYPE albumType;
    private String searchText, accountName;
    private Long lastMaxID = 0L;
    private int currentRequest = 0;

    private boolean hasTweets = true;
    private String originalHost;

    public TwitterRipper(URL url) throws IOException {
        super(url);
        authKey = Utils.getConfigString("twitter.auth", null);
        if (authKey != null) {
            authKey = authKey.trim();
        }
        accessToken = Utils.getConfigString("twitter.access_token", null);
        if (accessToken != null) {
            accessToken = accessToken.trim();
        }
        if (accessToken == null || accessToken.isEmpty()) {
            accessToken = loadAccessTokenFromFirefox();
        }
        if ((authKey == null || authKey.isEmpty()) && (accessToken == null || accessToken.isEmpty())) {
            throw new IOException(
                    "Could not find twitter authentication credentials in configuration. Provide twitter.auth or twitter.access_token");
        }
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException {
        originalHost = url.getHost() != null ? url.getHost().toLowerCase() : "";

        String urlString = url.toExternalForm();
        if (originalHost.endsWith("x.com")) {
            try {
                URI uri = url.toURI();
                String sanitizedHost = originalHost.replaceFirst("(?i)x\\.com$", "twitter.com");
                URI sanitizedUri = new URI(uri.getScheme(), uri.getUserInfo(), sanitizedHost, uri.getPort(), uri.getPath(),
                        uri.getQuery(), uri.getFragment());
                url = sanitizedUri.toURL();
                urlString = url.toExternalForm();
            } catch (URISyntaxException e) {
                throw new MalformedURLException("Unable to normalize x.com URL: " + e.getMessage());
            }
        }

        // https://twitter.com/search?q=from%3Apurrbunny%20filter%3Aimages&src=typd
        Pattern p = Pattern.compile("^https?://(m\\.)?twitter\\.com/search\\?(.*)q=(?<search>[a-zA-Z0-9%\\-_]+).*$");
        Matcher m = p.matcher(urlString);
        if (m.matches()) {
            albumType = ALBUM_TYPE.SEARCH;
            searchText = m.group("search");

            if (searchText.startsWith("from%3A")) {
                // from filter not supported
                searchText = searchText.substring(7);
            }
            if (searchText.contains("x")) {
                // x character not supported
                searchText = searchText.replace("x", "");
            }
            return URI.create(urlString).toURL();
        }
        p = Pattern.compile("^https?://(m\\.)?(twitter|x)\\.com/([a-zA-Z0-9\\-_]+).*$");
        m = p.matcher(urlString);
        if (m.matches()) {
            albumType = ALBUM_TYPE.ACCOUNT;
            accountName = m.group(3);
            return URI.create(urlString).toURL();
        }
        throw new MalformedURLException("Expected username or search string in url: " + url);
    }

    private void getAccessToken() throws IOException {
        if (accessToken != null && !accessToken.trim().isEmpty()) {
            logger.debug("Using cached twitter access token");
            return;
        }

        if (authKey == null || authKey.isEmpty()) {
            throw new IOException("Could not find twitter authentication key in configuration");
        }

        try {
            Document doc = Http.url("https://api.twitter.com/oauth2/token").ignoreContentType()
                    .header("Authorization", "Basic " + authKey)
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .header("User-agent", "ripe and zipe").data("grant_type", "client_credentials").post();
            String body = doc.body().html().replaceAll("&quot;", "\"");
            try {
                JSONObject json = new JSONObject(body);
                accessToken = json.getString("access_token").trim();
            } catch (JSONException e) {
                // Fall through
                throw new IOException("Failure while parsing JSON: " + body, e);
            }
        } catch (HttpStatusException e) {
            throw new IOException(
                    "Failed to load https://api.twitter.com/oauth2/token (HTTP " + e.getStatusCode()
                            + "). Provide twitter.access_token in rip.properties to bypass this call.",
                    e);
        }
    }

    private void checkRateLimits(String resource, String api) throws IOException {
        Document doc = Http.url("https://api.twitter.com/1.1/application/rate_limit_status.json?resources=" + resource)
                .ignoreContentType().header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("User-agent", "ripe and zipe").get();
        String body = doc.body().html().replaceAll("&quot;", "\"");
        try {
            JSONObject json = new JSONObject(body);
            JSONObject stats = json.getJSONObject("resources").getJSONObject(resource).getJSONObject(api);
            int remaining = stats.getInt("remaining");
            logger.info("    Twitter " + resource + " calls remaining: " + remaining);
            if (remaining < 20) {
                logger.error("Twitter API calls exhausted: " + stats.toString());
                throw new IOException("Less than 20 API calls remaining; not enough to rip.");
            }
        } catch (JSONException e) {
            logger.error("JSONException: ", e);
            throw new IOException("Error while parsing JSON: " + body, e);
        }
    }

    private String getApiURL(Long maxID) {
        StringBuilder req = new StringBuilder();
        switch (albumType) {
        case ACCOUNT:
            req.append("https://api.twitter.com/1.1/statuses/user_timeline.json")
                    .append("?screen_name=" + this.accountName).append("&include_entities=true")
                    .append("&exclude_replies=" + EXCLUDE_REPLIES).append("&trim_user=true").append("&count=" + MAX_ITEMS_REQUEST)
                    .append("&tweet_mode=extended");
            break;
        case SEARCH:// Only get tweets from last week
            req.append("https://api.twitter.com/1.1/search/tweets.json").append("?q=" + this.searchText)
                    .append("&include_entities=true").append("&result_type=recent").append("&count=100")
                    .append("&tweet_mode=extended");
            break;
        }
        if (maxID > 0) {
            req.append("&max_id=" + Long.toString(maxID));
        }
        return req.toString();
    }

    private JSONObject getTweets() throws IOException {
        currentRequest++;
        String url = getApiURL(lastMaxID - 1);
        logger.info("    Retrieving " + url);
        Document doc = Http.url(url).ignoreContentType().header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("User-agent", "ripe and zipe").get();
        String body = doc.body().html().replaceAll("&quot;", "\"");
        Object jsonObj = new JSONTokener(body).nextValue();
        JSONArray statuses;
        if (jsonObj instanceof JSONObject) {
            JSONObject json = (JSONObject) jsonObj;
            if (json.has("errors")) {
                String msg = json.getJSONObject("errors").getString("message");
                throw new IOException("Twitter responded with errors: " + msg);
            }
            statuses = json.getJSONArray("statuses");
        } else {
            statuses = (JSONArray) jsonObj;
        }

        JSONObject r = new JSONObject();
        r.put("tweets", statuses);
        return r;
    }

    public String getPrefix(int index) {
        return Utils.getConfigBoolean("download.save_order", true) ? String.format("%03d_", index) : "";
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        getAccessToken();

        switch (albumType) {
        case ACCOUNT:
            checkRateLimits("statuses", "/statuses/user_timeline");
            break;
        case SEARCH:
            checkRateLimits("search", "/search/tweets");
            break;
        }

        return getTweets();
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException {
        try {
            Thread.sleep(WAIT_TIME);
        } catch (InterruptedException e) {
            logger.error("[!] Interrupted while waiting to load more results", e);
        }
        return currentRequest <= MAX_REQUESTS ? getTweets() : null;
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    protected String getDomain() {
        if (originalHost != null && originalHost.endsWith("x.com")) {
            return "x.com";
        }
        return DOMAIN;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        switch (albumType) {
        case ACCOUNT:
            return "account_" + accountName;
        case SEARCH:
            StringBuilder gid = new StringBuilder();
            for (int i = 0; i < searchText.length(); i++) {
                char c = searchText.charAt(i);
                // Ignore URL-encoded chars
                if (c == '%') {
                    gid.append('_');
                    i += 2;
                    // Ignore non-alphanumeric chars
                } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                    gid.append(c);
                }
            }
            return "search_" + gid.toString();
        }
        throw new MalformedURLException("Could not decide type of URL (search/account): " + url);
    }

    @Override
    public boolean hasASAPRipping() {
        return hasTweets;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        List<JSONObject> tweets = new ArrayList<>();
        JSONArray statuses = json.getJSONArray("tweets");

        for (int i = 0; i < statuses.length(); i++) {
            tweets.add((JSONObject) statuses.get(i));
        }

        if (tweets.isEmpty()) {
            logger.info("   No more tweets found.");
            return urls;
        }

        logger.debug("Twitter response #" + (currentRequest) + " Tweets:\n" + tweets);
        if (tweets.size() == 1 && lastMaxID.equals(tweets.get(0).getString("id_str"))) {
            logger.info("   No more tweet found.");
            return urls;
        }

        for (JSONObject tweet : tweets) {
            lastMaxID = tweet.getLong("id");

            if (!tweet.has("extended_entities")) {
                logger.error("XXX Tweet doesn't have entities");
                continue;
            }

            if (!RIP_RETWEETS && tweet.has("retweeted_status")) {
                logger.info("Skipping a retweet as twitter.rip_retweet is set to false.");
                continue;
            }

            JSONObject entities = tweet.getJSONObject("extended_entities");

            if (entities.has("media")) {
                JSONArray medias = entities.getJSONArray("media");
                String url;
                JSONObject media;

                for (int i = 0; i < medias.length(); i++) {
                    media = (JSONObject) medias.get(i);
                    url = media.getString("media_url");
                    if (media.getString("type").equals("video") || media.getString("type").equals("animated_gif")) {
                        JSONArray variants = media.getJSONObject("video_info").getJSONArray("variants");
                        int largestBitrate = 0;
                        String urlToDownload = null;
                        // Loop over all the video options and find the biggest video
                        for (int j = 0; j < variants.length(); j++) {
                            JSONObject variant = (JSONObject) variants.get(j);
                            logger.info(variant);
                            // If the video doesn't have a bitrate it's a m3u8 file we can't download
                            if (variant.has("bitrate")) {
                                if (variant.getInt("bitrate") > largestBitrate) {
                                    largestBitrate = variant.getInt("bitrate");
                                    urlToDownload = variant.getString("url");
                                } else if (media.getString("type").equals("animated_gif")) {
                                    // If the type if animated_gif the bitrate doesn't matter
                                    urlToDownload = variant.getString("url");
                                }
                            }
                        }
                        if (urlToDownload != null) {
                            urls.add(urlToDownload);
                        } else {
                            logger.error("URLToDownload was null");
                        }
                    } else if (media.getString("type").equals("photo")) {
                        if (url.contains(".twimg.com/")) {
                            url += ":orig";
                            urls.add(url);
                        } else {
                            logger.debug("Unexpected media_url: " + url);
                        }
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
    public boolean canRip(URL url) {
        String host = url.getHost().toLowerCase();
        return host.endsWith("twitter.com") || host.endsWith("x.com");
    }

    private static String loadAccessTokenFromFirefox() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.debug("SQLite JDBC driver not available; cannot read Firefox data for twitter token", e);
            return null;
        }

        Set<Path> profilePaths = discoverFirefoxProfiles();
        if (profilePaths.isEmpty()) {
            logger.debug("No Firefox profiles detected while attempting to load twitter token");
            return null;
        }

        for (Path profilePath : profilePaths) {
            String token = readAccessTokenFromFirefoxProfile(profilePath);
            if (token != null && !token.isBlank()) {
                logger.info("Loaded twitter bearer token from Firefox profile {}", profilePath.getFileName());
                return token.trim();
            }
        }

        logger.debug("Unable to locate twitter bearer token in any Firefox profile");
        return null;
    }

    private static Set<Path> discoverFirefoxProfiles() {
        Set<Path> profilePaths = new HashSet<>();
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return profilePaths;
        }

        List<Path> iniCandidates = new ArrayList<>();
        iniCandidates.add(Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "profiles.ini"));
        iniCandidates.add(Paths.get(userHome, "Library", "Application Support", "Firefox", "profiles.ini"));
        iniCandidates.add(Paths.get(userHome, ".mozilla", "firefox", "profiles.ini"));

        for (Path iniPath : iniCandidates) {
            if (!Files.exists(iniPath)) {
                continue;
            }
            try {
                profilePaths.addAll(readProfilesFromIni(iniPath));
            } catch (IOException e) {
                logger.debug("Failed to parse Firefox profiles.ini at {}", iniPath, e);
            }
        }

        return profilePaths;
    }

    private static Set<Path> readProfilesFromIni(Path iniPath) throws IOException {
        Set<Path> profiles = new HashSet<>();
        List<String> lines = Files.readAllLines(iniPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return profiles;
        }

        Path baseDir = iniPath.getParent();
        boolean isRelative = true;

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }

            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("[")) {
                isRelative = true;
                continue;
            }

            if (line.startsWith("IsRelative=")) {
                isRelative = !"0".equals(line.substring("IsRelative=".length()).trim());
                continue;
            }

            if (line.startsWith("Path=")) {
                String profileEntry = line.substring("Path=".length()).trim();
                if (profileEntry.isEmpty()) {
                    continue;
                }

                Path profilePath = isRelative && baseDir != null ? baseDir.resolve(profileEntry) : Paths.get(profileEntry);
                Path normalized = profilePath.normalize();
                if (Files.exists(normalized)) {
                    profiles.add(normalized);
                } else {
                    logger.debug("Firefox profile path {} from {} does not exist", normalized, iniPath);
                }
            }
        }

        return profiles;
    }

    private static String readAccessTokenFromFirefoxProfile(Path profilePath) {
        if (profilePath == null) {
            return null;
        }

        String token = readTokenFromWebappsStore(profilePath);
        if (token != null && !token.isBlank()) {
            return token;
        }

        token = readTokenFromCookies(profilePath);
        if (token != null && !token.isBlank()) {
            return token;
        }

        return null;
    }

    private static String readTokenFromWebappsStore(Path profilePath) {
        Path sqlitePath = profilePath.resolve("webappsstore.sqlite");
        if (!Files.exists(sqlitePath)) {
            return null;
        }

        Path tempCopy = null;
        try {
            tempCopy = Files.createTempFile("ripme-twitter-webapps", ".sqlite");
            Files.copy(sqlitePath, tempCopy, StandardCopyOption.REPLACE_EXISTING);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempCopy);
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            "SELECT value FROM webappsstore2 WHERE (originKey LIKE '%twitter.com%' OR originKey LIKE '%x.com%') AND value LIKE 'Bearer %'")) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    if (value != null && !value.isBlank()) {
                        String token = normalizeAccessToken(value);
                        if (token != null && !token.isBlank()) {
                            return token;
                        }
                    }
                }
            }
        } catch (SQLException | IOException e) {
            logger.debug("Unable to read twitter token from Firefox webappsstore in {}", profilePath, e);
        } finally {
            if (tempCopy != null) {
                try {
                    Files.deleteIfExists(tempCopy);
                } catch (IOException e) {
                    logger.debug("Failed to delete temporary Firefox storage copy", e);
                }
            }
        }

        return null;
    }

    private static String readTokenFromCookies(Path profilePath) {
        Path sqlitePath = profilePath.resolve("cookies.sqlite");
        if (!Files.exists(sqlitePath)) {
            return null;
        }

        Path tempCopy = null;
        try {
            tempCopy = Files.createTempFile("ripme-twitter-cookies", ".sqlite");
            Files.copy(sqlitePath, tempCopy, StandardCopyOption.REPLACE_EXISTING);
            String sql = "SELECT name, value FROM moz_cookies WHERE host LIKE '%twitter.com%' OR host LIKE '%x.com%'";
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempCopy);
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    if (value == null || value.isBlank()) {
                        continue;
                    }

                    String token = extractAccessTokenFromCookieValue(value);
                    if (token != null && !token.isBlank()) {
                        return token;
                    }
                }
            }
        } catch (SQLException | IOException e) {
            logger.debug("Unable to read twitter token from Firefox cookies in {}", profilePath, e);
        } finally {
            if (tempCopy != null) {
                try {
                    Files.deleteIfExists(tempCopy);
                } catch (IOException e) {
                    logger.debug("Failed to delete temporary Firefox cookie copy", e);
                }
            }
        }

        return null;
    }

    private static String extractAccessTokenFromCookieValue(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String normalized = normalizeAccessToken(trimmed);
        if (normalized != null && !normalized.isBlank() && !normalized.equals(trimmed)) {
            return normalized;
        }

        int idx = trimmed.indexOf("access_token");
        if (idx >= 0) {
            Pattern pattern = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }

        return null;
    }

    private static String normalizeAccessToken(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring("Bearer ".length()).trim();
        }

        return trimmed;
    }

}
