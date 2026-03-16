package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

/**
 * Ripper for https://umd.net
 * Supports: forums (/forums/...), groups (/groups), pic archive (/picarchive), gallery (/gallery),
 * profile photo albums (/profile/id/.../section/photos/album/...).
 * Cookies are read from Firefox (if logged in there) or from cookies.umd.net in rip.properties.
 */
public class UmdRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(UmdRipper.class);

    private Map<String, String> umdCookiesCache;

    private static final Pattern IMAGE_EXT = Pattern.compile(
            ".*\\.(jpe?g|png|gif|webp|bmp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_EXT = Pattern.compile(
            ".*\\.(mp4|webm|mov|avi|mkv)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    /** Match UMD profile album image URLs in script/JSON or HTML (e.g. mucky.umd.net/media/photos/.../thumbs/...jpg). */
    private static final Pattern UMD_PHOTO_URL_IN_PAGE = Pattern.compile(
            "https?://(?:mucky\\.)?umd\\.net/media/photos/[^\"'\\s<>]+\\.(?:jpe?g|png|gif|webp|bmp)(?:\\?[^\"'\\s<>]*)?",
            Pattern.CASE_INSENSITIVE);

    public UmdRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "umd";
    }

    @Override
    protected String getDomain() {
        return "umd.net";
    }

    @Override
    public boolean canRip(URL url) {
        if (!url.getHost().toLowerCase().endsWith("umd.net")) {
            return false;
        }
        String path = url.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        boolean isForumOrGroup = path.startsWith("/forums/") || path.equals("/groups") || path.startsWith("/groups/");
        boolean isPicArchive = path.equals("/picarchive") || path.startsWith("/picarchive");
        boolean isGallery = path.equals("/gallery") || path.startsWith("/gallery");
        boolean isPhotoAlbum = path.contains("/profile/") && path.contains("/section/photos/album/");
        boolean isVideoSection = path.contains("/profile/") && path.contains("/section/videos");
        return isForumOrGroup || isPicArchive || isGallery || isPhotoAlbum || isVideoSection;
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        return url;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            throw new MalformedURLException("Expected umd.net path, got: " + url);
        }
        path = path.replaceAll("^/+|/+$", "");
        String[] parts = path.split("/");
        StringBuilder gid = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            if (gid.length() > 0) {
                gid.append("_");
            }
            gid.append(parts[i].replaceAll("[^a-zA-Z0-9_-]", "_"));
        }
        if (gid.length() == 0) {
            gid.append("umd");
        }
        return gid.toString();
    }

    @Override
    protected boolean hasQueueSupport() {
        return true;
    }

    @Override
    protected boolean pageContainsAlbums(URL url) {
        if (url == null) {
            return false;
        }
        String path = url.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        // Treat profile video listings as "album index" pages for queuing
        return path.contains("/profile/") && path.contains("/section/videos")
                && !path.contains("/section/videos/video/");
    }

    @Override
    protected List<String> getAlbumsToQueue(Document doc) {
        List<String> result = new ArrayList<>();
        if (doc == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        Document current = doc;

        while (current != null) {
            String baseUrl = current.location();
            for (Element a : current.select("a[href*=\"/section/videos/video/\"]")) {
                String href = a.hasAttr("abs:href") ? a.attr("abs:href") : a.attr("href");
                if (href == null || href.isBlank()) {
                    continue;
                }
                if (!href.startsWith("http")) {
                    href = resolveUrl(baseUrl, href);
                }
                if (href != null && !href.isBlank() && seen.add(href)) {
                    result.add(href);
                }
            }
            try {
                current = getNextPage(current);
            } catch (IOException | URISyntaxException e) {
                logger.warn("Error while loading next UMD videos page for queue: {}", e.getMessage());
                break;
            }
        }

        return result;
    }

    /**
     * Returns cookies for umd.net: config (cookies.umd.net) merged with Firefox cookies.
     * Used so the user can be logged in via Firefox without setting config.
     */
    private Map<String, String> getUmdCookies() {
        if (umdCookiesCache != null) {
            return umdCookiesCache;
        }
        Map<String, String> cookies = new LinkedHashMap<>();
        String configCookies = Utils.getConfigString("cookies.umd.net", "");
        if (configCookies != null && !configCookies.isBlank()) {
            cookies.putAll(RipUtils.getCookiesFromString(configCookies.trim()));
        }
        if (FirefoxCookieUtils.isSQLiteDriverAvailable()) {
            for (java.nio.file.Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
                Map<String, String> profileCookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath,
                        Arrays.asList("%umd.net", "%.umd.net"));
                if (!profileCookies.isEmpty()) {
                    cookies.putAll(profileCookies);
                    logger.info("Loaded {} umd.net cookies from Firefox profile {}", profileCookies.size(),
                            profilePath.getFileName());
                    break;
                }
            }
        }
        umdCookiesCache = cookies;
        return cookies;
    }

    @Override
    protected Document getFirstPage() throws IOException, URISyntaxException {
        int timeout = Utils.getConfigInteger("download.timeout", 60000);
        return Jsoup.connect(this.url.toExternalForm())
                .userAgent(AbstractRipper.USER_AGENT)
                .cookies(getUmdCookies())
                .timeout(timeout)
                .get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException, URISyntaxException {
        if (doc == null) {
            return null;
        }
        String base = doc.location();
        Element nextLink = doc.selectFirst("a[rel=next], link[rel=next], .pagination a.next, .pager-next a, a.next");
        if (nextLink == null) {
            Elements links = doc.select("a[href*='page='], a[href*='page/']");
            for (Element a : links) {
                String text = a.text();
                if (text != null && (text.equalsIgnoreCase("next") || text.matches("\\d+") || text.contains("»"))) {
                    nextLink = a;
                    break;
                }
            }
        }
        // Profile album: pagination may use different markup; accept any link to same album with page param
        if (nextLink == null && isProfileAlbumUrl(this.url)) {
            String path = this.url.getPath();
            if (path != null) {
                Elements pageLinks = doc.select("a[href*='page='], a[href*='page/']");
                for (Element a : pageLinks) {
                    String href = a.hasAttr("abs:href") ? a.attr("abs:href") : resolveUrl(base, a.attr("href"));
                    if (href != null && href.contains(path) && !href.equals(base)) {
                        nextLink = a;
                        break;
                    }
                }
            }
        }
        if (nextLink == null) {
            return null;
        }
        String href = nextLink.hasAttr("abs:href") ? nextLink.attr("abs:href") : nextLink.attr("href");
        if (href == null || href.isBlank()) {
            return null;
        }
        if (!href.startsWith("http")) {
            try {
                URL baseUrl = new URI(base).toURL();
                href = new URL(baseUrl, href).toExternalForm();
            } catch (Exception e) {
                logger.warn("Could not resolve next page href: {}", href, e);
                return null;
            }
        }
        if (href.equals(base)) {
            return null;
        }
        sleep(500);
        int timeout = Utils.getConfigInteger("download.timeout", 60000);
        return Jsoup.connect(href)
                .userAgent(AbstractRipper.USER_AGENT)
                .cookies(getUmdCookies())
                .timeout(timeout)
                .get();
    }

    @Override
    protected List<String> getURLsFromPage(Document page) throws UnsupportedEncodingException, URISyntaxException {
        Set<String> urls = new LinkedHashSet<>();
        String baseUrl = page.location();
        boolean isProfileAlbum = isProfileAlbumUrl(this.url);

        // On profile album pages, prefer images inside likely album/gallery containers
        Elements roots = isProfileAlbum
                ? page.select(".album-photos, .photo-album, .gallery-photos, .user-photos, .photos-grid, [data-album-id], main, .content-area, .profile-content")
                : page.select("body");
        if (roots.isEmpty()) {
            roots = page.select("body");
        }

        for (Element root : roots) {
            collectMediaUrlsFromElement(root, baseUrl, urls);
        }
        // Also run over full page so we don't miss images outside those containers
        if (isProfileAlbum) {
            collectMediaUrlsFromElement(page.body(), baseUrl, urls);
        }
        // Profile album images may be in script/JSON or lazy-loaded; extract from raw HTML
        if (isProfileAlbum || urls.isEmpty()) {
            collectMediaUrlsFromPageHtml(page.html(), urls);
        }
        // For profile albums, prefer full-size image when we have a thumb URL
        if (isProfileAlbum) {
            Set<String> preferred = new LinkedHashSet<>();
            for (String u : urls) {
                if (u == null) {
                    continue;
                }
                if (u.contains("/thumbs/")) {
                    String full = u.replace("/thumbs/", "/");
                    if (!full.equals(u) && isMediaUrl(full)) {
                        preferred.add(full);
                        continue;
                    }
                }
                preferred.add(u);
            }
            urls = preferred;
        }

        return new ArrayList<>(urls);
    }

    /** Extract UMD media URLs from raw HTML/script (e.g. profile album data in script tags). */
    private void collectMediaUrlsFromPageHtml(String html, Set<String> urls) {
        if (html == null || html.isEmpty()) {
            return;
        }
        // Normalize JSON-escaped URLs (e.g. https:\/\/mucky.umd.net\/media\/...) so regex can match
        String normalized = html.replace("\\/", "/");
        Matcher m = UMD_PHOTO_URL_IN_PAGE.matcher(normalized);
        while (m.find()) {
            String u = m.group(0);
            if (isMediaUrl(u)) {
                urls.add(u);
            }
        }
    }

    private static boolean isProfileAlbumUrl(URL url) {
        if (url == null) {
            return false;
        }
        String path = url.getPath();
        return path != null && path.toLowerCase().contains("/profile/")
                && path.toLowerCase().contains("/section/photos/album/");
    }

    private void collectMediaUrlsFromElement(Element root, String baseUrl, Set<String> urls) {
        for (Element img : root.select("img")) {
            addUrlFromAttr(img, "src", baseUrl, urls);
            addUrlFromAttr(img, "data-src", baseUrl, urls);
            addUrlFromAttr(img, "data-lazy-src", baseUrl, urls);
            addUrlFromAttr(img, "data-original", baseUrl, urls);
            addUrlFromAttr(img, "data-url", baseUrl, urls);
            addUrlFromAttr(img, "data-full-url", baseUrl, urls);
            addUrlFromAttr(img, "data-image", baseUrl, urls);
            addFromSrcset(img, baseUrl, urls);
        }
        for (Element a : root.select("a[href]")) {
            addUrlFromAttr(a, "href", baseUrl, urls);
        }
        for (Element source : root.select("source[src], video source[src]")) {
            addUrlFromAttr(source, "src", baseUrl, urls);
        }
        for (Element el : root.select("[data-src], [data-image-url], [data-full]")) {
            addUrlFromAttr(el, "data-src", baseUrl, urls);
            addUrlFromAttr(el, "data-image-url", baseUrl, urls);
            addUrlFromAttr(el, "data-full", baseUrl, urls);
        }
    }

    private void addUrlFromAttr(Element el, String attr, String baseUrl, Set<String> urls) {
        if (!el.hasAttr(attr)) {
            return;
        }
        String val = el.attr(attr);
        if (val == null || val.isBlank()) {
            return;
        }
        String abs = el.hasAttr("abs:" + attr) ? el.attr("abs:" + attr) : resolveUrl(baseUrl, val);
        if (isMediaUrl(abs)) {
            urls.add(abs);
        }
    }

    private void addFromSrcset(Element img, String baseUrl, Set<String> urls) {
        for (String attr : new String[] { "srcset", "data-srcset" }) {
            if (!img.hasAttr(attr)) {
                continue;
            }
            String srcset = img.attr(attr);
            if (srcset == null || srcset.isBlank()) {
                continue;
            }
            for (String entry : srcset.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String urlPart = trimmed.split("\\s+")[0].trim();
                String abs = urlPart.startsWith("http") ? urlPart : resolveUrl(baseUrl, urlPart);
                if (isMediaUrl(abs)) {
                    urls.add(abs);
                }
            }
        }
    }

    private static boolean isMediaUrl(String url) {
        if (url == null || url.isBlank() || url.startsWith("data:") || url.startsWith("javascript:")) {
            return false;
        }

        // Direct UMD download endpoints (used for videos) often have no file extension,
        // e.g. https://mucky.umd.net/download/... – treat them as media.
        try {
            URL u = new URL(url);
            String host = u.getHost().toLowerCase();
            String path = u.getPath();
            if (path != null && path.toLowerCase().contains("/download/")
                    && (host.endsWith("umd.net") || host.endsWith("mucky.umd.net"))) {
                return !isSiteChromeOrUnwanted(url);
            }
        } catch (Exception ignored) {
            // fall through to extension-based checks
        }

        return (IMAGE_EXT.matcher(url).matches() || VIDEO_EXT.matcher(url).matches())
                && !isSiteChromeOrUnwanted(url);
    }

    /** Exclude site UI, avatars, sidebar content, and tracking links so we only keep thread/post media. */
    private static boolean isSiteChromeOrUnwanted(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return true;
        }
        try {
            String path = new URL(urlString).getPath();
            if (path == null) {
                return false;
            }
            String p = path.toLowerCase();
            return p.contains("/images/")           // logos, icons, buttons
                    || p.contains("/templates/")   // template assets
                    || p.contains("/media/user_icons/")   // avatars
                    || p.contains("/media/site_thumbs/")  // sidebar thumbs
                    || p.contains("/media/smileys/")     // emoji
                    || p.contains("/media/subtracts/")   // sidebar banners/teasers
                    || p.contains("/media/calendar/")     // calendar thumbs
                    || p.contains("/go/");                // tracking/redirect links
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveUrl(String baseUrl, String relative) {
        if (relative == null || relative.isBlank()) {
            return "";
        }
        try {
            return new URL(new URL(baseUrl), relative).toExternalForm();
        } catch (Exception e) {
            return relative;
        }
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), getUmdCookies());
    }
}
