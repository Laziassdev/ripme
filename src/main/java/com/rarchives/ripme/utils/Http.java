package com.rarchives.ripme.utils;

import com.rarchives.ripme.ripper.AbstractRipper;
import org.apache.commons.lang.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.Random;

/**
 * Wrapper around the Jsoup connection methods.
 * <p>
 * Benefit is retry logic.
 */
public class Http {

    private static final int TIMEOUT = Utils.getConfigInteger("page.timeout", 5 * 1000);
    private static final Logger logger = LogManager.getLogger(Http.class);

    private int retries;
    private int retrySleep = 0;
    private final String url;
    private Connection connection;

    // Constructors
    public Http(String url) {
        this.url = url;
        defaultSettings();
    }

    private Http(URL url) {
        this.url = url.toExternalForm();
        defaultSettings();
    }

    public static Http url(String url) {
        return new Http(url);
    }

    public static Http url(URL url) {
        return new Http(url);
    }

    private void defaultSettings() {
        this.retries = Utils.getConfigInteger("download.retries", 3);
        this.retrySleep = Utils.getConfigInteger("download.retry.sleep", 5000);
        connection = Jsoup.connect(this.url);
        connection.userAgent(AbstractRipper.USER_AGENT);
        connection.method(Method.GET);
        connection.timeout(TIMEOUT);
        connection.maxBodySize(0);

        // Extract cookies from config entry:
        // Example config entry:
        // cookies.reddit.com = reddit_session=<value>; other_cookie=<value>
        connection.cookies(cookiesForURL(this.url));
    }

    private Map<String, String> cookiesForURL(String u) {
        Map<String, String> cookiesParsed = new HashMap<>();

        String cookieDomain = "";
        try {
            URL parsed = new URI(u).toURL();
            String cookieStr = "";

            String[] parts = parsed.getHost().split("\\.");

            // if url is www.reddit.com, we should also use cookies from reddit.com;
            // this rule is applied for all subdomains (for all rippers); e.g. also
            // old.reddit.com, new.reddit.com
            while (parts.length > 1) {
                String domain = String.join(".", parts);
                // Try to get cookies for this host from config
                logger.info("Trying to load cookies from config for " + domain);
                cookieStr = Utils.getConfigString("cookies." + domain, "");
                if (!cookieStr.equals("")) {
                    cookieDomain = domain;
                    // we found something, start parsing
                    break;
                }
                parts = (String[]) ArrayUtils.remove(parts, 0);
            }

            if (!cookieStr.equals("")) {
                cookiesParsed = RipUtils.getCookiesFromString(cookieStr.trim());
            }
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warn("Parsing url " + u + " while getting cookies", e);
        }

        if (cookiesParsed.size() > 0) {
            logger.info("Cookies for " + cookieDomain + " have been added to this request");
        }

        return cookiesParsed;
    }

    // Setters
    public Http timeout(int timeout) {
        connection.timeout(timeout);
        return this;
    }

    public Http ignoreContentType() {
        connection.ignoreContentType(true);
        return this;
    }

    public Http referrer(String ref) {
        connection.referrer(ref);
        return this;
    }

    public Http referrer(URL ref) {
        return referrer(ref.toExternalForm());
    }

    public Http userAgent(String ua) {
        connection.userAgent(ua);
        return this;
    }

    public Http retries(int tries) {
        this.retries = tries;
        return this;
    }

    public Http header(String name, String value) {
        connection.header(name, value);
        return this;
    }

    public Http cookies(Map<String, String> cookies) {
        connection.cookies(cookies);
        return this;
    }

    public Http data(Map<String, String> data) {
        connection.data(data);
        return this;
    }

    public Http data(String name, String value) {
        Map<String, String> data = new HashMap<>();
        data.put(name, value);
        return data(data);
    }

    public Http method(Method method) {
        connection.method(method);
        return this;
    }

    // Getters
    public Connection connection() {
        return connection;
    }

    public Document get() throws IOException {
        connection.method(Method.GET);
        return response().parse();
    }

    public Document post() throws IOException {
        connection.method(Method.POST);
        return response().parse();
    }

    public JSONObject getJSON() throws IOException {
        ignoreContentType();
        String jsonString = response().body();
        return new JSONObject(jsonString);
    }

    public JSONArray getJSONArray() throws IOException {
        ignoreContentType();
        String jsonArray = response().body();
        return new JSONArray(jsonArray);
    }

