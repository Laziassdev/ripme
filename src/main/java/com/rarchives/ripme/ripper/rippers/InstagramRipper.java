package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.*;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class InstagramRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(InstagramRipper.class);

    private String idString;
    private Map<String, String> cookies = new HashMap<>();
    private String endCursor = null;
    private boolean hasNextPage = true;

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
            throw new IOException("Unable to parse Instagram page JSON. Possible invalid session or private profile.");
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
        if (!hasNextPage || endCursor == null || idString == null) {
            return null;
        }
        JSONObject variables = new JSONObject();
        variables.put("id", idString);
        variables.put("first", 12);
        variables.put("after", endCursor);

        String queryHash = "c6809c9c025875ac6f02619eae97a80e"; // standard query hash for profile posts
        String url = format("https://www.instagram.com/graphql/query/?query_hash=%s&variables=%s", queryHash, variables.toString());

        JSONObject result = Http.url(url).cookies(cookies).getJSON();
        JSONObject media = result.getJSONObject("data").getJSONObject("user").getJSONObject("edge_owner_to_timeline_media");
        JSONObject pageInfo = media.getJSONObject("page_info");
        hasNextPage = pageInfo.getBoolean("has_next_page");
        endCursor = pageInfo.optString("end_cursor", null);
        return result;
    }

    private void setAuthCookie() {
        String sessionId = Utils.getConfigString("instagram.session_id", null);
        if (sessionId != null) {
            cookies.put("sessionid", sessionId);
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
            JSONArray edges = getJsonObjectByPath(json, "entry_data.ProfilePage[0].graphql.user.edge_owner_to_timeline_media.edges").getJSONArray("edges");
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
        } catch (Exception e) {
            try {
                JSONArray edges = json.getJSONObject("data").getJSONObject("user").getJSONObject("edge_owner_to_timeline_media").getJSONArray("edges");
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
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", null, cookies);
    }

    private String getPrefix(int index) {
        return String.format("%03d_", index);
    }
}
