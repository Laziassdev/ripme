package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AlbumRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class TumblrRipper extends AlbumRipper {

    private static final Logger logger = LogManager.getLogger(TumblrRipper.class);
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_SECONDS = 5;
    private final int maxDownloads = Utils.getConfigInteger(
            "maxdownloads",
            Utils.getConfigInteger("max.downloads", 0)); // 0 or below = no limit
    private final DownloadLimitTracker downloadLimitTracker = new DownloadLimitTracker(maxDownloads);
    private final AtomicInteger nextIndex = new AtomicInteger(1);
    private volatile boolean maxDownloadLimitReached = false;

    private static final String DOMAIN = "tumblr.com",
            HOST = "tumblr",
            IMAGE_PATTERN = "([^\\s]+(\\.(?i)(?:jpg|png|gif|bmp))$)";

    private enum ALBUM_TYPE {
        SUBDOMAIN,
        TAG,
        POST,
        LIKED
    }

    private ALBUM_TYPE albumType;
    private String subdomain, tagName, postNumber;

    private static final String TUMBLR_AUTH_CONFIG_KEY = "tumblr.auth";
    private static final Pattern HIDDEN_MEDIA_PATTERN = Pattern.compile(
            "https?://[^\\\"\\s>]+\\.(?:jpg|jpeg|png|gif|bmp|webp|mp4|m4v|webm)(?:\\?[^\\\"\\s]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DASHBOARD_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")
            .withZone(ZoneId.systemDefault());

    private static final String LEGACY_DEFAULT_API_KEY = "JFNLu3CbINQjRdUvZibXW9VpSEVYYtiPJ86o8YmvgLZIoKyuNX";
    private static final List<String> DEFAULT_API_KEYS = Collections
            .unmodifiableList(Arrays.asList(
                    LEGACY_DEFAULT_API_KEY,
                    "FQrwZMCxVnzonv90rgNUJcAk4FpnoS0mYuSuGYqIpM2cFgp9L4",
                    "qpdkY6nMknksfvYAhf2xIHp0iNRLkMlcWShxqzXyFJRxIsZ1Zz"));
    private static boolean useDefaultApiKey = false; // fall-back for bad user-specified key
    private static String API_KEY = null;
    private volatile String lastRequestedApiKey;
    private static final Map<String, String> TUMBLR_FIREFOX_COOKIES = new LinkedHashMap<>();

    // Loads Tumblr cookies from Firefox profiles across supported platforms and returns a header-ready string
    private static String getTumblrCookiesFromFirefox() {
        if (!FirefoxCookieUtils.isSQLiteDriverAvailable()) {
            logger.debug("SQLite JDBC driver not available; cannot read Tumblr cookies from Firefox");
            return null;
        }

        Map<String, String> cookies = new LinkedHashMap<>();
        synchronized (TUMBLR_FIREFOX_COOKIES) {
            TUMBLR_FIREFOX_COOKIES.clear();
        }
        for (Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
            Map<String, String> profileCookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath,
                    Arrays.asList("%tumblr.com", "%tumblr.co%"));
            if (!profileCookies.isEmpty()) {
                cookies.putAll(profileCookies);
                logger.info("Loaded {} Tumblr cookies from Firefox profile {}", profileCookies.size(),
                        profilePath.getFileName());
                synchronized (TUMBLR_FIREFOX_COOKIES) {
                    TUMBLR_FIREFOX_COOKIES.putAll(profileCookies);
                }
                break;
            }
        }

        String header = FirefoxCookieUtils.toCookieHeader(cookies);
        if (header != null && !header.isEmpty()) {
            logger.info("Prepared Tumblr cookie header from Firefox ({} bytes)", header.length());
        } else {
            logger.debug("No Tumblr cookies discovered in Firefox profiles");
        }

        return header;
    }

    private static String getTumblrCookieValue(String name) {
        synchronized (TUMBLR_FIREFOX_COOKIES) {
            return TUMBLR_FIREFOX_COOKIES.get(name);
        }
    }

    /**
     * Gets the API key.
     * Chooses between default/included keys & user specified ones (from the config file).
     * @return Tumblr API key
     */
    public static String getApiKey() {
        // Use a different api ket for unit tests so we don't get 429 errors
        if (isThisATest()) {
            return "UHpRFx16HFIRgQjtjJKgfVIcwIeb71BYwOQXTMtiCvdSEPjV7N";
        }
        if (API_KEY == null) {
            API_KEY = pickRandomApiKey();
        }

        String configuredApiKey = Utils.getConfigString(TUMBLR_AUTH_CONFIG_KEY, LEGACY_DEFAULT_API_KEY);
        if (useDefaultApiKey || DEFAULT_API_KEYS.contains(configuredApiKey)) {
            logger.info("Using api key: " + API_KEY);
            return API_KEY;
        } else {
            logger.info("Using user tumblr.auth api key: " + configuredApiKey);
            return configuredApiKey;
        }
    }

    private static String pickRandomApiKey() {
        int genNum = new Random().nextInt(DEFAULT_API_KEYS.size());
        logger.info(genNum);
        final String API_KEY = DEFAULT_API_KEYS.get(genNum); // Select random API key from APIKEYS
        return API_KEY;
    }

    public TumblrRipper(URL url) throws IOException {
        super(url);
        if (getApiKey() == null) {
            throw new IOException("Could not find tumblr authentication key in configuration");
        }
    }

    @Override
    public boolean canRip(URL url) {
        return url.getHost().endsWith(DOMAIN);
    }

    /**
     * Sanitizes URL.
     * @param url URL to be sanitized.
     * @return Sanitized URL
     * @throws MalformedURLException
     */
    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        String u = url.toExternalForm();
        if (url.getHost().equals("www.tumblr.com")) {
            Matcher m = Pattern.compile("https?://www\\.tumblr\\.com/([a-zA-Z0-9_-]+)(/.*)?").matcher(u);
            if (m.matches()) {
                return new URL("https://" + m.group(1) + ".tumblr.com");
            }
        }
        if (StringUtils.countMatches(u, ".") > 2) {
            url = new URI(u.replace(".tumblr.com", "")).toURL();
        }
        return url;
    }

    private boolean isTumblrURL(URL url) {
        String checkURL = "http://api.tumblr.com/v2/blog/";
        checkURL += url.getHost();
        checkURL += "/info?api_key=" + getApiKey();
        try {
            JSONObject json = Http.url(checkURL)
                    .getJSON();
            int status = json.getJSONObject("meta").getInt("status");
            return status == 200;
        } catch (IOException e) {
            logger.error("Error while checking possible tumblr domain: " + url.getHost(), e);
        }
        return false;
    }

    @Override
    public void rip() throws IOException {
        String[] mediaTypes;

        // If true the rip loop won't be run
        boolean shouldStopRipping = false;

        String tumblrCookies = getTumblrCookiesFromFirefox();

        if (albumType == ALBUM_TYPE.POST) {
            mediaTypes = new String[] { "post" };
        } else {
            mediaTypes = new String[] { "photo", "video", "audio" };
        }

        int offset;
        for (String mediaType : mediaTypes) {
            if (isStopped() || maxDownloadLimitReached) {
                break;
            }

            if (shouldStopRipping || maxDownloadLimitReached) {
                break;
            }

            offset = 0;

            while (true) {
                if (isStopped() || maxDownloadLimitReached) {
                    break;
                }

                if (shouldStopRipping || maxDownloadLimitReached) {
                    break;
                }

                String apiURL = getTumblrApiURL(mediaType, offset);
                logger.info("Retrieving " + apiURL);
                sendUpdate(STATUS.LOADING_RESOURCE, apiURL);

                JSONObject json = null;

                try {
                    Map<String,String> headers = null;
                    if (tumblrCookies != null && !tumblrCookies.isEmpty()) {
                        headers = new HashMap<>();
                        headers.put("Cookie", tumblrCookies);
                    }
                    String response = Http.getWith429Retry(new URL(apiURL), MAX_RETRIES, RETRY_DELAY_SECONDS, AbstractRipper.USER_AGENT, headers);
                    json = new JSONObject(response);
                } catch (IOException e) {
                    HttpStatusException statusException = e instanceof HttpStatusException
                            ? (HttpStatusException) e
                            : null;
                    int statusCode = statusException != null ? statusException.getStatusCode() : -1;
                    String responseBody = fetchErrorBody(apiURL, tumblrCookies);

                    if (statusCode == 404 || statusCode == 410) {
                        logger.warn("Tumblr API returned {} for {}. Attempting dashboard fallback if possible.", statusCode,
                                apiURL);
                        boolean handledHidden = tumblrCookies != null && !tumblrCookies.isEmpty()
                                && tryRipHiddenDashboard(tumblrCookies);
                        if (!handledHidden) {
                            logger.error("Tumblr blog does not exist or is private. Exiting.");
                            sendUpdate(STATUS.DOWNLOAD_ERRORED,
                                    "Tumblr blog not found (" + statusCode + "): " + apiURL);
                        }
                        shouldStopRipping = true;
                        break;
                    }

                    if (responseBody != null && responseBody.contains("\"code\":4012")) {
                        logger.warn("Tumblr API reported dashboard-only access (4012). Attempting dashboard scraping fallback.");
                        boolean handledHidden = tumblrCookies != null && !tumblrCookies.isEmpty()
                                && tryRipHiddenDashboard(tumblrCookies);
                        if (!handledHidden) {
                            sendUpdate(STATUS.DOWNLOAD_ERRORED,
                                    "Tumblr blog is dashboard-only and could not be fetched via fallback.");
                        }
                        shouldStopRipping = true;
                        break;
                    }

                    if (statusCode == 401) {
                        if (!isDefaultApiKeyInUse()) {
                            String message = "401 Unauthorized from Tumblr API. Retrying with bundled key.";
                            if (responseBody != null && !responseBody.isEmpty()) {
                                logger.warn("{} Raw body: {}", message, responseBody);
                            } else {
                                logger.warn(message, e);
                            }
                            sendUpdate(STATUS.DOWNLOAD_WARN, message);
                            useDefaultApiKey = true;
                            continue;
                        }

                        if (responseBody != null && !responseBody.isEmpty()) {
                            logger.error("Failed to fetch Tumblr API JSON. Raw body: " + responseBody);
                        } else {
                            logger.error("Failed to fetch Tumblr API JSON from " + apiURL, e);
                        }

                        boolean handledHidden = tumblrCookies != null && !tumblrCookies.isEmpty()
                                && tryRipHiddenDashboard(tumblrCookies);
                        if (!handledHidden) {
                            sendUpdate(STATUS.DOWNLOAD_ERRORED,
                                    "Unauthorized to fetch Tumblr API data. Provide a valid API key or Tumblr cookies.");
                        }
                        shouldStopRipping = true;
                        break;
                    }

                    if (responseBody != null && (responseBody.contains("\"status\":404")
                            || responseBody.contains("\"msg\":\"Not Found\""))) {
                        logger.error("Tumblr blog does not exist or is private. Exiting.");
                        sendUpdate(STATUS.DOWNLOAD_ERRORED, "Tumblr blog not found (404): " + apiURL);
                        shouldStopRipping = true;
                        break;
                    }

                    if (responseBody != null && !responseBody.isEmpty()) {
                        logger.error("Failed to fetch Tumblr API JSON. Raw body: " + responseBody);
                    } else {
                        logger.error("Failed to fetch Tumblr API JSON from " + apiURL, e);
                    }
                    sendUpdate(STATUS.DOWNLOAD_ERRORED, "Failed to fetch JSON from Tumblr API: " + apiURL);
                    shouldStopRipping = true;
                    break;
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.error("[!] Interrupted while waiting to load next album:", e);
                    break;
                }

                if (json == null) {
                    logger.error("Failed to fetch or parse Tumblr API JSON from " + apiURL);
                    sendUpdate(STATUS.DOWNLOAD_ERRORED, "Failed to fetch JSON from Tumblr API: " + apiURL);
                    continue;  // Stop the loop, or `continue;` if you want to skip just this page
                }

                if (!handleJSON(json)) {
                    // Returns false if an error occurs and we should stop.
                    break;
                }

                if (downloadLimitTracker.isLimitReached()) {
                    maxDownloadLimitReached = true;
                }

                offset += 20;
            }

            if (isStopped()) {
                break;
            }
        }

        waitForThreads();
    }

    private boolean handleJSON(JSONObject json) {
        JSONArray posts, photos;
        JSONObject post, photo;
        Pattern p;
        Matcher m;
        p = Pattern.compile(IMAGE_PATTERN);

        String fileLocation;
        URL fileURL;

        Pattern qualP = Pattern.compile("_[0-9]+\\.(jpg|png|gif|bmp)$");
        Matcher qualM;

        if (albumType == ALBUM_TYPE.LIKED) {
            posts = json.getJSONObject("response").getJSONArray("liked_posts");
        } else {
            posts = json.getJSONObject("response").getJSONArray("posts");
        }
        if (posts.length() == 0) {
            logger.info("   Zero posts returned.");
            return false;
        }

        for (int i = 0; i < posts.length(); i++) {
            post = posts.getJSONObject(i);
            String date = post.getString("date");
            if (post.has("photos")) {
                photos = post.getJSONArray("photos");
                for (int j = 0; j < photos.length(); j++) {
                    photo = photos.getJSONObject(j);
                    try {
                        fileLocation = photo.getJSONObject("original_size").getString("url").replaceAll("http:", "https:");
                        qualM = qualP.matcher(fileLocation);
                        fileLocation = qualM.replaceFirst("_1280.$1");
                        fileURL = new URI(fileLocation).toURL();

                        m = p.matcher(fileURL.toString());
                        if (m.matches()) {
                            downloadURL(fileURL, date);
                        } else {
                            URL redirectedURL = Http.url(fileURL).ignoreContentType().response().url();
                            downloadURL(redirectedURL, date);
                        }
                    } catch (Exception e) {
                        logger.error("[!] Error while parsing photo in " + photo, e);
                        JSONArray altSizes = photo.getJSONArray("alt_sizes");
                        for (int k = 0; k < altSizes.length(); k++) {
                            JSONObject alt = altSizes.getJSONObject(k);
                            String altUrl = alt.getString("url").replaceAll("http:", "https:");
                            // Optional: Only pick _1280 or similar if present
                            if (altUrl.contains("_1280")) {
                            try {
                                fileURL = new URI(altUrl).toURL();
                                downloadURL(fileURL, date);
                                break;
                            } catch (URISyntaxException | MalformedURLException ex) {
                                logger.warn("Failed to convert alt image URL to valid URI", ex);
                            }
                            }
                        }
                    }
                }
            } else if (post.has("video_url")) {
                try {
                    fileURL = new URI(post.getString("video_url").replaceAll("http:", "https:")).toURL();
                    downloadURL(fileURL, date);
                } catch (Exception e) {
                    logger.error("[!] Error while parsing video in " + post, e);
                    return true;
                }
            } else if (post.has("audio_url")) {
                try {
                    fileURL = new URI(post.getString("audio_url").replaceAll("http:", "https:")).toURL();
                    downloadURL(fileURL, date);
                } catch (Exception e) {
                    logger.error("[!] Error while parsing audio in " + post, e);
                    return true;
                }
                if (post.has("album_art")) {
                    try {
                        fileURL = new URI(post.getString("album_art").replaceAll("http:", "https:")).toURL();
                        downloadURL(fileURL, date);
                    } catch (Exception e) {
                        logger.error("[!] Error while parsing album art in " + post, e);
                        return true;
                    }
                }
            } else if (post.has("body")) {
                Document d = Jsoup.parse(post.getString("body"));
                if (!d.select("img").attr("src").isEmpty()) {
                    try {
                        String imgSrc = d.select("img").attr("src");
                        // Set maximum quality, tumblr doesn't go any further
                        // If the image is any smaller, it will still get the largest available size
                        qualM = qualP.matcher(imgSrc);
                        imgSrc = qualM.replaceFirst("_1280.$1");
                        downloadURL(new URI(imgSrc).toURL(), date);
                    } catch (MalformedURLException | URISyntaxException e) {
                        logger.error("[!] Error while getting embedded image at " + post, e);
                        return true;
                    }
                }
            }
            else if (post.has("player")) {
                JSONArray players = post.getJSONArray("player");
                for (int j = players.length() - 1; j >= 0; j--) {
                    JSONObject player = players.getJSONObject(j);
                    if (player.has("embed_code")) {
                        Document embed = Jsoup.parse(player.getString("embed_code"));
                        String src = embed.select("source[src]").attr("src");
                        if (!src.isEmpty()) {
                            try {
                                fileURL = new URI(src.replaceAll("http:", "https:")).toURL();
                                downloadURL(fileURL, date);
                            } catch (Exception e) {
                                logger.warn("Could not parse video from embed_code", e);
                            }
                        }
                    }
                }
            }
            if (post.has("caption")) {
                Document caption = Jsoup.parse(post.getString("caption"));
                caption.select("img[src]").forEach(img -> {
                    try {
                        String imgSrc = img.attr("src").replaceAll("http:", "https:");
                        Matcher localQualM = qualP.matcher(imgSrc);
                        imgSrc = localQualM.replaceFirst("_1280.$1");
                        downloadURL(new URI(imgSrc).toURL(), date);
                    } catch (Exception e) {
                        logger.warn("Failed to download embedded image from caption", e);
                    }
                });
            }
            if (post.has("trail")) {
                JSONArray trails = post.getJSONArray("trail");
                for (int j = 0; j < trails.length(); j++) {
                    JSONObject trail = trails.getJSONObject(j);
                    if (trail.has("content_raw")) {
                        Document embedded = Jsoup.parse(trail.getString("content_raw"));
                        embedded.select("img[src]").forEach(img -> {
                            try {
                                String imgSrc = img.attr("src").replaceAll("http:", "https:");
                                Matcher localQualM = qualP.matcher(imgSrc);
                                imgSrc = localQualM.replaceFirst("_1280.$1");
                                downloadURL(new URI(imgSrc).toURL(), date);
                            } catch (Exception e) {
                                logger.warn("Failed to download embedded image from trail", e);
                            }
                        });
                    }
                }
            }
            if (albumType == ALBUM_TYPE.POST) {
                return false;
            }
        }
        return true;
    }

    private String getTumblrApiURL(String mediaType, int offset) {
        StringBuilder sb = new StringBuilder();
        String apiKey = getApiKey();
        lastRequestedApiKey = apiKey;

        if (albumType == ALBUM_TYPE.LIKED) {
            sb.append("https://api.tumblr.com/v2/blog/")
                    .append(subdomain)
                    .append("/likes")
                    .append("?api_key=")
                    .append(apiKey)
                    .append("&offset=")
                    .append(offset);
            return sb.toString();
        }
        if (albumType == ALBUM_TYPE.POST) {
            sb.append("https://api.tumblr.com/v2/blog/")
                    .append(subdomain)
                    .append("/posts?id=")
                    .append(postNumber)
                    .append("&api_key=")
                    .append(apiKey);
            return sb.toString();
        }
        sb.append("https://api.tumblr.com/v2/blog/")
                .append(subdomain)
                .append("/posts/")
                .append(mediaType)
                .append("?api_key=")
                .append(apiKey)
                .append("&offset=")
                .append(offset);
        if (albumType == ALBUM_TYPE.TAG) {
            sb.append("&tag=")
                    .append(tagName);
        }

        return sb.toString();
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        final String DOMAIN_REGEX = "^https?://([a-zA-Z0-9\\-.]+)";

        Pattern p;
        Matcher m;

        // Tagged URL
        p = Pattern.compile(DOMAIN_REGEX + "/tagged/([a-zA-Z0-9\\-%]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.TAG;
            this.subdomain = m.group(1);
            this.tagName = m.group(2);
            this.tagName = this.tagName.replace('-', '+').replace("_", "%20");
            return this.subdomain + "_tag_" + this.tagName.replace("%20", " ");
        }
        // Post URL
        p = Pattern.compile(DOMAIN_REGEX + "/post/([0-9]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.POST;
            this.subdomain = m.group(1);
            this.postNumber = m.group(2);
            return this.subdomain + "_post_" + this.postNumber;
        }
        // Subdomain-level URL
        p = Pattern.compile(DOMAIN_REGEX + "/?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.SUBDOMAIN;
            this.subdomain = m.group(1);
            return this.subdomain;
        }
        // Likes url
        p = Pattern.compile("https?://([a-z0-9_-]+).tumblr.com/likes");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.LIKED;
            this.subdomain = m.group(1);
            return this.subdomain + "_liked";
        }

        // Likes url different format
        p = Pattern.compile("https://www.tumblr.com/liked/by/([a-z0-9_-]+)");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.LIKED;
            this.subdomain = m.group(1);
            return this.subdomain + "_liked";
        }

        throw new MalformedURLException("Expected format: http://subdomain[.tumblr.com][/tagged/tag|/post/postno]");
    }

    private String getPrefix(int i) {
        String prefix = "";
        if (Utils.getConfigBoolean("download.save_order", true)) {
            prefix = String.format("%03d_", i);
        }
        return prefix;
    }

    public void downloadURL(URL url, String date) {
        if (!downloadLimitTracker.tryAcquire(url)) {
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

        int currentIndex = nextIndex.get();
        boolean added = false;

        if (url.getHost().equals("va.media.tumblr.com")) {
            added = addURLToDownload(url, getPrefix(currentIndex) + "tumblr_video_" + currentIndex);
        } else {
            if (albumType == ALBUM_TYPE.TAG) {
                added = addURLToDownload(url, date + " ");
            }
            if (!added) {
                added = addURLToDownload(url, getPrefix(currentIndex));
            }
        }

        if (added) {
            nextIndex.incrementAndGet();
            if (Utils.getConfigBoolean("urls_only.save", false)) {
                handleSuccessfulDownload(url);
            }
        } else {
            downloadLimitTracker.onFailure(url);
        }
    }

    @Override
    public void downloadCompleted(URL url, Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadExists(URL url, Path file) {
        super.downloadExists(url, file);
        downloadLimitTracker.onFailure(url);
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        downloadLimitTracker.onFailure(url);
        super.downloadErrored(url, reason);
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

    private boolean isDefaultApiKeyInUse() {
        return lastRequestedApiKey == null || DEFAULT_API_KEYS.contains(lastRequestedApiKey);
    }

    private String fetchErrorBody(String apiURL, String tumblrCookies) {
        try {
            Http http = Http.url(apiURL);
            if (tumblrCookies != null && !tumblrCookies.isEmpty()) {
                http = http.header("Cookie", tumblrCookies);
            }
            http.connection().ignoreHttpErrors(true);
            return http.ignoreContentType().get().body().text();
        } catch (Exception ex) {
            logger.warn("Couldn't fetch or parse raw response body", ex);
            return null;
        }
    }

    private boolean tryRipHiddenDashboard(String tumblrCookies) {
        if (tumblrCookies == null || tumblrCookies.isEmpty()) {
            logger.warn("Cannot attempt dashboard fallback without Tumblr cookies");
            return false;
        }

        String dashboardUrl = "https://www.tumblr.com/dashboard/blog/" + subdomain;
        try {
            Http http = Http.url(dashboardUrl).ignoreContentType().userAgent(AbstractRipper.USER_AGENT)
                    .referrer("https://www.tumblr.com/dashboard")
                    .header("Cookie", tumblrCookies);
            String html = http.get().outerHtml();
            String initialJson = extractInitialStateJson(html);
            if (initialJson == null || initialJson.isEmpty()) {
                logger.error("Unable to locate Tumblr dashboard initial state JSON for {}", subdomain);
                return false;
            }

            JSONObject root = new JSONObject(initialJson);
            HiddenPage page = parseHiddenPage(root);
            if (page == null || page.posts == null) {
                logger.error("Dashboard JSON for {} did not contain posts", subdomain);
                return false;
            }

            String apiUrl = root.optString("apiUrl", "https://www.tumblr.com");
            JSONObject apiFetchStore = root.optJSONObject("apiFetchStore");
            String bearerToken = apiFetchStore != null ? apiFetchStore.optString("API_TOKEN", null) : null;

            handleHiddenPosts(page.posts);
            if (maxDownloadLimitReached || isStopped()) {
                return true;
            }

            String nextHref = page.nextHref;
            while (nextHref != null && !nextHref.isEmpty() && !isStopped() && !maxDownloadLimitReached) {
                String nextUrl = nextHref.startsWith("http") ? nextHref : apiUrl + nextHref;
                JSONObject nextRoot = fetchHiddenApiPage(nextUrl, bearerToken, tumblrCookies);
                if (nextRoot == null) {
                    break;
                }
                HiddenPage nextPage = parseHiddenPage(nextRoot);
                if (nextPage == null || nextPage.posts == null || nextPage.posts.length() == 0) {
                    break;
                }
                handleHiddenPosts(nextPage.posts);
                if (maxDownloadLimitReached || isStopped()) {
                    break;
                }
                nextHref = nextPage.nextHref;
            }

            waitForThreads();
            return true;
        } catch (IOException e) {
            logger.error("Failed to fetch Tumblr dashboard HTML for {}", subdomain, e);
            return false;
        }
    }

    private JSONObject fetchHiddenApiPage(String url, String bearerToken, String tumblrCookies) {
        try {
            Http http = Http.url(url).ignoreContentType().userAgent(AbstractRipper.USER_AGENT)
                    .referrer("https://www.tumblr.com/dashboard/blog/" + subdomain)
                    .header("Cookie", tumblrCookies);
            String formKey = getTumblrCookieValue("form_key");
            if (formKey != null && !formKey.isEmpty()) {
                http = http.header("X-Tumblr-Form-Key", formKey);
            }
            if (bearerToken != null && !bearerToken.isEmpty()) {
                http = http.header("Authorization", "Bearer " + bearerToken);
            }
            String body = http.get().body().text();
            return new JSONObject(body);
        } catch (IOException e) {
            logger.warn("Failed to fetch Tumblr dashboard API page {}", url, e);
        }
        return null;
    }

    private HiddenPage parseHiddenPage(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONArray posts = null;
        String nextHref = null;

        if (root.has("PeeprRoute")) {
            JSONObject peeprRoute = root.optJSONObject("PeeprRoute");
            if (peeprRoute != null) {
                JSONObject initialTimeline = peeprRoute.optJSONObject("initialTimeline");
                if (initialTimeline != null) {
                    posts = initialTimeline.optJSONArray("objects");
                    JSONObject nextLink = initialTimeline.optJSONObject("nextLink");
                    if (nextLink != null) {
                        nextHref = nextLink.optString("href", null);
                    }
                }
            }
        }

        if (posts == null && root.has("response")) {
            JSONObject response = root.optJSONObject("response");
            if (response != null) {
                posts = response.optJSONArray("posts");
                JSONObject links = response.optJSONObject("_links");
                if (links != null) {
                    JSONObject next = links.optJSONObject("next");
                    if (next != null) {
                        nextHref = next.optString("href", null);
                    }
                }
            }
        }

        return new HiddenPage(posts, nextHref);
    }

    private void handleHiddenPosts(JSONArray posts) {
        if (posts == null) {
            return;
        }
        for (int i = 0; i < posts.length(); i++) {
            if (isStopped() || maxDownloadLimitReached) {
                break;
            }

            JSONObject post = posts.optJSONObject(i);
            if (post == null) {
                continue;
            }

            String dateLabel = post.optString("date", "");
            if (dateLabel.isEmpty()) {
                long timestamp = post.optLong("timestamp", 0L);
                if (timestamp > 0) {
                    dateLabel = DASHBOARD_DATE_FORMATTER.format(Instant.ofEpochSecond(timestamp));
                }
            }

            Set<String> mediaUrls = extractHiddenMediaUrls(post);
            if (mediaUrls.isEmpty()) {
                continue;
            }

            for (String mediaUrl : mediaUrls) {
                if (isStopped() || maxDownloadLimitReached) {
                    break;
                }
                try {
                    URL url = new URI(mediaUrl).toURL();
                    downloadURL(url, dateLabel);
                } catch (MalformedURLException | URISyntaxException e) {
                    logger.debug("Skipping malformed Tumblr media URL {}", mediaUrl, e);
                }
            }
        }
    }

    private Set<String> extractHiddenMediaUrls(JSONObject post) {
        Set<String> urls = new LinkedHashSet<>();
        String raw = post.toString();
        Matcher matcher = HIDDEN_MEDIA_PATTERN.matcher(raw);
        while (matcher.find()) {
            String url = matcher.group();
            if (url == null || url.isEmpty()) {
                continue;
            }
            String cleaned = url.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&");
            urls.add(cleaned);
        }
        if (post.has("trail")) {
            JSONArray trails = post.optJSONArray("trail");
            if (trails != null) {
                for (int i = 0; i < trails.length(); i++) {
                    JSONObject trail = trails.optJSONObject(i);
                    if (trail != null) {
                        urls.addAll(extractHiddenMediaUrls(trail));
                    }
                }
            }
        }
        return urls;
    }

    private String extractInitialStateJson(String html) {
        if (html == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("window\\['___INITIAL_STATE___'\\]\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
                .matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile("id=\"___INITIAL_STATE___\">\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL)
                .matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static class HiddenPage {
        final JSONArray posts;
        final String nextHref;

        HiddenPage(JSONArray posts, String nextHref) {
            this.posts = posts;
            this.nextHref = nextHref;
        }
    }

}