    public static String getWith429Retry(URL url, int maxRetries, int baseDelaySeconds, String userAgent) throws IOException {
    int retries = 0;
    int maxDelaySeconds = 600; // Cap max wait to 10 minutes
    Random random = new Random();
    Logger logger = LogManager.getLogger(Http.class);

    while (retries <= maxRetries) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();

            if (responseCode == 429) {
                if (retries < maxRetries) {
                    String retryAfter = connection.getHeaderField("Retry-After");
                    long waitTime;

                    if (retryAfter != null) {
                        try {
                            waitTime = Long.parseLong(retryAfter);
                            logger.warn("[!] 429 Too Many Requests - retrying in " + waitTime + "s (attempt " + (retries + 1) + ") (using Retry-After)");
                        } catch (NumberFormatException e) {
                            waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                            logger.warn("[!] Invalid Retry-After format - fallback retrying in " + waitTime + "s (attempt " + (retries + 1) + ")");
                        }
                    } else {
                        waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                        int jitter = random.nextInt(5); // 0–4s jitter
                        waitTime += jitter;
                        logger.warn("[!] 429 Too Many Requests - retrying in " + waitTime + "s (attempt " + (retries + 1) + ") (no Retry-After header)");
                    }

                    Utils.sleep(waitTime * 1000L);
                    retries++;
                    continue;
                } else {
                    // After final normal retry, wait 10 minutes and try once more
                    logger.warn("[!] Max retries reached. Waiting 10 minutes before one final attempt...");
                    Utils.sleep(600_000); // 10 minutes in ms
                    retries++; // Ensure we exit loop if this fails
                    continue;
                }
            }

            if (responseCode >= 400) {
                throw new IOException("HTTP error: " + responseCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                if (retries < maxRetries) {
                    long waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                    int jitter = random.nextInt(5);
                    waitTime += jitter;
                    logger.warn("[!] IOException suggests 429 - retrying in " + waitTime + "s (attempt " + (retries + 1) + ")");
                    Utils.sleep(waitTime * 1000L);
                    retries++;
                } else {
                    logger.warn("[!] Max retries hit from IOException. Waiting 10 minutes before final attempt...");
                    Utils.sleep(600_000);
                    retries++;
                }
            } else {
                throw e;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    throw new IOException("Exceeded max retries (including final attempt) for GET " + url);
    }

    public Response response() throws IOException {
        Response response;
        IOException lastException = null;
        int retries = this.retries;
        while (--retries >= 0) {
            try {
                response = connection.execute();
                return response;
            } catch (IOException e) {
                // Warn users about possibly fixable permission error
                if (e instanceof org.jsoup.HttpStatusException) {
                    HttpStatusException ex = (HttpStatusException) e;

                    // These status codes might indicate missing cookies
                    //     401 Unauthorized
                    //     403 Forbidden

                    int status = ex.getStatusCode();
                    if (status == 401 || status == 403) {
                        throw new IOException("Failed to load " + url + ": Status Code " + status + ". You might be able to circumvent this error by setting cookies for this domain", e);
                    }
                    if (status == 404 || status == 410) {
                        throw new IOException("File not found " + url + ": Status Code " + status + ". ", e);
                    }
                }

                if (retrySleep > 0 && retries >= 0) {
                    logger.warn("Error while loading " + url + " waiting "+ retrySleep + " ms before retrying.", e);
                    Utils.sleep(retrySleep);
                } else {
                    logger.warn("Error while loading " + url, e);
                }
                lastException = e;
            }
        }
        throw new IOException("Failed to load " + url + " after " + this.retries + " attempts", lastException);
    }

    public static void SSLVerifyOff() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HostnameVerifier allHostsValid = (hostname, session) -> true;
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (Exception e) {
            logger.error("ignoreSSLVerification() failed.");
            logger.error(e.getMessage());
        }
    }

    public static URL followRedirectsWithRetry(URL originalUrl, int maxRetries, int baseDelaySeconds, String userAgent) throws IOException {
        return followRedirectsWithRetry(originalUrl, maxRetries, baseDelaySeconds, userAgent, "*/*");
    }

    public static URL followRedirectsWithRetry(URL originalUrl, int maxRetries, int baseDelaySeconds, String userAgent, String acceptHeader) throws IOException {
        int retries = 0;
        int maxDelaySeconds = 600;
        Random random = new Random();
        URL currentUrl = originalUrl;

        while (retries <= maxRetries) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) currentUrl.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", acceptHeader);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();

                if (responseCode == 429) {
                    String retryAfter = connection.getHeaderField("Retry-After");
                    long waitTime;

                    if (retryAfter != null) {
                        try {
                            waitTime = Long.parseLong(retryAfter);
                        } catch (NumberFormatException e) {
                            waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                        }
                    } else {
                        waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                        waitTime += random.nextInt(5); // jitter
                    }

                    logger.warn("[429] Too Many Requests - waiting {}s before retry (attempt {}/{})", waitTime, retries + 1, maxRetries);
                    Utils.sleep(waitTime * 1000L);
                    retries++;
                    continue;
                }

                if (responseCode == 301 || responseCode == 302 || responseCode == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location != null) {
                        currentUrl = new URL(location);
                        logger.debug("Redirected to {}", currentUrl);
                        continue; // follow the next redirect
                    }
                }

                if (responseCode >= 400) {
                    throw new IOException("HTTP error: " + responseCode);
                }

                return currentUrl;

            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("429") && retries < maxRetries) {
                    long waitTime = Math.min(baseDelaySeconds * (1L << retries), maxDelaySeconds);
                    waitTime += random.nextInt(5);
                    logger.warn("IOException suggests 429 - retrying in {}s (attempt {}/{})", waitTime, retries + 1, maxRetries);
                    Utils.sleep(waitTime * 1000L);
                    retries++;
                } else {
                    throw e;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        throw new IOException("Exceeded max retries while resolving redirects for " + originalUrl);
    }

    public static void undoSSLVerifyOff() {
        try {
            // Reset to the default SSL socket factory and hostname verifier
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, null, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        } catch (Exception e) {
            logger.error("undoSSLVerificationIgnore() failed.");
            logger.error(e.getMessage());
        }
    }
}
