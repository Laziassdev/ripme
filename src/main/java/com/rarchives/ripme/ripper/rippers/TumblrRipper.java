
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
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AlbumRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class TumblrRipper extends AlbumRipper {

    private static final Logger logger = LogManager.getLogger(TumblrRipper.class);

    int index = 1;
    int downloadCounter = 0;
    int maxDownloads = Utils.getConfigInteger("maxdownloads", -1);

    private static final String DOMAIN = "tumblr.com", HOST = "tumblr";

    private enum ALBUM_TYPE {
        SUBDOMAIN, TAG, POST, LIKED
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

        if (useDefaultApiKey || Utils.getConfigString(TUMBLR_AUTH_CONFIG_KEY).isEmpty()) {
            logger.info("Using default api key: " + API_KEY);
            return API_KEY;
        } else {
            return Utils.getConfigString(TUMBLR_AUTH_CONFIG_KEY);
        }
    }

    private static String pickRandomApiKey() {
        final List<String> APIKEYS = Arrays.asList(
            "JFNLu3CbINQjRdUvZibXW9VpSEVYYtiPJ86o8YmvgLZIoKyuNX",
            "FQrwZMCxVnzonv90rgNUJcAk4FpnoS0mYuSuGYqIpM2cFgp9L4",
            "qpdkY6nMknksfvYAhf2xIHp0iNRLkMlcWShxqzXyFJRxIsZ1Zz"
        );
        return APIKEYS.get(new Random().nextInt(APIKEYS.size()));
    }

    public TumblrRipper(URL url) throws IOException {
        super(url);
        if (getApiKey() == null) {
            throw new IOException("Missing Tumblr API key");
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
        return url;
    }

    @Override
    public void rip() throws IOException {
        int offset = 0;

        while (!isStopped()) {
            String apiUrl = getTumblrApiUrl(offset);
            JSONObject json;
            try {
                json = Http.getWith429Retry(apiUrl, 5, 5).getJSON();
            } catch (HttpStatusException e) {
                if (e.getStatusCode() == 429) {
                    sendUpdate(STATUS.DOWNLOAD_ERRORED, "Tumblr rate limit exceeded");
                    break;
                }
                throw e;
            }

            JSONArray posts = json.getJSONObject("response").getJSONArray("posts");
            if (posts.isEmpty()) break;

            for (int i = 0; i < posts.length(); i++) {
                if (isStopped() || (maxDownloads > 0 && downloadCounter >= maxDownloads)) break;

                JSONObject post = posts.getJSONObject(i);
                if (post.has("video_url")) {
                    URL url = new URI(post.getString("video_url")).toURL();
                    addURLToDownload(url, getPrefix(index));
                    downloadCounter++;
                } else if (post.has("photos")) {
                    JSONArray photos = post.getJSONArray("photos");
                    for (int j = 0; j < photos.length(); j++) {
                        URL url = new URI(photos.getJSONObject(j).getJSONObject("original_size").getString("url")).toURL();
                        addURLToDownload(url, getPrefix(index));
                        downloadCounter++;
                        if (maxDownloads > 0 && downloadCounter >= maxDownloads) break;
                    }
                }
                index++;
            }

            offset += 20;
        }

        waitForThreads();
    }

    private String getTumblrApiUrl(int offset) {
        return "https://api.tumblr.com/v2/blog/" + subdomain + "/posts?api_key=" + getApiKey() + "&offset=" + offset;
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9\-.]+)");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.find()) {
            this.albumType = ALBUM_TYPE.SUBDOMAIN;
            this.subdomain = m.group(1);
            return this.subdomain;
        }
        throw new MalformedURLException("Unrecognized Tumblr URL format");
    }

    private String getPrefix(int i) {
        return String.format("%03d_", i);
    }
}
