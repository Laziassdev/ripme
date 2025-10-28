package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.StringEscapeUtils;
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
    private static final Pattern HIDDEN_MEDIA_SIZE_SUFFIX_PATTERN = Pattern.compile(
            "_(raw|\\d{2,4})(?=\\.[^./]+$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_MEDIA_PATH_SIZE_PATTERN = Pattern.compile(
            "/s(\\d{2,4})x(\\d{2,4})(?:_[^/]+)?/",
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
    private final Set<String> hiddenFallbackSeenKeys = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> seenMediaKeys = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, String> mediaKeyByUrl = Collections.synchronizedMap(new LinkedHashMap<>());

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
        URL resolvedUrl = maybeUpgradeTumblrMediaUrl(url);
        if (resolvedUrl == null) {
            resolvedUrl = url;
        }

        String canonicalKey = canonicalTumblrMediaKey(resolvedUrl);
        if (canonicalKey != null) {
            synchronized (seenMediaKeys) {
                if (!seenMediaKeys.add(canonicalKey)) {
                    logger.debug("Skipping duplicate Tumblr media candidate {}", resolvedUrl);
                    return;
                }
            }
            synchronized (mediaKeyByUrl) {
                mediaKeyByUrl.put(resolvedUrl.toExternalForm(), canonicalKey);
            }
        }

        int currentIndex = nextIndex.get();
        boolean countTowardsLimit = true;
        if (downloadLimitTracker.isEnabled()) {
            try {
                String prefix;
                if (resolvedUrl.getHost().equals("va.media.tumblr.com")) {
                    prefix = getPrefix(currentIndex) + "tumblr_video_" + currentIndex;
                } else if (albumType == ALBUM_TYPE.TAG) {
                    prefix = date + " ";
                } else {
                    prefix = getPrefix(currentIndex);
                }
                Path existingPath = getFilePath(resolvedUrl, "", prefix, null, null);
                if (Files.exists(existingPath)) {
                    if (!Utils.getConfigBoolean("file.overwrite", false)) {
                        logger.debug("Skipping existing file due to max download limit: {}", existingPath);
                        super.downloadExists(resolvedUrl, existingPath);
                        unregisterMediaKeyMapping(resolvedUrl);
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
                    String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                    logger.info(message);
                    sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                }
            } else {
                logger.debug("Max download limit of {} currently allocated, deferring {}", maxDownloads, resolvedUrl);
            }
            if (canonicalKey != null) {
                unregisterTumblrMediaKey(resolvedUrl);
            }
            return;
        }

        boolean added = false;

        if (resolvedUrl.getHost().equals("va.media.tumblr.com")) {
            added = addURLToDownload(resolvedUrl, getPrefix(currentIndex) + "tumblr_video_" + currentIndex);
        } else {
            if (albumType == ALBUM_TYPE.TAG) {
                added = addURLToDownload(resolvedUrl, date + " ");
            }
            if (!added) {
                added = addURLToDownload(resolvedUrl, getPrefix(currentIndex));
            }
        }

        if (added) {
            nextIndex.incrementAndGet();
            if (Utils.getConfigBoolean("urls_only.save", false)) {
                handleSuccessfulDownload(resolvedUrl);
                unregisterMediaKeyMapping(resolvedUrl);
            }
        } else {
            downloadLimitTracker.onFailure(resolvedUrl);
            if (canonicalKey != null) {
                unregisterTumblrMediaKey(resolvedUrl);
            }
        }
    }

    @Override
    public void downloadCompleted(URL url, Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
        unregisterMediaKeyMapping(url);
    }

    @Override
    public void downloadExists(URL url, Path file) {
        super.downloadExists(url, file);
        downloadLimitTracker.onFailure(url);
        unregisterMediaKeyMapping(url);
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        downloadLimitTracker.onFailure(url);
        super.downloadErrored(url, reason);
        unregisterTumblrMediaKey(url);
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

        String dashboardBlogIdentifier = getDashboardBlogIdentifier();
        if (dashboardBlogIdentifier == null || dashboardBlogIdentifier.isEmpty()) {
            logger.warn("Cannot attempt dashboard fallback without a valid blog identifier for {}", subdomain);
            return false;
        }

        String dashboardUrl = "https://www.tumblr.com/dashboard/blog/" + dashboardBlogIdentifier;
        try {
            Http http = Http.url(dashboardUrl).ignoreContentType().userAgent(AbstractRipper.USER_AGENT)
                    .referrer("https://www.tumblr.com/dashboard")
                    .header("Cookie", tumblrCookies);
            String html = http.response().body();
            String initialJson = extractInitialStateJson(html);
            if (initialJson == null || initialJson.isEmpty()) {
                logger.error("Unable to locate Tumblr dashboard initial state JSON for {}", dashboardBlogIdentifier);
                return false;
            }

            JSONObject root = new JSONObject(initialJson);
            HiddenPage page = parseHiddenPage(root);
            if (page == null || page.posts == null) {
                logger.error("Dashboard JSON for {} did not contain posts", dashboardBlogIdentifier);
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
                JSONObject nextRoot = fetchHiddenApiPage(nextUrl, bearerToken, tumblrCookies, dashboardBlogIdentifier);
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
            logger.error("Failed to fetch Tumblr dashboard HTML for {}", dashboardBlogIdentifier, e);
            return false;
        }
    }

    private JSONObject fetchHiddenApiPage(String url, String bearerToken, String tumblrCookies, String dashboardBlogIdentifier) {
        try {
            Http http = Http.url(url).ignoreContentType().userAgent(AbstractRipper.USER_AGENT)
                    .referrer("https://www.tumblr.com/dashboard/blog/" + dashboardBlogIdentifier)
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

    private String getDashboardBlogIdentifier() {
        if (subdomain == null || subdomain.isEmpty()) {
            return null;
        }
        if (subdomain.endsWith(".tumblr.com")) {
            return subdomain.substring(0, subdomain.length() - ".tumblr.com".length());
        }
        return subdomain;
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

            Set<String> filteredMediaUrls = selectBestHiddenMediaUrls(mediaUrls);
            for (String mediaUrl : filteredMediaUrls) {
                if (isStopped() || maxDownloadLimitReached) {
                    break;
                }
                String normalized = normalizeHiddenMediaUrl(mediaUrl);
                if (normalized == null) {
                    normalized = mediaUrl;
                }
                synchronized (hiddenFallbackSeenKeys) {
                    if (!hiddenFallbackSeenKeys.add(normalized)) {
                        logger.debug("Skipping duplicate Tumblr fallback media URL {}", mediaUrl);
                        continue;
                    }
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
        collectHiddenMediaFromContent(post.optJSONArray("content"), urls);
        collectHiddenMediaFromTrail(post.optJSONArray("trail"), urls);
        collectHiddenMediaFromAttachments(post.optJSONArray("attachments"), urls);

        if (urls.isEmpty()) {
            urls.addAll(extractHiddenMediaUrlsFromRaw(post.toString()));
        }

        return urls;
    }

    private void collectHiddenMediaFromTrail(JSONArray trails, Set<String> urls) {
        if (trails == null) {
            return;
        }
        for (int i = 0; i < trails.length(); i++) {
            JSONObject trail = trails.optJSONObject(i);
            if (trail == null) {
                continue;
            }
            collectHiddenMediaFromContent(trail.optJSONArray("content"), urls);
            JSONObject trailPost = trail.optJSONObject("post");
            if (trailPost != null) {
                collectHiddenMediaFromContent(trailPost.optJSONArray("content"), urls);
                collectHiddenMediaFromAttachments(trailPost.optJSONArray("attachments"), urls);
            }
        }
    }

    private void collectHiddenMediaFromAttachments(JSONArray attachments, Set<String> urls) {
        if (attachments == null) {
            return;
        }
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject attachment = attachments.optJSONObject(i);
            if (attachment == null) {
                continue;
            }
            Object media = attachment.opt("media");
            if (media != null) {
                collectHiddenMediaFromValue(media, urls);
            }
            collectHiddenMediaFromContent(attachment.optJSONArray("content"), urls);
        }
    }

    private void collectHiddenMediaFromContent(JSONArray content, Set<String> urls) {
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) {
                continue;
            }
            Object media = block.opt("media");
            if (media != null) {
                collectHiddenMediaFromValue(media, urls);
            }
            Object poster = block.opt("poster");
            if (poster != null) {
                collectHiddenMediaFromValue(poster, urls);
            }
            Object thumbnail = block.opt("thumbnail");
            if (thumbnail != null) {
                collectHiddenMediaFromValue(thumbnail, urls);
            }
            String directUrl = block.optString("url", null);
            if (directUrl != null) {
                addHiddenMediaCandidate(urls, directUrl);
            }
            collectHiddenMediaFromContent(block.optJSONArray("content"), urls);
        }
    }

    private void collectHiddenMediaFromValue(Object value, Set<String> urls) {
        if (value == null) {
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectHiddenMediaFromValue(array.opt(i), urls);
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String url = object.optString("url", null);
            if (url != null) {
                addHiddenMediaCandidate(urls, url);
            }
            collectHiddenMediaFromValue(object.opt("media"), urls);
            collectHiddenMediaFromValue(object.opt("poster"), urls);
            return;
        }
        if (value instanceof String) {
            addHiddenMediaCandidate(urls, (String) value);
        }
    }

    private void addHiddenMediaCandidate(Set<String> urls, String url) {
        if (!isHiddenMediaCandidate(url)) {
            return;
        }
        String cleaned = url.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&");
        try {
            cleaned = URLDecoder.decode(cleaned, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
            // Ignore malformed escape sequences and fall back to the un-decoded URL
        }
        urls.add(cleaned);
    }

    private boolean isHiddenMediaCandidate(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Matcher matcher = HIDDEN_MEDIA_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.contains("tumblr.com")) {
            if (lower.contains("/avatar") || lower.contains("tumblr_static") || lower.contains("assets.tumblr.com")) {
                return false;
            }
        }
        return true;
    }

    private Set<String> extractHiddenMediaUrlsFromRaw(String raw) {
        Set<String> urls = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return urls;
        }
        Matcher matcher = HIDDEN_MEDIA_PATTERN.matcher(raw);
        while (matcher.find()) {
            String candidate = matcher.group();
            addHiddenMediaCandidate(urls, candidate);
        }
        return urls;
    }

    private Set<String> selectBestHiddenMediaUrls(Set<String> urls) {
        Map<String, String> bestByGroup = new LinkedHashMap<>();
        for (String url : urls) {
            if (url == null || url.isEmpty()) {
                continue;
            }
            String groupKey = hiddenMediaGroupKey(url);
            if (groupKey == null || groupKey.isEmpty()) {
                groupKey = url;
            }
            String existing = bestByGroup.get(groupKey);
            if (existing == null || hiddenMediaVariantScore(url) > hiddenMediaVariantScore(existing)) {
                bestByGroup.put(groupKey, url);
            }
        }
        return new LinkedHashSet<>(bestByGroup.values());
    }

    private int hiddenMediaVariantScore(String url) {
        int quality = hiddenMediaQualityScore(url);
        if (quality == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long combined = quality * 1000L + hiddenMediaExtensionScore(url);
        if (combined > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) combined;
    }

    private int hiddenMediaExtensionScore(String url) {
        if (url == null) {
            return 0;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) {
            return 600;
        }
        if (lower.endsWith(".webm") || lower.endsWith(".m4v")) {
            return 550;
        }
        if (lower.endsWith(".gifv")) {
            return 525;
        }
        if (lower.endsWith(".gif")) {
            return 500;
        }
        if (lower.endsWith(".webp")) {
            return 400;
        }
        if (lower.endsWith(".png")) {
            return 350;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return 300;
        }
        return 0;
    }

    private int hiddenMediaQualityScore(String url) {
        if (url == null) {
            return 0;
        }
        Matcher matcher = HIDDEN_MEDIA_SIZE_SUFFIX_PATTERN.matcher(url);
        if (matcher.find()) {
            String token = matcher.group(1);
            if ("raw".equalsIgnoreCase(token)) {
                return Integer.MAX_VALUE;
            }
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        matcher = HIDDEN_MEDIA_PATH_SIZE_PATTERN.matcher(url);
        if (matcher.find()) {
            try {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                return Math.max(width, height);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String normalizeHiddenMediaUrl(String url) {
        if (url == null) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        String normalized = HIDDEN_MEDIA_SIZE_SUFFIX_PATTERN.matcher(withoutQuery).replaceAll("");
        normalized = HIDDEN_MEDIA_PATH_SIZE_PATTERN.matcher(normalized).replaceAll("/");
        return normalized;
    }

    private String hiddenMediaGroupKey(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int queryIndex = url.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        Matcher sizeSegmentMatcher = HIDDEN_MEDIA_PATH_SIZE_PATTERN.matcher(withoutQuery);
        if (sizeSegmentMatcher.find()) {
            return withoutQuery.substring(0, sizeSegmentMatcher.start());
        }
        String normalized = normalizeHiddenMediaUrl(withoutQuery);
        if (normalized == null || normalized.isEmpty()) {
            return normalized;
        }
        int lastSlash = normalized.lastIndexOf('/');
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot > lastSlash) {
            return normalized.substring(0, lastDot);
        }
        return normalized;
    }

    private URL maybeUpgradeTumblrMediaUrl(URL url) {
        if (url == null) {
            return null;
        }
        String host = url.getHost();
        if (host == null || !host.toLowerCase().contains("tumblr.com")) {
            return url;
        }
        String original = url.toExternalForm();
        int queryIndex = original.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? original.substring(0, queryIndex) : original;
        Matcher matcher = HIDDEN_MEDIA_SIZE_SUFFIX_PATTERN.matcher(withoutQuery);
        if (matcher.find()) {
            String token = matcher.group(1);
            if (!"raw".equalsIgnoreCase(token)) {
                String candidate = withoutQuery.substring(0, matcher.start()) + "_raw" + withoutQuery.substring(matcher.end());
                try {
                    URL rawUrl = new URL(candidate);
                    if (hasReachableTumblrMedia(rawUrl)) {
                        return rawUrl;
                    }
                } catch (MalformedURLException e) {
                    logger.debug("Ignoring malformed Tumblr raw candidate {}", candidate, e);
                }
            }
        }
        return url;
    }

    private boolean hasReachableTumblrMedia(URL url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            return status >= 200 && status < 400;
        } catch (IOException e) {
            logger.debug("Failed to probe Tumblr media URL {}", url, e);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String canonicalTumblrMediaKey(URL url) {
        if (url == null) {
            return null;
        }
        String host = url.getHost();
        if (host == null || !host.toLowerCase().contains("tumblr.com")) {
            return null;
        }
        return normalizeHiddenMediaUrl(url.toExternalForm());
    }

    private void unregisterTumblrMediaKey(URL url) {
        if (url == null) {
            return;
        }
        String key;
        synchronized (mediaKeyByUrl) {
            key = mediaKeyByUrl.remove(url.toExternalForm());
        }
        if (key != null) {
            synchronized (seenMediaKeys) {
                seenMediaKeys.remove(key);
            }
        }
    }

    private void unregisterMediaKeyMapping(URL url) {
        if (url == null) {
            return;
        }
        synchronized (mediaKeyByUrl) {
            mediaKeyByUrl.remove(url.toExternalForm());
        }
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
        matcher = Pattern.compile(
                "window\\['___INITIAL_STATE___'\\]\\s*=\\s*JSON\\.parse\\((?:\\\"|\\')?(.*?)(?:\\\"|\\')?\\);",
                Pattern.DOTALL)
                .matcher(html);
        if (matcher.find()) {
            return decodeInitialStateJson(matcher.group(1), false);
        }
        matcher = Pattern.compile(
                "window\\['___INITIAL_STATE___'\\]\\s*=\\s*JSON\\.parse\\(decodeURIComponent\\((?:\\\"|\\')?(.*?)(?:\\\"|\\')?\\)\\)\\);",
                Pattern.DOTALL)
                .matcher(html);
        if (matcher.find()) {
            return decodeInitialStateJson(matcher.group(1), true);
        }
        return null;
    }

    private String decodeInitialStateJson(String encoded, boolean uriDecoded) {
        if (encoded == null) {
            return null;
        }
        String unescaped = StringEscapeUtils.unescapeJavaScript(encoded);
        if (uriDecoded) {
            try {
                unescaped = URLDecoder.decode(unescaped, "UTF-8");
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                logger.warn("Failed to URL decode Tumblr initial state JSON", e);
            }
        }
        return unescaped;
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