package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FacebookRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(FacebookRipper.class);
    private static final String DOMAIN = "facebook.com";
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile("https?:\\/\\/[^\"'\\s<>\\\\]+\\.(?:jpe?g|png|webp|gif|mp4)(?:\\?[^\"'\\s<>\\\\]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile("\\.(?:mp4|m4v)(?:$|\\?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANIFEST_URL_PATTERN = Pattern.compile("\\.(?:mpd|m3u8)(?:$|\\?)", Pattern.CASE_INSENSITIVE);
    // Facebook stores video/reel sources in JSON keys rather than as plain links. HD-quality keys
    // are extracted first so we prefer the highest available quality.
    private static final Pattern HD_VIDEO_KEY_PATTERN = Pattern.compile(
            "\"(?:playable_url_quality_hd|browser_native_hd_url|hd_src(?:_no_ratelimit)?)\"\\s*:\\s*\"(https?:[^\"]+?\\.(?:mp4|m4v)[^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SD_VIDEO_KEY_PATTERN = Pattern.compile(
            "\"(?:playable_url|browser_native_sd_url|sd_src(?:_no_ratelimit)?)\"\\s*:\\s*\"(https?:[^\"]+?\\.(?:mp4|m4v)[^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    // UI chrome / sprites / emoji / hard-coded asset icons that are not actual post media.
    private static final Pattern JUNK_URL_PATTERN = Pattern.compile(
            "rsrc\\.php|/emoji\\.php|/images/emoji|static\\.[^/]*fbcdn\\.net|/rsrc\\.php|sprite|spaceball"
                    + "|assets_DO_NOT_HARDCODE|facebook\\.com/images/|facebook\\.com/rsrc"
                    + "|/images/assets_|fbcdn\\.net/rsrc",
            Pattern.CASE_INSENSITIVE);
    // Photos embedded on profile/timeline pages are frequently only present as tiny grid thumbnails
    // (e.g. stp=..._s74x74_... or s206x206). Those download as <10 KB files that immediately get
    // deleted, so anything whose largest known dimension is below this is dropped before queueing.
    private static final long MIN_IMAGE_DIMENSION = 320L;
    // Facebook serves the same photo at many sizes; the size is encoded in the stp parameter
    // (e.g. stp=..._s480x480_... or p960x960). These let us collapse size-variants to one best image.
    private static final Pattern STP_PARAM_PATTERN = Pattern.compile("[?&]stp=([^&]+)");
    private static final Pattern IMAGE_SIZE_PATTERN = Pattern.compile("[sp](\\d{2,5})x\\d{2,5}");
    // Reel/video renditions carry a base64 "efg" blob describing the video_id, encode tag and bitrate.
    private static final Pattern EFG_PARAM_PATTERN = Pattern.compile("[?&]efg=([^&]+)");
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("\"video_id\"\\s*:\\s*(\\d+)");
    private static final Pattern VENCODE_TAG_PATTERN = Pattern.compile("\"vencode_tag\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BITRATE_PATTERN = Pattern.compile("\"bitrate\"\\s*:\\s*(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d{2,4})p");
    // Photo permalinks (e.g. /photo/?fbid=123, photo.php?fbid=123, "fbid":"123") found on a photos
    // listing page. Each links to a single photo page whose og:image is the full-resolution image.
    private static final Pattern FBID_PATTERN = Pattern.compile(
            "(?:[?&]fbid=|\"fbid\"\\s*:\\s*\"?)(\\d{6,})");

    private Map<String, String> facebookCookies = new LinkedHashMap<>();

    public FacebookRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "facebook";
    }

    @Override
    protected String getDomain() {
        return DOMAIN;
    }

    @Override
    public String getGID(URL url) {
        String path = url.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "home";
        }
        String normalized = path.replaceAll("^/+", "").replaceAll("/+$", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]+", "_");
        return normalized.isBlank() ? "post" : normalized;
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        String host = url.getHost().toLowerCase();
        if (host.endsWith("fb.com")) {
            return new URL(url.toExternalForm().replaceFirst("(?i)://(?:www\\.)?fb\\.com", "://www.facebook.com"));
        }
        if (host.equals("m.facebook.com")) {
            return new URL(url.toExternalForm().replaceFirst("(?i)://m\\.facebook\\.com", "://www.facebook.com"));
        }
        return url;
    }

    @Override
    protected Document getFirstPage() throws IOException {
        loadFacebookCookies();
        String body = fetchWithFallbacks();
        if (body == null || body.isBlank()) {
            throw new IOException("Facebook returned an empty response");
        }
        return Jsoup.parse(body, this.url.toExternalForm());
    }

    private String fetchWithFallbacks() throws IOException {
        List<URL> candidates = Arrays.asList(this.url, toMobileUrl(this.url), toMbasicUrl(this.url));
        List<String> referrers = Arrays.asList("https://www.facebook.com/", "https://m.facebook.com/", "https://mbasic.facebook.com/");
        HttpStatusException last400 = null;

        for (int i = 0; i < candidates.size(); i++) {
            URL candidate = candidates.get(i);
            Http request = newFacebookRequest(candidate, referrers.get(i));
            if (!facebookCookies.isEmpty()) {
                request.cookies(facebookCookies);
            }
            try {
                return request.get().html();
            } catch (HttpStatusException ex) {
                if (ex.getStatusCode() == 400 && i < candidates.size() - 1) {
                    logger.warn("Facebook returned HTTP 400 for {}, retrying with {}", candidate, candidates.get(i + 1));
                    last400 = ex;
                    continue;
                }
                throw ex;
            }
        }

        if (last400 != null) {
            throw last400;
        }
        throw new IOException("Failed to load Facebook page from all fallback URLs");
    }

    private Http newFacebookRequest(URL targetUrl, String referrer) {
        return Http.url(targetUrl)
                .userAgent(USER_AGENT)
                .referrer(referrer)
                .ignoreContentType()
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Upgrade-Insecure-Requests", "1");
    }

    private URL toMbasicUrl(URL source) throws MalformedURLException {
        String target = source.toExternalForm()
                .replaceFirst("(?i)://(?:www\\.|m\\.)?facebook\\.com", "://mbasic.facebook.com")
                .replaceFirst("(?i)://(?:www\\.)?fb\\.com", "://mbasic.facebook.com");
        return new URL(target);
    }

    private URL toMobileUrl(URL source) throws MalformedURLException {
        String target = source.toExternalForm()
                .replaceFirst("(?i)://(?:www\\.|mbasic\\.)?facebook\\.com", "://m.facebook.com")
                .replaceFirst("(?i)://(?:www\\.)?fb\\.com", "://m.facebook.com");
        return new URL(target);
    }

    @Override
    protected List<String> getURLsFromPage(Document page) throws UnsupportedEncodingException {
        Set<String> allMedia = new LinkedHashSet<>();
        collectMediaFromDocument(page, allMedia);

        // A photos/album listing page only embeds full-resolution URLs for the first handful of
        // photos; the rest are present solely as tiny thumbnails. Visit each photo's permalink to
        // pull its full-resolution image so the whole album is downloaded, not just the first screen.
        if (isPhotoListingPage()) {
            crawlPhotoPages(page, allMedia);
        }

        // Facebook exposes the same photo at many sizes and the same reel at many renditions.
        // Collapse those duplicates: one best image per photo, one best file per video_id.
        Map<String, ScoredUrl> bestImageByKey = new LinkedHashMap<>();
        Map<String, ScoredUrl> bestVideoByKey = new LinkedHashMap<>();
        List<String> manifestFallback = new ArrayList<>();

        for (String mediaUrl : allMedia) {
            if (mediaUrl == null) {
                continue;
            }
            if (MANIFEST_URL_PATTERN.matcher(mediaUrl).find()) {
                if (!manifestFallback.contains(mediaUrl)) {
                    manifestFallback.add(mediaUrl);
                }
            } else if (VIDEO_URL_PATTERN.matcher(mediaUrl).find()) {
                VideoInfo info = videoInfo(mediaUrl);
                if (info.audioOnly) {
                    // Standalone audio tracks are useless on their own; skip them.
                    continue;
                }
                String key = info.videoId != null ? "id:" + info.videoId : "path:" + urlPath(mediaUrl);
                ScoredUrl existing = bestVideoByKey.get(key);
                if (existing == null || info.score > existing.score) {
                    bestVideoByKey.put(key, new ScoredUrl(mediaUrl, info.score));
                }
            } else {
                String key = urlPath(mediaUrl);
                long size = imageSizeScore(mediaUrl);
                ScoredUrl existing = bestImageByKey.get(key);
                if (existing == null || size > existing.score) {
                    bestImageByKey.put(key, new ScoredUrl(mediaUrl, size));
                }
            }
        }

        List<String> videoOnly = new ArrayList<>();
        for (ScoredUrl scored : bestVideoByKey.values()) {
            videoOnly.add(scored.url);
        }
        List<String> images = new ArrayList<>();
        for (ScoredUrl scored : bestImageByKey.values()) {
            // scored.score holds the largest dimension we could find for this photo (Long.MAX_VALUE
            // when the URL carries no size token, i.e. a full-resolution image). Skip thumbnail-only
            // photos whose best available size is below the floor; they would just be downloaded and
            // deleted for being under the minimum file size.
            if (scored.score < MIN_IMAGE_DIMENSION) {
                logger.debug("Skipping Facebook thumbnail-only image (max dim " + scored.score + "): " + scored.url);
                continue;
            }
            images.add(scored.url);
        }

        if (videoOnly.isEmpty() && images.isEmpty() && manifestFallback.isEmpty()) {
            throw new IllegalStateException("No downloadable Facebook media URLs were discovered on page");
        }

        // Prefer direct video streams when available, then DASH/HLS manifests, then images.
        if (!videoOnly.isEmpty()) {
            return videoOnly;
        }
        if (!manifestFallback.isEmpty()) {
            return manifestFallback;
        }
        return images;
    }

    /**
     * Scrapes every media URL embedded in a single Facebook document (video keys, OpenGraph/Twitter
     * meta tags and any media URL embedded in the page's escaped JSON) into {@code allMedia}.
     */
    private void collectMediaFromDocument(Document page, Set<String> allMedia) {
        String rawHtml = page.html();
        // Facebook embeds almost all media inside JSON blobs in <script> tags where slashes are
        // escaped (https:\/\/...). Unescaping first is what allows the regex to actually match them.
        String unescapedHtml = jsonUnescape(rawHtml);

        // Explicit FB video keys (HD before SD).
        extractVideoKeys(rawHtml, HD_VIDEO_KEY_PATTERN, allMedia);
        extractVideoKeys(rawHtml, SD_VIDEO_KEY_PATTERN, allMedia);

        addMetaMedia(page, allMedia, "meta[property=og:image]");
        addMetaMedia(page, allMedia, "meta[property=og:image:secure_url]");
        addMetaMedia(page, allMedia, "meta[property=og:video]");
        addMetaMedia(page, allMedia, "meta[property=og:video:secure_url]");
        addMetaMedia(page, allMedia, "meta[property=twitter:image]");
        addMetaMedia(page, allMedia, "meta[property=twitter:player:stream]");

        Matcher m = MEDIA_URL_PATTERN.matcher(unescapedHtml);
        while (m.find()) {
            String mediaUrl = unescapeJsonUrl(m.group());
            if (mediaUrl != null && !JUNK_URL_PATTERN.matcher(mediaUrl).find()) {
                allMedia.add(mediaUrl);
            }
        }
    }

    /**
     * @return true when the URL being ripped is a photo/album listing (e.g. {@code /name/photos} or
     *         a {@code /media/set/} album) rather than a single photo/video/reel permalink.
     */
    private boolean isPhotoListingPage() {
        String full = this.url.toExternalForm().toLowerCase();
        String path = this.url.getPath() == null ? "" : this.url.getPath().toLowerCase();
        // Single-media permalinks already expose the full image/video directly, so don't crawl them.
        if (full.contains("fbid=") || full.contains("story_fbid=")
                || full.contains("/watch") || full.contains("/reel")
                || path.matches(".*/videos?(/.*|$)") || path.matches(".*/photo(/.*|$)")) {
            return false;
        }
        return path.contains("/photos") || full.contains("sk=photos") || full.contains("/media/set");
    }

    /**
     * Visits each photo permalink referenced on a listing page and merges the full-resolution image
     * from that photo's page into {@code allMedia}. Failures on individual photos are logged and
     * skipped so one bad photo can't abort the whole rip.
     */
    private void crawlPhotoPages(Document listingPage, Set<String> allMedia) {
        Set<String> fbids = extractFbids(listingPage);
        if (fbids.isEmpty()) {
            return;
        }
        int cap = Utils.getConfigInteger("facebook.max_photo_pages", 1000);
        long delay = Utils.getConfigInteger("facebook.photo_page_delay_ms", 300);
        logger.info("Found {} Facebook photo permalink(s); fetching full-resolution images", fbids.size());

        int fetched = 0;
        for (String fbid : fbids) {
            if (isStopped()) {
                break;
            }
            if (fetched >= cap) {
                logger.warn("Reached facebook.max_photo_pages cap ({}); stopping photo crawl", cap);
                break;
            }
            try {
                Document photoDoc = fetchPhotoPage(fbid);
                if (photoDoc != null) {
                    collectMediaFromDocument(photoDoc, allMedia);
                }
            } catch (IOException e) {
                logger.warn("Failed to fetch Facebook photo page fbid={}: {}", fbid, e.getMessage());
            }
            fetched++;
            if (delay > 0 && fetched < fbids.size()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private Set<String> extractFbids(Document page) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = FBID_PATTERN.matcher(jsonUnescape(page.html()));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /**
     * Fetches a single Facebook photo page. Exposed (protected) so tests can supply a local document
     * without performing network I/O.
     */
    protected Document fetchPhotoPage(String fbid) throws IOException {
        URL photoUrl = new URL("https://www.facebook.com/photo/?fbid=" + fbid);
        Http request = newFacebookRequest(photoUrl, this.url.toExternalForm());
        if (!facebookCookies.isEmpty()) {
            request.cookies(facebookCookies);
        }
        return request.get();
    }

    private void extractVideoKeys(String html, Pattern keyPattern, Set<String> target) {
        Matcher matcher = keyPattern.matcher(html);
        while (matcher.find()) {
            String videoUrl = unescapeJsonUrl(matcher.group(1));
            if (videoUrl != null && !videoUrl.isBlank()) {
                target.add(videoUrl);
            }
        }
    }

    private static String urlPath(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    /**
     * Ranks a Facebook image URL by its rendition size so we can keep only the largest variant of
     * each photo. URLs without a resizing {@code stp} parameter are treated as the original.
     */
    private static long imageSizeScore(String url) {
        Matcher stp = STP_PARAM_PATTERN.matcher(url);
        if (!stp.find()) {
            return Long.MAX_VALUE;
        }
        Matcher size = IMAGE_SIZE_PATTERN.matcher(stp.group(1));
        long best = -1;
        while (size.find()) {
            best = Math.max(best, Long.parseLong(size.group(1)));
        }
        return best < 0 ? Long.MAX_VALUE : best;
    }

    /**
     * Decodes the metadata Facebook attaches to a reel/video URL (video_id, encode tag, bitrate)
     * and turns it into a comparable score. The highest resolution wins, then the highest bitrate;
     * progressive renditions are only used as a tiebreaker. DASH renditions are video-only (no
     * audio), which is acceptable since maximum quality is preferred.
     */
    private VideoInfo videoInfo(String url) {
        VideoInfo info = new VideoInfo();
        String efg = decodeEfg(url);
        if (efg != null) {
            Matcher id = VIDEO_ID_PATTERN.matcher(efg);
            if (id.find()) {
                info.videoId = id.group(1);
            }
            Matcher tagMatcher = VENCODE_TAG_PATTERN.matcher(efg);
            String tag = tagMatcher.find() ? tagMatcher.group(1).toLowerCase() : "";
            info.audioOnly = tag.contains("audio");
            info.progressive = tag.contains("progressive");
            Matcher res = RESOLUTION_PATTERN.matcher(tag);
            if (res.find()) {
                info.resolution = Integer.parseInt(res.group(1));
            }
            Matcher br = BITRATE_PATTERN.matcher(efg);
            if (br.find()) {
                try {
                    info.bitrate = Long.parseLong(br.group(1));
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
        }
        info.computeScore();
        return info;
    }

    private String decodeEfg(String url) {
        Matcher m = EFG_PARAM_PATTERN.matcher(url);
        if (!m.find()) {
            return null;
        }
        try {
            String encoded = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException standardFailed) {
            try {
                String encoded = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException urlSafeFailed) {
                return null;
            }
        }
    }

    private static final class ScoredUrl {
        final String url;
        final long score;

        ScoredUrl(String url, long score) {
            this.url = url;
            this.score = score;
        }
    }

    private static final class VideoInfo {
        String videoId;
        boolean audioOnly;
        boolean progressive;
        int resolution;
        long bitrate;
        long score;

        void computeScore() {
            // Prefer the highest resolution, then bitrate. Progressive is only a final tiebreaker
            // (Facebook's DASH renditions are video-only, but higher quality is preferred here).
            long value = (long) resolution * 100_000_000L;
            value += Math.min(bitrate, 99_999_999L);
            if (progressive) {
                value += 1;
            }
            score = value;
        }
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }

    private void addMetaMedia(Document page, Set<String> found, String selector) {
        for (Element e : page.select(selector)) {
            String content = e.attr("content");
            if (content != null && !content.isBlank()) {
                found.add(unescapeJsonUrl(content.trim()));
            }
        }
    }

    private String jsonUnescape(String body) {
        if (body == null) {
            return "";
        }
        // Turn JSON-escaped slashes/unicode sequences back into a normal URL form so the media
        // regex can match URLs that Facebook stores inside <script> JSON.
        return body
                .replace("\\/", "/")
                .replace("\\u0025", "%")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=");
    }

    private String unescapeJsonUrl(String value) {
        if (value == null) {
            return null;
        }
        return Parser.unescapeEntities(value, false)
                .replace("\\u0025", "%")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=");
    }

    private void loadFacebookCookies() {
        if (!facebookCookies.isEmpty()) {
            return;
        }

        List<String> hostPatterns = Arrays.asList("%.facebook.com", "%.fb.com");
        for (Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
            Map<String, String> cookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath, hostPatterns);
            if (!cookies.isEmpty()) {
                facebookCookies.putAll(cookies);
            }
        }

        logger.info("Loaded {} Facebook cookie(s) from Firefox profiles", facebookCookies.size());
    }
}
