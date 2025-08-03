package com.rarchives.ripme.ripper.rippers;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.ripper.AbstractRipper;

/**
 * <a href="https://coomer.st/api/schema">See this link for the API schema</a>.
 */
public class CoomerPartyRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(CoomerPartyRipper.class);

    private String IMG_URL_BASE = "https://img.coomer.st";
    private String VID_URL_BASE = "https://c1.coomer.st";
    private static final Pattern IMG_PATTERN = Pattern.compile("^.*\\.(jpg|jpeg|png|gif|apng|webp|tif|tiff)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VID_PATTERN = Pattern.compile("^.*\\.(webm|mp4|m4v)$", Pattern.CASE_INSENSITIVE);

    // just so we can return a JSONObject from getFirstPage
    private static final String KEY_WRAPPER_JSON_ARRAY = "array";

    private static final String KEY_FILE = "file";
    private static final String KEY_PATH = "path";
    private static final String KEY_ATTACHMENTS = "attachments";

    // Posts Request Endpoint
    private String POSTS_ENDPOINT = "https://coomer.st/api/v1/%s/user/%s?o=%d";

    // Pagination is strictly 50 posts per page, per API schema.
    private Integer pageCount = 0;
    private static final Integer postCount = 50;

    // "Service" of the page to be ripped: Onlyfans, Fansly, Candfans
    private final String service;

    // Username of the page to be ripped
    private final String user;

    // Current domain being used for API requests and media URLs
    private String domain;

    private final int maxDownloads = Utils.getConfigInteger("maxdownloads", -1);
    private int downloadCounter = 0;
    private int queuedDownloadCounter = 0;



    public CoomerPartyRipper(URL url) throws IOException {
        super(url);
        List<String> pathElements = Arrays.stream(url.getPath().split("/"))
                .filter(element -> !element.isBlank())
                .collect(Collectors.toList());

        service = pathElements.get(0);
        user = pathElements.get(2);

        if (service == null || user == null || service.isBlank() || user.isBlank()) {
            logger.warn("service=" + service + ", user=" + user);
            throw new MalformedURLException("Invalid coomer.party URL: " + url);
        }
        logger.debug("Parsed service=" + service + " and user=" + user + " from " + url);

        domain = url.getHost();
        setDomain(domain);
    }

    @Override
    protected String getDomain() {
        return "coomer.party";
    }

    @Override
    public String getHost() {
        return "coomer.party";
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost();
        return host.endsWith("coomer.party") || host.endsWith("coomer.su") || host.endsWith("coomer.st");
    }

    @Override
    public String getGID(URL url) {
        return Utils.filesystemSafe(String.format("%s_%s", service, user));
    }

    private void setDomain(String newDomain) {
        domain = newDomain;
        IMG_URL_BASE = "https://img." + newDomain;
        VID_URL_BASE = "https://c1." + newDomain;
        POSTS_ENDPOINT = "https://" + newDomain + "/api/v1/%s/user/%s?o=%d";
    }

    private JSONObject getJsonPostsForOffset(Integer offset) throws IOException {
        Set<String> domainsToTry = new LinkedHashSet<>();
        domainsToTry.add(domain);
        domainsToTry.add("coomer.su");
        domainsToTry.add("coomer.st");

        IOException lastException = null;
        for (String dom : domainsToTry) {
            setDomain(dom);
            String apiUrl = String.format(POSTS_ENDPOINT, service, user, offset);
            try {
                String jsonArrayString = Http.url(apiUrl)
                        .ignoreContentType()
                        .response()
                        .body();

                logger.debug("Raw JSON from API for offset " + offset + ": " + jsonArrayString);

                JSONArray jsonArray = new JSONArray(jsonArrayString);

                if (jsonArray.isEmpty()) {
                    logger.warn("No posts found at offset " + offset + " for user: " + user);
                }

                JSONObject wrapperObject = new JSONObject();
                wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, jsonArray);
                return wrapperObject;
            } catch (IOException e) {
                lastException = e;
                logger.warn("Failed to fetch posts from {}: {}", apiUrl, e.getMessage());
            }
        }
        throw lastException;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        JSONObject page = getJsonPostsForOffset(0);
        JSONArray posts = page.getJSONArray(KEY_WRAPPER_JSON_ARRAY);
        if (posts.length() == 0) {
            logger.warn("No posts returned for " + getURL());
            return null; // graceful exit
        }
        return page;
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (maxDownloads > 0 && queuedDownloadCounter >= maxDownloads) {
            logger.info("Reached maxdownloads limit of " + maxDownloads + ". Stopping.");
            sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, "Reached maxdownloads limit of " + maxDownloads + ". Stopping.");
            return null;
        }

        pageCount++;
        int offset = pageCount * postCount;

        JSONObject nextPage = getJsonPostsForOffset(offset);
        JSONArray posts = nextPage.getJSONArray(KEY_WRAPPER_JSON_ARRAY);

        if (posts.isEmpty()) {
            logger.info("No more posts found at offset " + offset + ", ending rip.");
            return null;
        }

        return nextPage;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        JSONArray posts = json.getJSONArray(KEY_WRAPPER_JSON_ARRAY);
        ArrayList<String> urls = new ArrayList<>();

        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.getJSONObject(i);
            logger.debug("Processing post: {}", post.toString());

            int before = urls.size();

            try {
                pullFileUrl(post, urls);
            } catch (Exception e) {
                logger.warn("Error pulling file URL for post {}: {}", i, e.getMessage());
            }

            try {
                pullAttachmentUrls(post, urls);
            } catch (Exception e) {
                logger.warn("Error pulling attachments for post {}: {}", i, e.getMessage());
            }

            int added = urls.size() - before;
            if (added == 0) {
                logger.debug("Post {} yielded no URLs", i);
            }

            queuedDownloadCounter += added;
        }

        if (urls.isEmpty()) {
            logger.warn("No downloadable URLs found in JSON block of {} posts", posts.length());
        }

        return urls;
    }


    @Override
    protected void downloadURL(URL url, int index) {
        try {
            URL resolvedUrl = Http.followRedirectsWithRetry(url, 5, 5, AbstractRipper.USER_AGENT);
            addURLToDownload(resolvedUrl, getPrefix(index));
        } catch (IOException e) {
            logger.error("Failed to resolve or download redirect URL {}: {}", url, e.getMessage());
        }
    }

    @Override
    public void downloadCompleted(URL url, java.nio.file.Path saveAs) {
        super.downloadCompleted(url, saveAs);
        downloadCounter++;

        if (maxDownloads > 0 && downloadCounter >= maxDownloads) {
            logger.info("Completed {} of max {} downloads. Stopping rip.", downloadCounter, maxDownloads);
            sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, "Reached maxdownloads limit of " + maxDownloads + ". Stopping.");

        }
    }

    private String buildMediaUrl(String base, String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.startsWith("/data/") && !path.startsWith("/thumbnail/") && !path.startsWith("/original/")) {
            path = "/data" + path;
        }
        return base + path;
    }

    private void pullFileUrl(JSONObject post, ArrayList<String> results) {
        if (post == null) {
            logger.warn("Attempted to parse null post object");
            return;
        }

        if (!post.has(KEY_FILE)) {
            logger.debug("Post missing 'file' object, skipping.");
            return;
        }

        try {
            JSONObject file = post.getJSONObject(KEY_FILE);

            if (!file.has(KEY_PATH)) {
                logger.debug("File object missing 'path', skipping.");
                return;
            }

            String path = file.getString(KEY_PATH).trim();

            if (path.isEmpty()) {
                logger.debug("File path is empty, skipping.");
                return;
            }

            String url;
            if (path.startsWith("http")) {
                url = path;
                if (!isImage(url) && !isVideo(url)) {
                    logger.warn("Unsupported media extension in path: " + path);
                    return;
                }
            } else if (isImage(path)) {
                url = buildMediaUrl(IMG_URL_BASE, path);
            } else if (isVideo(path)) {
                url = buildMediaUrl(VID_URL_BASE, path);
            } else {
                logger.warn("Unsupported media extension in path: " + path);
                return;
            }

            results.add(url);

        } catch (JSONException e) {
            logger.error("Error parsing 'file' object from post: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error in pullFileUrl: " + e.getMessage(), e);
        }
    }

    private void pullAttachmentUrls(JSONObject post, ArrayList<String> results) {
        if (post == null || !post.has(KEY_ATTACHMENTS)) {
            return;
        }

        try {
            JSONArray attachments = post.getJSONArray(KEY_ATTACHMENTS);

            for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.optJSONObject(i);
                if (attachment != null) {
                    pullFileUrl(attachment, results);
                } else {
                    logger.debug("Attachment at index " + i + " is not a valid JSONObject");
                }
            }

        } catch (JSONException e) {
            logger.error("Error parsing 'attachments' array: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error in pullAttachmentUrls: " + e.getMessage(), e);
        }
    }

    private boolean isImage(String path) {
        Matcher matcher = IMG_PATTERN.matcher(path);
        return matcher.matches();
    }

    private boolean isVideo(String path) {
        Matcher matcher = VID_PATTERN.matcher(path);
        return matcher.matches();
    }
}
