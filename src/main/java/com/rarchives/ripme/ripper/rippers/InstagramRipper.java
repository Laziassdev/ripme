package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class InstagramRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(InstagramRipper.class);
    private static final int WAIT_TIME = 2000; // 2 seconds between requests

    private String idString;
    private Map<String, String> cookies = new HashMap<>();
    private String endCursor = null;
    private boolean hasNextPage = true;
    private boolean fallbackToGraphQL = false;
    private String csrftoken = null;

    public InstagramRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    protected String getDomain() {
        return "instagram.com";
    }

    @Override
    public String getHost() {
        return "instagram";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern pattern = Pattern.compile("https?://(?:www\\.)?instagram\\.com/(?<username>[^/?#]+)/?");
        Matcher matcher = pattern.matcher(url.toExternalForm());
        if (matcher.find()) {
            return matcher.group("username");
        }
        throw new MalformedURLException("Expected format: https://www.instagram.com/username/");
    }

    @Override
    public JSONObject getFirstPage() throws IOException {
        setAuthCookie();
        Document document = Http.url(url).cookies(cookies).response().parse();
        JSONObject jsonObject = getJsonObjectFromDoc(document);
        if (jsonObject == null) {
            logger.warn("Failed to parse sharedData, falling back to GraphQL");
            fallbackToGraphQL = true;
            String username = getGID(url);
            return getGraphQLUserPage(username, null);
        }
        JSONObject user = getJsonObjectByPath(jsonObject, "entry_data.ProfilePage[0].graphql.user");
        if (user.optBoolean("is_private") && user.optJSONObject("edge_owner_to_timeline_media") == null) {
            throw new IOException("This Instagram profile is private. Set a valid 'instagram.session_id' in rip.properties.");
        }
        idString = user.getString("id");
        JSONObject pageInfo = user.getJSONObject("edge_owner_to_timeline_media").getJSONObject("page_info");
        hasNextPage = pageInfo.getBoolean("has_next_page");
        endCursor = pageInfo.optString("end_cursor", null);
        return jsonObject;
    }

    @Override
    public JSONObject getNextPage(JSONObject json) throws IOException {
        if (!hasNextPage || idString == null) {
            return null;
        }
        String username = getGID(url);
        return getGraphQLUserPage(username, endCursor);
    }    private JSONObject getGraphQLUserPage(String username, String afterCursor) throws IOException {
        if (idString == null) {            String fullUrlUser = format("https://www.instagram.com/api/v1/users/web_profile_info/?username=%s", username);
            String rawProfile = Http.url(fullUrlUser)
                .cookies(cookies)
                .ignoreContentType()
                .userAgent("Instagram 76.0.0.15.395 Android (24/7.0; 640dpi; 1440x2560; samsung; SM-G930F; herolte; samsungexynos8890; en_US; 138226743)")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-ASBD-ID", "198387")
                .header("X-IG-WWW-Claim", "hmac.AR2oFTCuitCzXvttHXW3DD1kZLwzL7oaLyP-3JUDK_KJ5AIg")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", csrftoken)
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Origin", "https://www.instagram.com")
                .header("Connection", "keep-alive")
                .header("Referer", "https://www.instagram.com/")
                .get().body().text();
            JSONObject shared = new JSONObject(rawProfile);
            idString = shared.getJSONObject("data").getJSONObject("user").getString("id");
        }

        JSONObject variables = new JSONObject();
        variables.put("id", idString);
        variables.put("first", 12);
        if (afterCursor != null) {
            variables.put("after", afterCursor);
        }

        String queryHash = "c6809c9c025875ac6f02619eae97a80e";
        String encodedVariables = URLEncoder.encode(variables.toString(), StandardCharsets.UTF_8);
        String fullUrl = format("https://www.instagram.com/graphql/query/?query_hash=%s&variables=%s", queryHash, encodedVariables);        try {
            Thread.sleep(WAIT_TIME);
        } catch (InterruptedException e) {
            logger.error("[!] Interrupted while waiting to load next page", e);
        }

        String rawJson = Http.url(fullUrl)
            .cookies(cookies)
            .ignoreContentType()
            .userAgent("Instagram 76.0.0.15.395 Android (24/7.0; 640dpi; 1440x2560; samsung; SM-G930F; herolte; samsungexynos8890; en_US; 138226743)")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-ASBD-ID", "198387")
            .header("X-IG-WWW-Claim", "hmac.AR2oFTCuitCzXvttHXW3DD1kZLwzL7oaLyP-3JUDK_KJ5AIg")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-CSRFToken", csrftoken)
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Origin", "https://www.instagram.com")
            .header("Connection", "keep-alive")
            .header("Referer", url.toExternalForm())
            .get().body().text();

        if (!rawJson.trim().startsWith("{")) {
            logger.error("Expected JSON, but got HTML:\n{}", rawJson.length() > 500 ? rawJson.substring(0, 500) + "..." : rawJson);
            throw new IOException("Instagram GraphQL response is not valid JSON.");
        }

        return new JSONObject(rawJson);
    }    private void setAuthCookie() {
        String sessionId = Utils.getConfigString("instagram.session_id", null);
        if (sessionId != null) {
            cookies.put("sessionid", sessionId);
            cookies.put("ds_user_id", sessionId.split(":")[0]);
            cookies.put("ig_did", Utils.getConfigString("instagram.ig_did", java.util.UUID.randomUUID().toString()));
            csrftoken = Utils.getConfigString("instagram.csrftoken", java.util.UUID.randomUUID().toString().replace("-", ""));
            cookies.put("csrftoken", csrftoken);
            cookies.put("ig_nrcb", "1");
            cookies.put("mid", Utils.getConfigString("instagram.mid", java.util.UUID.randomUUID().toString()));
            cookies.put("rur", "\"CLN\\05449462557\\0541719022291:01f7994243c1ab77adce0376935b1cc4c1177ad4a39cb3391b72d778880560f3c88a7fc0\"");
        }
    }

    private JSONObject getJsonObjectFromDoc(Document document) {
        for (Element script : document.select("script[type=text/javascript]")) {
            String scriptText = script.data();
            if (scriptText.startsWith("window._sharedData")) {
                try {
                    String jsonText = scriptText.replaceAll("[^\\{]*([\\{].*})[^\\}]*", "$1");
                    return new JSONObject(jsonText);
                } catch (Exception e) {
                    logger.warn("Failed to parse embedded script JSON", e);
                }
            }
        }
        logger.error("No embedded JSON found in Instagram document");
        return null;
    }

    private JSONObject getJsonObjectByPath(JSONObject object, String key) {
        if (object == null) {
            throw new IllegalArgumentException("Null JSON object for path: " + key);
        }
        Pattern arrayPattern = Pattern.compile("(?<arr>.*)\\[(?<idx>\\d+)]");
        JSONObject result = object;
        for (String s : key.split("\\.")) {
            Matcher m = arrayPattern.matcher(s);
            result = m.matches() ?
                result.getJSONArray(m.group("arr")).getJSONObject(Integer.parseInt(m.group("idx"))) :
                result.getJSONObject(s);
        }
        return result;
    }

    @Override
    public List<String> getURLsFromJSON(JSONObject json) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray edges;
            if (fallbackToGraphQL) {
                edges = json.getJSONObject("data").getJSONObject("user").getJSONObject("edge_owner_to_timeline_media").getJSONArray("edges");
            } else {
                edges = getJsonObjectByPath(json, "entry_data.ProfilePage[0].graphql.user.edge_owner_to_timeline_media.edges").getJSONArray("edges");
            }
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                if (node.optBoolean("is_video", false)) {
                    if (node.has("video_url")) {
                        result.add(node.getString("video_url"));
                    }
                } else {
                    result.add(node.getString("display_url"));
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to extract media URLs", ex);
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", null, cookies);
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }
}
