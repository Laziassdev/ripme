package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
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
 * Supports: forums (/forums/forum/...), groups (/groups), pic archive (/picarchive), gallery (/gallery).
 * Cookies are read from Firefox (if logged in there) or from cookies.umd.net in rip.properties.
 */
public class UmdRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(UmdRipper.class);

    private Map<String, String> umdCookiesCache;

    private static final Pattern IMAGE_EXT = Pattern.compile(
            ".*\\.(jpe?g|png|gif|webp|bmp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_EXT = Pattern.compile(
            ".*\\.(mp4|webm|mov|avi|mkv)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

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
        return path.startsWith("/forums/") || path.equals("/groups") || path.startsWith("/groups/")
                || path.equals("/picarchive") || path.startsWith("/picarchive")
                || path.equals("/gallery") || path.startsWith("/gallery");
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

        // img with src
        for (Element img : page.select("img[src]")) {
            String src = img.absUrl("src");
            if (isMediaUrl(src)) {
                urls.add(src);
            }
        }
        // Lazy-loaded images
        for (Element img : page.select("img[data-src]")) {
            String src = img.attr("abs:data-src");
            if (src.isEmpty()) {
                src = resolveUrl(baseUrl, img.attr("data-src"));
            }
            if (isMediaUrl(src)) {
                urls.add(src);
            }
        }
        for (Element img : page.select("img[data-lazy-src]")) {
            String src = img.attr("abs:data-lazy-src");
            if (src.isEmpty()) {
                src = resolveUrl(baseUrl, img.attr("data-lazy-src"));
            }
            if (isMediaUrl(src)) {
                urls.add(src);
            }
        }
        // Links that point directly to media
        for (Element a : page.select("a[href]")) {
            String href = a.hasAttr("abs:href") ? a.attr("abs:href") : resolveUrl(baseUrl, a.attr("href"));
            if (isMediaUrl(href)) {
                urls.add(href);
            }
        }
        // Video sources
        for (Element source : page.select("source[src], video source[src]")) {
            String src = source.hasAttr("abs:src") ? source.attr("abs:src") : resolveUrl(baseUrl, source.attr("src"));
            if (isMediaUrl(src)) {
                urls.add(src);
            }
        }

        return new ArrayList<>(urls);
    }

    private static boolean isMediaUrl(String url) {
        if (url == null || url.isBlank() || url.startsWith("data:") || url.startsWith("javascript:")) {
            return false;
        }
        return IMAGE_EXT.matcher(url).matches() || VIDEO_EXT.matcher(url).matches();
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
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), null);
    }
}
