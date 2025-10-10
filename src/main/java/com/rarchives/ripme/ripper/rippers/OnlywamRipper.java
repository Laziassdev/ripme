package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OnlywamRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(OnlywamRipper.class);
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "https?://[^\\\"']+\\.(?:jpe?g|png|gif|webp)(?:\\?[^\\\"']*)?",
            Pattern.CASE_INSENSITIVE);

    private Map<String, String> cookies = new HashMap<>();

    public OnlywamRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "onlywam";
    }

    @Override
    protected String getDomain() {
        return "onlywam.com";
    }

    @Override
    public void setup() throws IOException, URISyntaxException {
        super.setup();
        this.cookies = loadCookies();
        if (this.cookies.isEmpty()) {
            logger.warn("No OnlyWam cookies were loaded. Requests may fail without authentication.");
        } else {
            logger.info("Loaded {} OnlyWam cookie(s) for authenticated requests.", this.cookies.size());
        }
    }

    @Override
    protected Document getFirstPage() throws IOException, URISyntaxException {
        Http request = Http.url(this.url);
        applyAuthentication(request);
        return request.get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException, URISyntaxException {
        Element nextLink = doc.selectFirst("a[rel=next], link[rel=next]");
        if (nextLink == null) {
            return null;
        }
        String href = nextLink.hasAttr("abs:href") ? nextLink.attr("abs:href") : nextLink.attr("href");
        if (href == null || href.isBlank()) {
            return null;
        }
        String resolved = resolveToAbsolute(href);
        if (resolved.isBlank()) {
            return null;
        }
        Http request = Http.url(resolved);
        applyAuthentication(request);
        return request.get();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String path = url.getPath();
        if (path == null) {
            throw new MalformedURLException("Expected OnlyWam user path but got: " + url);
        }
        String[] parts = path.split("/");
        String username = null;
        String section = "gallery";
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (username == null) {
                username = part;
            } else {
                section = part;
                break;
            }
        }
        if (username == null) {
            throw new MalformedURLException("Expected OnlyWam URL format https://www.onlywam.com/<creator>/<section>");
        }
        username = username.replaceAll("[^a-zA-Z0-9_-]", "_");
        section = section.replaceAll("[^a-zA-Z0-9_-]", "_");
        return username + "_" + section;
    }

    @Override
    protected List<String> getURLsFromPage(Document page) throws UnsupportedEncodingException, URISyntaxException {
        Set<String> results = new LinkedHashSet<>();
        Element nextData = page.selectFirst("script#__NEXT_DATA__");
        if (nextData != null) {
            String jsonText = nextData.data();
            if (jsonText != null && !jsonText.isBlank()) {
                try {
                    JSONObject json = new JSONObject(jsonText);
                    collectImageUrlsFromJson(json, results);
                } catch (JSONException e) {
                    logger.debug("Failed to parse __NEXT_DATA__ JSON: {}", e.getMessage());
                }
            }
        }

        if (results.isEmpty()) {
            Elements images = page.select("img");
            for (Element image : images) {
                addImageAttributeIfPresent(image, "src", results);
                addImageAttributeIfPresent(image, "data-src", results);
                addImageAttributeIfPresent(image, "data-original", results);
                addImageAttributeIfPresent(image, "data-url", results);
                addFromSrcset(image, results);
            }
        }

        return new ArrayList<>(results);
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), cookies);
    }

    private void applyAuthentication(Http request) {
        if (request == null) {
            return;
        }
        if (!this.cookies.isEmpty()) {
            request.cookies(this.cookies);
        }
        String token = extractBearerToken();
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        request.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.header("Accept-Language", "en-US,en;q=0.9");
    }

    private String extractBearerToken() {
        if (cookies.containsKey("token")) {
            return cookies.get("token");
        }
        if (cookies.containsKey("bearer")) {
            return cookies.get("bearer");
        }
        if (cookies.containsKey("authorization")) {
            return cookies.get("authorization");
        }
        if (cookies.containsKey("authToken")) {
            return cookies.get("authToken");
        }
        if (cookies.containsKey("access_token")) {
            return cookies.get("access_token");
        }
        return null;
    }

    private void collectImageUrlsFromJson(Object value, Set<String> results) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            for (String key : obj.keySet()) {
                Object nested = obj.opt(key);
                collectImageUrlsFromJson(nested, results);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                Object nested = arr.opt(i);
                collectImageUrlsFromJson(nested, results);
            }
            return;
        }
        if (value instanceof String) {
            addIfImage((String) value, results);
        }
    }

    private void addImageAttributeIfPresent(Element image, String attribute, Set<String> results) {
        if (!image.hasAttr(attribute)) {
            return;
        }
        String attr = image.attr(attribute);
        if (attr == null || attr.isBlank()) {
            return;
        }
        String absolute = image.hasAttr("abs:" + attribute) ? image.attr("abs:" + attribute) : attr;
        addIfImage(absolute, results);
    }

    private void addFromSrcset(Element image, Set<String> results) {
        String srcset = image.attr("srcset");
        if (srcset == null || srcset.isBlank()) {
            return;
        }
        String[] entries = srcset.split(",");
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(" ");
            if (parts.length == 0) {
                continue;
            }
            addIfImage(parts[0], results);
        }
    }

    private void addIfImage(String candidate, Set<String> results) {
        if (candidate == null) {
            return;
        }
        String resolved = resolveToAbsolute(candidate.trim());
        if (resolved.isEmpty()) {
            return;
        }
        if (resolved.startsWith("data:")) {
            return;
        }
        String lower = resolved.toLowerCase(Locale.ROOT);
        if (IMAGE_PATTERN.matcher(resolved).find()
                || (lower.contains("onlywam") && (lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".gif") || lower.contains(".webp")
                || lower.contains("format=jpg") || lower.contains("format=jpeg")
                || lower.contains("format=png") || lower.contains("mime=image")))) {
            results.add(resolved);
        }
    }

    private String resolveToAbsolute(String candidate) {
        if (candidate == null) {
            return "";
        }
        String cleaned = candidate.replace("\\u0026", "&").replace("&amp;", "&");
        if (cleaned.startsWith("//")) {
            return "https:" + cleaned;
        }
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return cleaned;
        }
        try {
            return new URL(this.url, cleaned).toExternalForm();
        } catch (MalformedURLException e) {
            return cleaned;
        }
    }

    private Map<String, String> loadCookies() {
        Map<String, String> loaded = new HashMap<>();
        mergeCookiesFromConfig("cookies.onlywam.com", loaded);
        mergeCookiesFromConfig("cookies.www.onlywam.com", loaded);
        Map<String, String> firefoxCookies = loadCookiesFromFirefox();
        if (!firefoxCookies.isEmpty()) {
            loaded.putAll(firefoxCookies);
        }
        return loaded;
    }

    private void mergeCookiesFromConfig(String key, Map<String, String> target) {
        String configCookies = Utils.getConfigString(key, "");
        if (configCookies == null || configCookies.isBlank()) {
            return;
        }
        Map<String, String> parsed = RipUtils.getCookiesFromString(configCookies);
        target.putAll(parsed);
    }

    private Map<String, String> loadCookiesFromFirefox() {
        Map<String, String> firefoxCookies = new HashMap<>();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.warn("SQLite JDBC driver not found. Firefox cookie extraction for OnlyWam is unavailable.");
            return firefoxCookies;
        }

        Path cookiesFile;
        try {
            cookiesFile = findLatestFirefoxCookiesFile();
        } catch (IOException e) {
            logger.warn("Unable to locate Firefox cookies.sqlite: {}", e.getMessage());
            return firefoxCookies;
        }

        if (cookiesFile == null) {
            logger.warn("No Firefox cookies.sqlite containing OnlyWam cookies was found.");
            return firefoxCookies;
        }

        Path tempCopy = null;
        try {
            tempCopy = Files.createTempFile("onlywam_cookies", ".sqlite");
            Files.copy(cookiesFile, tempCopy, StandardCopyOption.REPLACE_EXISTING);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempCopy.toAbsolutePath());
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT name, value FROM moz_cookies WHERE host LIKE ?")) {
                statement.setString(1, "%onlywam.com");
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        String value = rs.getString("value");
                        if (name != null && value != null) {
                            firefoxCookies.put(name, value);
                        }
                    }
                }
            }
        } catch (SQLException | IOException e) {
            logger.warn("Failed to load Firefox cookies for OnlyWam: {}", e.getMessage());
        } finally {
            if (tempCopy != null) {
                try {
                    Files.deleteIfExists(tempCopy);
                } catch (IOException e) {
                    logger.debug("Unable to delete temporary cookies copy: {}", e.getMessage());
                }
            }
        }

        return firefoxCookies;
    }

    private Path findLatestFirefoxCookiesFile() throws IOException {
        String userHome = System.getProperty("user.home");
        List<Path> searchRoots = new ArrayList<>();
        searchRoots.add(Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "Profiles"));
        searchRoots.add(Paths.get(userHome, "Library", "Application Support", "Firefox", "Profiles"));
        searchRoots.add(Paths.get(userHome, ".mozilla", "firefox"));

        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (Path root : searchRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            List<Path> candidates;
            try (Stream<Path> stream = Files.walk(root, 2)) {
                candidates = stream
                        .filter(path -> path.getFileName().toString().equals("cookies.sqlite"))
                        .collect(Collectors.toList());
            }
            for (Path candidate : candidates) {
                long modified = Files.getLastModifiedTime(candidate).toMillis();
                if (modified > newestTime) {
                    newestTime = modified;
                    newest = candidate;
                }
            }
        }
        return newest;
    }
}
