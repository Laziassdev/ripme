package com.rarchives.ripme.ripper.rippers;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.ripper.AbstractRipper;

/**
 * <a href="https://coomer.st/api/schema">See this link for the API schema</a>.
 */
public class CoomerPartyRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(CoomerPartyRipper.class);
    private static final String coomerCookies = getCoomerCookiesFromFirefox();
    private static final String COOMER_USER_AGENT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    private String IMG_URL_BASE = "https://img.coomer.st";
    private String VID_URL_BASE = "https://c1.coomer.st";
    private static final Pattern IMG_PATTERN = Pattern.compile("^.*\\.(jpg|jpeg|png|gif|apng|webp|tif|tiff)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VID_PATTERN = Pattern.compile("^.*\\.(webm|mp4|m4v)$", Pattern.CASE_INSENSITIVE);

    // just so we can return a JSONObject from getFirstPage
    private static final String KEY_WRAPPER_JSON_ARRAY = "array";

    private static final String KEY_FILE = "file";
    private static final String KEY_PATH = "path";
    private static final String KEY_ATTACHMENTS = "attachments";

    // Posts Request Endpoint templates
    // Primary endpoint: /api/v1/{service}/user/{username}/posts
    private static final String POSTS_ENDPOINT = "https://%s/api/v1/%s/user/%s/posts?o=%d";

    // Pagination is strictly 50 posts per page, per API schema.
    private Integer pageCount = 0;
    private static final Integer postCount = 50;

    // "Service" of the page to be ripped: Onlyfans, Fansly, Candfans
    private final String service;

    // Username of the page to be ripped
    private final String user;

    // Current domain being used for API requests and media URLs
    private String domain;

    private final int maxDownloads = Utils.getConfigInteger("maxdownloads", -1);
    private final DownloadLimitTracker downloadLimitTracker = new DownloadLimitTracker(maxDownloads);
    private volatile boolean maxDownloadLimitReached = false;



    public CoomerPartyRipper(URL url) throws IOException {
        super(url);
        List<String> pathElements = Arrays.stream(url.getPath().split("/"))
                .filter(element -> !element.isBlank())
                .collect(Collectors.toList());

        service = pathElements.get(0);
        user = pathElements.get(2);

        if (service == null || user == null || service.isBlank() || user.isBlank()) {
            logger.warn("service=" + service + ", user=" + user);
            throw new MalformedURLException("Invalid coomer.party URL: " + url);
        }
        logger.debug("Parsed service=" + service + " and user=" + user + " from " + url);

        domain = url.getHost();
        setDomain(domain);
    }

    @Override
    protected String getDomain() {
        return "coomer.party";
    }

    @Override
    public String getHost() {
        return "coomer.party";
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost();
        return host.endsWith("coomer.party") || host.endsWith("coomer.su") || host.endsWith("coomer.st");
    }

    @Override
    public String getGID(URL url) {
        return Utils.filesystemSafe(String.format("%s_%s", service, user));
    }

    private void setDomain(String newDomain) {
        domain = newDomain;
        IMG_URL_BASE = "https://" + newDomain;
        VID_URL_BASE = "https://" + newDomain;
    }

    private JSONObject getJsonPostsForOffset(Integer offset) throws IOException {
        LinkedHashSet<String> domainsToTry = new LinkedHashSet<>();
        domainsToTry.add(domain);
        domainsToTry.add("coomer.st");
        domainsToTry.add("coomer.party");
        domainsToTry.add("coomer.su");

        IOException lastException = null;
        for (String dom : domainsToTry) {
            setDomain(dom);
            String apiUrl = String.format(POSTS_ENDPOINT, dom, service, user, offset);
            String jsonArrayString = null;
            try {
                Map<String, String> headers = new HashMap<>();
                // Mirror the lightweight header set used by the reference Python client that
                // successfully bypasses 403s: Googlebot UA plus a CSS accept header and a
                // fixed coomer.st referer.
                headers.put("Accept", "text/css");
                headers.put("Referer", "https://coomer.st/");
                if (coomerCookies != null) {
                    headers.put("Cookie", coomerCookies);
                }
                jsonArrayString = Http.getWith429Retry(new URL(apiUrl), 5, 5, COOMER_USER_AGENT, headers);

                logger.debug("Raw JSON from API for offset " + offset + ": " + jsonArrayString);

                JSONArray jsonArray = parsePostsArray(jsonArrayString);

                if (jsonArray.length() == 0) {
                    logger.warn("No posts found at offset " + offset + " for user: " + user);
                }

                JSONObject wrapperObject = new JSONObject();
                wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, jsonArray);
                return wrapperObject;
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 400) {
                    logger.info("Offset {} out of range for user {}, treating as no more posts", offset, user);
                    JSONObject wrapperObject = new JSONObject();
                    wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, new JSONArray());
                    return wrapperObject;
                }
                lastException = e;
                logger.warn("Failed to fetch posts from {}: {}", apiUrl, e.getMessage());
                // Try next domain (including CDN fallbacks) on 403/404/429 instead of bailing immediately
                continue;
            } catch (JSONException e) {
                lastException = new IOException("Invalid JSON response", e);
                logger.warn("Invalid JSON from {}: {}", apiUrl, e.getMessage());
                if (jsonArrayString != null) {
                    String snippet = jsonArrayString.length() > 200
                            ? jsonArrayString.substring(0, 200) + "..."
                            : jsonArrayString;
                    logger.debug("Response body (truncated to 200 chars): {}", snippet.replaceAll("\n", "\\n"));
                }
            } catch (IOException e) {
                lastException = e;
                logger.warn("Failed to fetch posts from {}: {}", apiUrl, e.getMessage());
            }
        }
        throw lastException;
    }

    protected JSONArray parsePostsArray(String rawJson) throws JSONException {
        return parsePostsArrayInternal(rawJson, false);
    }

    private JSONArray parsePostsArrayInternal(String rawJson, boolean alreadySanitized) throws JSONException {
        if (rawJson == null) {
            throw new JSONException("Empty response body");
        }

        String trimmed = rawJson.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim();
        }

        // Drop obvious trailing commas which can appear in some error-wrapped bodies
        trimmed = trimmed.replaceAll(",\\s*([}\\]])", "$1");

        JSONArray recovered = extractFirstParsableArray(trimmed);
        if (recovered != null) {
            return recovered;
        }

        int jsonStart = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{' || c == '[') {
                jsonStart = i;
                break;
            }
        }

        if (jsonStart > 0) {
            trimmed = trimmed.substring(jsonStart);
        }

        if (trimmed.isEmpty() || jsonStart == -1) {
            throw new JSONException("Non-JSON response body");
        }

        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }

        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("posts")) {
                return obj.getJSONArray("posts");
            }
            if (obj.has("items")) {
                return obj.getJSONArray("items");
            }
            if (obj.has("data")) {
                Object data = obj.get("data");
                if (data instanceof JSONArray) {
                    return (JSONArray) data;
                }
            }
        } catch (JSONException ex) {
            if (!alreadySanitized) {
                String textOnly = Jsoup.parse(trimmed).text();
                if (textOnly != null && !textOnly.isEmpty() && !textOnly.equals(trimmed)) {
                    return parsePostsArrayInternal(textOnly, true);
                }
            }
            throw ex;
        }

        throw new JSONException("No posts array in JSON object");
    }

    /**
     * Attempts to salvage an embedded JSON array from a response body that contains extra
     * HTML/garbage by locating the first balanced '[' ... ']' block.
     */
    private JSONArray extractFirstParsableArray(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '[') {
                continue;
            }

            int end = findMatchingBracket(body, i);
            if (end <= i) {
                continue;
            }

            String candidate = body.substring(i, end + 1);
            try {
                JSONArray parsed = new JSONArray(candidate);
                if (parsed.length() == 0) {
                    continue;
                }

                Object first = parsed.get(0);
                if (first instanceof JSONObject) {
                    return parsed;
                }
            } catch (JSONException parseError) {
                // Try the next candidate
            }
        }

        return null;
    }

    private int findMatchingBracket(String body, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                } else if (depth < 0) {
                    return -1;
                }
            }
        }

        return -1;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        JSONObject page = getJsonPostsForOffset(0);
        JSONArray posts = page.getJSONArray(KEY_WRAPPER_JSON_ARRAY);
        if (posts.length() == 0) {
            logger.warn("No posts returned for " + getURL());
            return null; // graceful exit
        }
        return page;
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (downloadLimitTracker.isLimitReached()) {
            maxDownloadLimitReached = true;
            return null;
        }

        pageCount++;
        int offset = pageCount * postCount;

        JSONObject nextPage = getJsonPostsForOffset(offset);
        JSONArray posts = nextPage.getJSONArray(KEY_WRAPPER_JSON_ARRAY);

        if (posts.length() == 0) {
            logger.info("No more posts found at offset " + offset + ", ending rip.");
            return null;
        }

        return nextPage;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        JSONArray posts = json.getJSONArray(KEY_WRAPPER_JSON_ARRAY);
        ArrayList<String> urls = new ArrayList<>();

        if (downloadLimitTracker.isLimitReached()) {
            maxDownloadLimitReached = true;
            return urls;
        }

        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.getJSONObject(i);
            logger.debug("Processing post: {}", post.toString());

            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                break;
            }

            int before = urls.size();

            try {
                pullFileUrl(post, urls);
            } catch (Exception e) {
                logger.warn("Error pulling file URL for post {}: {}", i, e.getMessage());
            }

            try {
                pullAttachmentUrls(post, urls);
            } catch (Exception e) {
                logger.warn("Error pulling attachments for post {}: {}", i, e.getMessage());
            }

            int added = urls.size() - before;
            if (added == 0) {
                logger.debug("Post {} yielded no URLs", i);
            }
        }

        if (urls.isEmpty()) {
            logger.warn("No downloadable URLs found in JSON block of {} posts", posts.length());
        }

        return urls;
    }


    @Override
    protected void downloadURL(URL url, int index) {
        try {
            Map<String,String> headers = new HashMap<>();
            headers.put("Accept", "text/css");
            headers.put("Referer", "https://coomer.st/");
            if (coomerCookies != null) {
                headers.put("Cookie", coomerCookies);
            }
            URL resolvedUrl = resolveWithFallback(url, headers);

            boolean countTowardsLimit = true;
            if (downloadLimitTracker.isEnabled()) {
                try {
                    Path existingPath = getFilePath(resolvedUrl, "", getPrefix(index), null, null);
                    if (Files.exists(existingPath)) {
                        if (!Utils.getConfigBoolean("file.overwrite", false)) {
                            logger.debug("Skipping existing file due to max download limit: {}", existingPath);
                            super.downloadExists(resolvedUrl, existingPath);
                            return;
                        }
                        countTowardsLimit = false;
                    }
                } catch (IOException e) {
                    logger.warn("Unable to determine existing file path for {}: {}", resolvedUrl, e.getMessage());
                }
            }

            if (!downloadLimitTracker.tryAcquire(resolvedUrl, countTowardsLimit)) {
                if (downloadLimitTracker.isLimitReached()) {
                    maxDownloadLimitReached = true;
                    if (downloadLimitTracker.shouldNotifyLimitReached()) {
                        String message = "Reached maxdownloads limit of " + maxDownloads + ". Stopping.";
                        logger.info(message);
                        sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                    }
                } else {
                    logger.debug("Max download limit of {} currently allocated, deferring {}", maxDownloads, resolvedUrl);
                }
                return;
            }

            boolean added = addURLToDownload(resolvedUrl, getPrefix(index));
            if (added) {
                if (Utils.getConfigBoolean("urls_only.save", false)) {
                    handleSuccessfulDownload(resolvedUrl);
                }
            } else {
                downloadLimitTracker.onFailure(resolvedUrl);
            }
        } catch (IOException e) {
            logger.error("Failed to resolve or download redirect URL {}: {}", url, e.getMessage());
        }
    }

    @Override
    public void downloadCompleted(URL url, java.nio.file.Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadExists(URL url, java.nio.file.Path file) {
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
                String message = "Reached maxdownloads limit of " + maxDownloads + ". Stopping.";
                logger.info(message);
                sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
            }
        }
    }

    private String buildMediaUrl(String base, String path, boolean isVideo) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.startsWith("/data/") && !path.startsWith("/thumbnail/") && !path.startsWith("/original/")) {
            path = (isVideo ? "/data" : "/thumbnail/data") + path;
        }
        return base + path;
    }

    private void pullFileUrl(JSONObject post, ArrayList<String> results) {
        if (post == null) {
            logger.warn("Attempted to parse null post object");
            return;
        }

        if (!post.has(KEY_FILE)) {
            logger.debug("Post missing 'file' object, skipping.");
            return;
        }

        try {
            JSONObject file = post.getJSONObject(KEY_FILE);
            List<String> paths = extractMediaPaths(file);
            addPathsToResults(paths, results);

        } catch (JSONException e) {
            logger.error("Error parsing 'file' object from post: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error in pullFileUrl: " + e.getMessage(), e);
        }
    }

    private void addPathsToResults(List<String> paths, ArrayList<String> results) {
        if (paths.isEmpty()) {
            logger.debug("No usable media paths supplied");
            return;
        }

        for (String path : paths) {
            if (path == null) {
                continue;
            }

            String trimmedPath = path.trim();
            if (trimmedPath.isEmpty()) {
                continue;
            }

            String url;
            if (trimmedPath.startsWith("http")) {
                url = trimmedPath;
                if (!isImage(url) && !isVideo(url)) {
                    logger.warn("Unsupported media extension in path: " + trimmedPath);
                    continue;
                }
            } else if (isImage(trimmedPath)) {
                url = buildMediaUrl(IMG_URL_BASE, trimmedPath, false);
            } else if (isVideo(trimmedPath)) {
                url = buildMediaUrl(VID_URL_BASE, trimmedPath, true);
            } else {
                logger.warn("Unsupported media extension in path: " + trimmedPath);
                continue;
            }

            results.add(url);
        }
    }

    private List<String> extractMediaPaths(JSONObject file) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();

        if (file.has(KEY_PATH) && !file.isNull(KEY_PATH)) {
            String path = file.optString(KEY_PATH, "");
            if (!path.isBlank()) {
                paths.add(path);
            }
        }

        collectPathsFromValue(file.opt("locations"), paths);

        if (file.has("alternates")) {
            Object alternates = file.opt("alternates");
            if (alternates instanceof JSONObject) {
                JSONObject altObj = (JSONObject) alternates;
                for (String key : altObj.keySet()) {
                    collectPathsFromValue(altObj.opt(key), paths);
                }
            }
        }

        return new ArrayList<>(paths);
    }

    private void collectPathsFromValue(Object value, LinkedHashSet<String> paths) {
        if (value == null) {
            return;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectPathsFromValue(array.opt(i), paths);
            }
            return;
        }

        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            if (obj.has("location")) {
                String location = obj.optString("location", "");
                if (!location.isBlank()) {
                    paths.add(location);
                }
            }
            collectPathsFromValue(obj.opt(KEY_PATH), paths);
            collectPathsFromValue(obj.opt("locations"), paths);
            return;
        }

        String path = value.toString();
        if (!path.isBlank()) {
            paths.add(path);
        }
    }

    protected URL resolveWithFallback(URL url, Map<String, String> headers) throws IOException {
        try {
            return Http.followRedirectsWithRetry(url, 5, 5, COOMER_USER_AGENT, headers);
        } catch (HttpStatusException e) {
            if (e.getStatusCode() != 403 && e.getStatusCode() != 404) {
                throw e;
            }
        }

        String host = url.getHost();
        List<String> candidates = buildSubdomainCandidates(host);
        for (String candidate : candidates) {
            URL altUrl = rebuildUrlWithHost(url, candidate);
            try {
                return Http.followRedirectsWithRetry(altUrl, 5, 5, COOMER_USER_AGENT, headers);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() != 403 && e.getStatusCode() != 404) {
                    throw e;
                }
            }
        }

        // Nothing worked; try original exception again
        return Http.followRedirectsWithRetry(url, 1, 1, COOMER_USER_AGENT, headers);
    }

    protected List<String> buildSubdomainCandidates(String base) {
        ArrayList<String> candidates = new ArrayList<>();
        if (base == null || base.isBlank()) {
            return candidates;
        }

        String lower = base.toLowerCase();
        List<String> seedBases = new ArrayList<>();
        if (lower.contains("coomer")) {
            seedBases.add("coomer.st");
        } else if (lower.contains("kemono")) {
            seedBases.add("kemono.cr");
            seedBases.add("kemono.su");
        }

        for (String seed : seedBases) {
            for (int i = 1; i <= 10; i++) {
                candidates.add(String.format("n%d.%s", i, seed));
            }
        }

        return candidates;
    }

    protected URL rebuildUrlWithHost(URL original, String newHost) throws MalformedURLException {
        String path = original.getPath();
        if (!path.startsWith("/data")) {
            if (path.startsWith("/")) {
                path = "/data" + path;
            } else {
                path = "/data/" + path;
            }
        }
        String query = original.getQuery();
        String rebuilt = String.format("%s://%s%s", original.getProtocol(), newHost, path);
        if (query != null && !query.isBlank()) {
            rebuilt += "?" + query;
        }
        return new URL(rebuilt);
    }

    private void pullAttachmentUrls(JSONObject post, ArrayList<String> results) {
        if (post == null || !post.has(KEY_ATTACHMENTS)) {
            return;
        }

        try {
            JSONArray attachments = post.getJSONArray(KEY_ATTACHMENTS);

            for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.optJSONObject(i);
                if (attachment != null) {
                    pullFileUrl(attachment, results);
                    LinkedHashSet<String> collected = new LinkedHashSet<>();
                    collectPathsFromValue(attachment, collected);
                    addPathsToResults(new ArrayList<>(collected), results);
                } else {
                    Object attachmentValue = attachments.get(i);
                    LinkedHashSet<String> collected = new LinkedHashSet<>();
                    collectPathsFromValue(attachmentValue, collected);
                    addPathsToResults(new ArrayList<>(collected), results);
                }
            }

        } catch (JSONException e) {
            logger.error("Error parsing 'attachments' array: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error in pullAttachmentUrls: " + e.getMessage(), e);
        }
    }

    private static String getCoomerCookiesFromFirefox() {
        try {
            String userHome = System.getProperty("user.home");
            String profilesIniPath = userHome + "/AppData/Roaming/Mozilla/Firefox/profiles.ini";
            java.nio.file.Path iniPath = java.nio.file.Paths.get(profilesIniPath);
            if (!java.nio.file.Files.exists(iniPath)) return null;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(iniPath);
            java.util.List<String> profilePaths = new java.util.ArrayList<>();
            for (String line : lines) {
                if (line.trim().startsWith("Path=")) {
                    profilePaths.add(line.trim().substring(5));
                }
            }
            logger.info("Found Firefox profiles: {}", profilePaths);
            for (String profilePath : profilePaths) {
                String sqlitePath = userHome + "/AppData/Roaming/Mozilla/Firefox/Profiles/" + profilePath + "/cookies.sqlite";
                sqlitePath = sqlitePath.replace("Profiles/Profiles/", "Profiles/");
                logger.info("Trying cookies.sqlite at: {}", sqlitePath);
                java.nio.file.Path tempCopy = null;
                try {
                    Class.forName("org.sqlite.JDBC");
                    tempCopy = java.nio.file.Files.createTempFile("cookies", ".sqlite");
                    tempCopy.toFile().deleteOnExit();
                    java.nio.file.Files.copy(java.nio.file.Paths.get(sqlitePath), tempCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempCopy.toString())) {
                        String sql = "SELECT name, value FROM moz_cookies WHERE host LIKE '%coomer%'";
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                            StringBuilder cookieStr = new StringBuilder();
                            boolean found = false;
                            while (rs.next()) {
                                String name = rs.getString("name");
                                String value = rs.getString("value");
                                if (cookieStr.length() > 0) cookieStr.append("; ");
                                cookieStr.append(name).append("=").append(value);
                                found = true;
                            }
                            if (found) {
                                logger.info("Found Coomer cookies in profile {}: {}", profilePath, cookieStr.length() > 16 ? cookieStr.substring(0,8)+"..."+cookieStr.substring(cookieStr.length()-8) : cookieStr.toString());
                                return cookieStr.toString();
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to read cookies from profile {}: {}", profilePath, e.getMessage());
                } finally {
                    if (tempCopy != null) {
                        try {
                            java.nio.file.Files.deleteIfExists(tempCopy);
                        } catch (IOException cleanupException) {
                            logger.debug("Unable to delete temporary Firefox cookie copy", cleanupException);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading Firefox profiles.ini: {}", e.getMessage());
        }
        return null;
    }

    private boolean isImage(String path) {
        Matcher matcher = IMG_PATTERN.matcher(path);
        return matcher.matches();
    }

    private boolean isVideo(String path) {
        Matcher matcher = VID_PATTERN.matcher(path);
        return matcher.matches();
    }
}
