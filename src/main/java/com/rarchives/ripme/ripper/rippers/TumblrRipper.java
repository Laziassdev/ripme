
package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AlbumRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

@SuppressWarnings("deprecation")
public class TumblrRipper extends AlbumRipper {

    private static final Logger logger = LogManager.getLogger(TumblrRipper.class);

    int index = 1;
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_SECONDS = 5;
    private static final String DOMAIN = "tumblr.com";
    private static final String HOST = "tumblr";

    private enum ALBUM_TYPE {
        SUBDOMAIN,
        TAG,
        POST,
        LIKED
    }

    private ALBUM_TYPE albumType;
    private String subdomain, tagName, postNumber;

    private static final String TUMBLR_AUTH_CONFIG_KEY = "tumblr.auth";
    private static boolean useDefaultApiKey = false;
    private static String API_KEY = null;

    public static String getApiKey() {
        if (isThisATest()) {
            return "UHpRFx16HFIRgQjtjJKgfVIcwIeb71BYwOQXTMtiCvdSEPjV7N";
        }
        if (API_KEY == null) {
            API_KEY = pickRandomApiKey();
        }
        String userDefined = Utils.getConfigString(TUMBLR_AUTH_CONFIG_KEY, "");
        if (useDefaultApiKey || userDefined.isEmpty()) {
            logger.info("Using default api key: " + API_KEY);
            return API_KEY;
        } else {
            logger.info("Using user-defined api key: " + userDefined);
            return userDefined;
        }
    }

    private static String pickRandomApiKey() {
        final List<String> APIKEYS = Arrays.asList("JFNLu3CbINQjRdUvZibXW9VpSEVYYtiPJ86o8YmvgLZIoKyuNX",
                "FQrwZMCxVnzonv90rgNUJcAk4FpnoS0mYuSuGYqIpM2cFgp9L4",
                "qpdkY6nMknksfvYAhf2xIHp0iNRLkMlcWShxqzXyFJRxIsZ1Zz");
        return APIKEYS.get(new Random().nextInt(APIKEYS.size()));
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

    @Override
    public String getGID(URL url) throws MalformedURLException {
        final String DOMAIN_REGEX = "^https?://([a-zA-Z0-9\\-.]+)";
        Pattern p;
        Matcher m;

        p = Pattern.compile(DOMAIN_REGEX + "/?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.SUBDOMAIN;
            this.subdomain = m.group(1);
            return this.subdomain;
        }
        throw new MalformedURLException("Unsupported Tumblr URL format");
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    public void rip() throws IOException {
        int offset = 0;
        boolean stop = false;

        while (!stop && index <= Utils.getConfigInteger("download.max_downloads", 100)) {
            String apiUrl = "https://api.tumblr.com/v2/blog/" + subdomain + "/posts?api_key=" + getApiKey() + "&offset=" + offset;
            logger.info("Fetching: " + apiUrl);
            sendUpdate(STATUS.LOADING_RESOURCE, apiUrl);

            JSONObject json;
            try {
                json = new JSONObject(Http.getWith429Retry(new URL(apiUrl), MAX_RETRIES, RETRY_DELAY_SECONDS, null));
            } catch (IOException e) {
                logger.error("Failed to retrieve Tumblr API response", e);
                break;
            }

            JSONArray posts = json.getJSONObject("response").getJSONArray("posts");
            if (posts.isEmpty()) break;

            for (int i = 0; i < posts.length() && index <= Utils.getConfigInteger("download.max_downloads", 100); i++) {
                JSONObject post = posts.getJSONObject(i);
                if (post.has("photos")) {
                    JSONArray photos = post.getJSONArray("photos");
                    for (int j = 0; j < photos.length(); j++) {
                        String url = photos.getJSONObject(j).getJSONObject("original_size").getString("url");
                        addURLToDownload(new URL(url), "");
                        index++;
                    }
                } else if (post.has("video_url")) {
                    String videoUrl = post.getString("video_url");
                    addURLToDownload(new URL(videoUrl), "");
                    index++;
                }
            }

            offset += 20;
        }

        waitForThreads();
    }
}
