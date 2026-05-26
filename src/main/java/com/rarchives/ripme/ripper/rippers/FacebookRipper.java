package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile("https?:\\/\\/[^\"'\\s<>]+\\.(?:jpe?g|png|webp|gif|mp4)(?:\\?[^\"'\\s<>]*)?", Pattern.CASE_INSENSITIVE);

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
        Http request = Http.url(this.url).userAgent(USER_AGENT).referrer("https://www.facebook.com/").ignoreContentType();
        if (!facebookCookies.isEmpty()) {
            request.cookies(facebookCookies);
            if (facebookCookies.containsKey("c_user")) {
                request.header("X-Requested-With", "XMLHttpRequest");
            }
        }
        String body = request.get().html();
        if (body == null || body.isBlank()) {
            throw new IOException("Facebook returned an empty response");
        }
        return Jsoup.parse(body, this.url.toExternalForm());
    }

    @Override
    protected List<String> getURLsFromPage(Document page) throws UnsupportedEncodingException {
        Set<String> found = new LinkedHashSet<>();

        addMetaMedia(page, found, "meta[property=og:image]");
        addMetaMedia(page, found, "meta[property=og:image:secure_url]");
        addMetaMedia(page, found, "meta[property=og:video]");
        addMetaMedia(page, found, "meta[property=og:video:secure_url]");
        addMetaMedia(page, found, "meta[property=twitter:image]");
        addMetaMedia(page, found, "meta[property=twitter:player:stream]");

        String html = page.html();
        Matcher m = MEDIA_URL_PATTERN.matcher(html);
        while (m.find()) {
            found.add(unescapeJsonUrl(m.group()));
        }

        if (found.isEmpty()) {
            throw new IllegalStateException("No downloadable Facebook media URLs were discovered on page");
        }

        return new ArrayList<>(found);
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

    private String unescapeJsonUrl(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\u0025", "%")
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
