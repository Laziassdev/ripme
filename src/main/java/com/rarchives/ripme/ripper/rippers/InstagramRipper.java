package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

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

    private String qHash;
    private Map<String, String> cookies = new HashMap<>();
    private String idString;

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
        return jsonObject;
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
                    result.add(node.getString("video_url"));
                } else {
                    result.add(node.getString("display_url"));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract media URLs", e);
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
