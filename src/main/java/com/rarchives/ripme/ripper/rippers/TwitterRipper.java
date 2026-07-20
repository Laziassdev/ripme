package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

/**
 * Rips media from X/Twitter profiles and searches using the web GraphQL API
 * (same approach as TumblThree), authenticated with Firefox session cookies.
 */
public class TwitterRipper extends AbstractJSONRipper {
    private static final Logger logger = LogManager.getLogger(TwitterRipper.class);

    private static final String DOMAIN = "twitter.com";
    private static final String HOST = "twitter";
    private static final String GRAPHQL_BASE = "https://x.com/i/api/graphql";

    /** Public web-client bearer token used by X's site (and TumblThree). */
    public static final String DEFAULT_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";

    public static final String DEFAULT_QUERY_USER_BY_SCREEN_NAME = "xc8f1g7BYqr6VTzTbvNlGw";
    public static final String DEFAULT_QUERY_USER_TWEETS = "2GIWTr7XwadIixZDtyXd4A";
    /** Current SearchTimeline id (twikit Apr 2026). Older TumblThree id NA567V… now 404s. */
    public static final String DEFAULT_QUERY_SEARCH_TIMELINE = "R0u1RWRf748KzyGBXvOYRA";

    /** Fallback SearchTimeline query IDs tried on HTTP 404 (X rotates these). */
    private static final String[] SEARCH_TIMELINE_QUERY_FALLBACKS = {
            "R0u1RWRf748KzyGBXvOYRA",
            "ML-n2SfAxx5S_9QMqNejbg",
            "M1jEez78PEfVfbQLvlWMvQ",
            "4fpceYZ6-YQCx_JSl_Cn_A",
            "NA567V_8AFwu0cZEkAAKcw"
    };

    private static final String FEATURES_USER_BY_SCREEN_NAME =
            "%7B%22hidden_profile_likes_enabled%22%3Afalse%2C%22hidden_profile_subscriptions_enabled%22%3Afalse%2C"
                    + "%22responsive_web_graphql_exclude_directive_enabled%22%3Atrue%2C%22verified_phone_label_enabled%22%3Afalse%2C"
                    + "%22subscriptions_verification_info_verified_since_enabled%22%3Atrue%2C%22highlights_tweets_tab_ui_enabled%22%3Atrue%2C"
                    + "%22creator_subscriptions_tweet_preview_api_enabled%22%3Atrue%2C"
                    + "%22responsive_web_graphql_skip_user_profile_image_extensions_enabled%22%3Afalse%2C"
                    + "%22responsive_web_graphql_timeline_navigation_enabled%22%3Atrue%7D";

    private static final String FIELD_TOGGLES_USER =
            "%7B%22withAuxiliaryUserLabels%22%3Afalse%7D";

    private static final String FEATURES_TIMELINE =
            "%7B%22rweb_lists_timeline_redesign_enabled%22%3Atrue%2C%22responsive_web_graphql_exclude_directive_enabled%22%3Atrue%2C"
                    + "%22verified_phone_label_enabled%22%3Afalse%2C%22creator_subscriptions_tweet_preview_api_enabled%22%3Atrue%2C"
                    + "%22responsive_web_graphql_timeline_navigation_enabled%22%3Atrue%2C"
                    + "%22responsive_web_graphql_skip_user_profile_image_extensions_enabled%22%3Afalse%2C"
                    + "%22tweetypie_unmention_optimization_enabled%22%3Atrue%2C%22responsive_web_edit_tweet_api_enabled%22%3Atrue%2C"
                    + "%22graphql_is_translatable_rweb_tweet_is_translatable_enabled%22%3Atrue%2C"
                    + "%22view_counts_everywhere_api_enabled%22%3Atrue%2C%22longform_notetweets_consumption_enabled%22%3Atrue%2C"
                    + "%22responsive_web_twitter_article_tweet_consumption_enabled%22%3Afalse%2C"
                    + "%22tweet_awards_web_tipping_enabled%22%3Afalse%2C%22freedom_of_speech_not_reach_fetch_enabled%22%3Atrue%2C"
                    + "%22standardized_nudges_misinfo%22%3Atrue%2C"
                    + "%22tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled%22%3Atrue%2C"
                    + "%22longform_notetweets_rich_text_read_enabled%22%3Atrue%2C%22longform_notetweets_inline_media_enabled%22%3Atrue%2C"
                    + "%22responsive_web_media_download_video_enabled%22%3Afalse%2C%22responsive_web_enhance_cards_enabled%22%3Afalse%7D";

    private static final String FIELD_TOGGLES_TIMELINE =
            "%7B%22withAuxiliaryUserLabels%22%3Afalse%2C%22withArticleRichContentState%22%3Afalse%7D";

    /** Feature flags for SearchTimeline POST (from twikit SEARCH_TIMELINE_FEATURES). */
    private static final JSONObject FEATURES_SEARCH_TIMELINE = new JSONObject()
            .put("rweb_video_screen_enabled", false)
            .put("rweb_cashtags_enabled", true)
            .put("profile_label_improvements_pcf_label_in_post_enabled", true)
            .put("responsive_web_profile_redirect_enabled", false)
            .put("rweb_tipjar_consumption_enabled", false)
            .put("verified_phone_label_enabled", false)
            .put("creator_subscriptions_tweet_preview_api_enabled", true)
            .put("responsive_web_graphql_timeline_navigation_enabled", true)
            .put("responsive_web_graphql_skip_user_profile_image_extensions_enabled", false)
            .put("premium_content_api_read_enabled", false)
            .put("communities_web_enable_tweet_community_results_fetch", true)
            .put("c9s_tweet_anatomy_moderator_badge_enabled", true)
            .put("responsive_web_grok_analyze_button_fetch_trends_enabled", false)
            .put("responsive_web_grok_analyze_post_followups_enabled", true)
            .put("responsive_web_jetfuel_frame", true)
            .put("responsive_web_grok_share_attachment_enabled", true)
            .put("responsive_web_grok_annotations_enabled", true)
            .put("articles_preview_enabled", true)
            .put("responsive_web_edit_tweet_api_enabled", true)
            .put("graphql_is_translatable_rweb_tweet_is_translatable_enabled", true)
            .put("view_counts_everywhere_api_enabled", true)
            .put("longform_notetweets_consumption_enabled", true)
            .put("responsive_web_twitter_article_tweet_consumption_enabled", true)
            .put("content_disclosure_indicator_enabled", true)
            .put("content_disclosure_ai_generated_indicator_enabled", true)
            .put("responsive_web_grok_show_grok_translated_post", true)
            .put("responsive_web_grok_analysis_button_from_backend", true)
            .put("post_ctas_fetch_enabled", true)
            .put("freedom_of_speech_not_reach_fetch_enabled", true)
            .put("standardized_nudges_misinfo", true)
            .put("tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled", true)
            .put("longform_notetweets_rich_text_read_enabled", true)
            .put("longform_notetweets_inline_media_enabled", false)
            .put("responsive_web_grok_image_annotation_enabled", true)
            .put("responsive_web_grok_imagine_annotation_enabled", true)
            .put("responsive_web_grok_community_note_auto_translation_is_enabled", true)
            .put("responsive_web_enhance_cards_enabled", false);

    private static final JSONObject FIELD_TOGGLES_SEARCH = new JSONObject()
            .put("withArticleRichContentState", false);

    private static final DateTimeFormatter TWITTER_DATE =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
    private static final DateTimeFormatter UNTIL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    private static final int MAX_REQUESTS = Utils.getConfigInteger("twitter.max_requests", 10);
    private static final boolean RIP_RETWEETS = Utils.getConfigBoolean("twitter.rip_retweets", true);
    private static final boolean EXCLUDE_REPLIES = Utils.getConfigBoolean("twitter.exclude_replies", true);
    private static final int PAGE_SIZE = Utils.getConfigInteger("twitter.max_items_request", 20);
    private static final int WAIT_TIME = 2000;

    private enum ALBUM_TYPE {
        ACCOUNT, SEARCH
    }

    private enum FETCH_MODE {
        USER_TWEETS, SEARCH_TIMELINE
    }

    private String bearerToken;
    private ALBUM_TYPE albumType;
    private String searchText;
    private String accountName;
    private String userRestId;
    private int currentRequest = 0;
    private boolean hasTweets = true;
    private String originalHost;
    private final int maxDownloads = Utils.getConfigInteger("maxdownloads",
            Utils.getConfigInteger("max.downloads", 0));
    private final DownloadLimitTracker downloadLimitTracker = new DownloadLimitTracker(maxDownloads);
    private final AtomicInteger nextIndex = new AtomicInteger(1);
    private volatile boolean maxDownloadLimitReached = false;
    private String twitterCookieHeader;
    private final Map<String, String> twitterCookies = new LinkedHashMap<>();

    private FETCH_MODE fetchMode = FETCH_MODE.USER_TWEETS;
    private String cursor;
    private String previousCursor;
    private String lastBottomCursor;
    private String oldestPostDate;
    private boolean switchedToSearchFallback;
    private boolean lastPageHadTweets;

    public TwitterRipper(URL url) throws IOException {
        super(url);
        bearerToken = Utils.getConfigString("twitter.access_token", null);
        if (bearerToken != null) {
            bearerToken = bearerToken.trim();
        }
        if (bearerToken == null || bearerToken.isEmpty()) {
            bearerToken = DEFAULT_BEARER_TOKEN;
        }
        loadTwitterCookies();
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException {
        originalHost = url.getHost() != null ? url.getHost().toLowerCase(Locale.ROOT) : "";
        String urlString = url.toExternalForm();

        Pattern searchPattern = Pattern.compile(
                "^https?://(m\\.)?(twitter|x)\\.com/search\\?(.*)$", Pattern.CASE_INSENSITIVE);
        Matcher searchMatcher = searchPattern.matcher(urlString);
        if (searchMatcher.matches()) {
            String query = extractQueryParam(searchMatcher.group(3), "q");
            if (query == null || query.isEmpty()) {
                throw new MalformedURLException("Search URL missing q parameter: " + url);
            }
            albumType = ALBUM_TYPE.SEARCH;
            searchText = query;
            fetchMode = FETCH_MODE.SEARCH_TIMELINE;
            return URI.create(urlString).toURL();
        }

        // Profile URLs may include harmless query params (e.g. ?lang=en); strip them for matching.
        String accountUrlString = stripQueryString(urlString);
        Pattern accountPattern = Pattern.compile(
                "^https?://(m\\.)?(twitter|x)\\.com/([a-zA-Z0-9_]+)(/.*)?$", Pattern.CASE_INSENSITIVE);
        Matcher accountMatcher = accountPattern.matcher(accountUrlString);
        if (accountMatcher.matches()) {
            String name = accountMatcher.group(3);
            if (isReservedPath(name)) {
                throw new MalformedURLException("Expected username or search string in url: " + url);
            }
            albumType = ALBUM_TYPE.ACCOUNT;
            accountName = name;
            fetchMode = FETCH_MODE.USER_TWEETS;
            return URI.create(accountUrlString).toURL();
        }
        throw new MalformedURLException("Expected username or search string in url: " + url);
    }

    private static String stripQueryString(String urlString) {
        int queryIdx = urlString.indexOf('?');
        return queryIdx >= 0 ? urlString.substring(0, queryIdx) : urlString;
    }

    private static boolean isReservedPath(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
        case "search":
        case "i":
        case "home":
        case "explore":
        case "notifications":
        case "messages":
        case "settings":
        case "compose":
            return true;
        default:
            return false;
        }
    }

    public static String extractQueryParam(String queryString, String key) {
        if (queryString == null || key == null) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String value = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                return value;
            }
        }
        return null;
    }

    private void ensureAuthenticated() throws IOException {
        loadTwitterCookies();
        String authToken = getTwitterCookie("auth_token");
        String ct0 = getTwitterCookie("ct0");
        if (authToken == null || authToken.isEmpty() || ct0 == null || ct0.isEmpty()) {
            String configPath = Utils.getConfigDir() + java.io.File.separator + "rip.properties";
            throw new IOException(
                    "X/Twitter ripping requires a logged-in Firefox session (cookies auth_token and ct0). "
                            + "Log in to https://x.com in Firefox, then try again. "
                            + "Alternatively set cookies.x.com=auth_token=...; ct0=... in " + configPath);
        }
    }

    private JSONObject graphqlGet(String url, String referer) throws IOException {
        currentRequest++;
        logger.info("    Retrieving " + url);
        try {
            Http http = Http.url(url)
                    .ignoreContentType()
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("User-Agent", AbstractRipper.USER_AGENT)
                    .header("Origin", "https://x.com")
                    .header("x-twitter-active-user", "yes")
                    .header("x-twitter-auth-type", "OAuth2Session")
                    .header("x-twitter-client-language", "en");
            if (referer != null && !referer.isEmpty()) {
                http = http.referrer(referer);
            }
            if (twitterCookieHeader != null && !twitterCookieHeader.isEmpty()) {
                http = http.header("Cookie", twitterCookieHeader);
            }
            String csrf = getTwitterCookie("ct0");
            if (csrf != null && !csrf.isEmpty()) {
                http = http.header("x-csrf-token", csrf);
            }
            JSONObject json = http.getJSON();
            if (json.has("errors")) {
                throw new IOException("X GraphQL errors: " + formatApiErrors(json));
            }
            return json;
        } catch (HttpStatusException e) {
            throw new IOException("X GraphQL returned HTTP " + e.getStatusCode()
                    + ". Log in to https://x.com in Firefox and try again.", e);
        }
    }

    private static String formatApiErrors(JSONObject json) {
        try {
            if (json.has("errors") && json.get("errors") instanceof JSONArray) {
                JSONArray errs = json.getJSONArray("errors");
                if (errs.length() > 0) {
                    return errs.getJSONObject(0).optString("message", errs.toString());
                }
            }
            if (json.has("error")) {
                return json.optString("error_description", json.optString("error", ""));
            }
        } catch (JSONException ignored) {
            // fall through
        }
        return json.optString("errors", "unknown");
    }

    private String queryId(String configKey, String defaultId) {
        String configured = Utils.getConfigString(configKey, defaultId);
        if (configured == null || configured.isBlank()) {
            return defaultId;
        }
        return configured.trim();
    }

    private String buildUserByScreenNameUrl(String screenName) {
        String qid = queryId("twitter.graphql.user_by_screen_name", DEFAULT_QUERY_USER_BY_SCREEN_NAME);
        String variables = urlEncode("{\"screen_name\":\"" + screenName + "\",\"withSafetyModeUserFields\":true}");
        return GRAPHQL_BASE + "/" + qid + "/UserByScreenName?variables=" + variables
                + "&features=" + FEATURES_USER_BY_SCREEN_NAME
                + "&fieldToggles=" + FIELD_TOGGLES_USER;
    }

    private String buildUserTweetsUrl(String restId, String cursorValue) {
        String qid = queryId("twitter.graphql.user_tweets", DEFAULT_QUERY_USER_TWEETS);
        StringBuilder variables = new StringBuilder();
        variables.append("{\"userId\":\"").append(restId).append("\"");
        variables.append(",\"count\":").append(PAGE_SIZE);
        if (cursorValue != null && !cursorValue.isEmpty()) {
            variables.append(",\"cursor\":\"").append(escapeJson(cursorValue)).append("\"");
        }
        variables.append(",\"includePromotedContent\":true");
        variables.append(",\"withQuickPromoteEligibilityTweetFields\":true");
        variables.append(",\"withVoice\":true");
        variables.append(",\"withV2Timeline\":true}");
        return GRAPHQL_BASE + "/" + qid + "/UserTweets?variables=" + urlEncode(variables.toString())
                + "&features=" + FEATURES_TIMELINE
                + "&fieldToggles=" + FIELD_TOGGLES_TIMELINE;
    }

    private JSONObject buildSearchTimelineVariables(String rawQuery, String cursorValue) {
        JSONObject variables = new JSONObject();
        variables.put("rawQuery", rawQuery);
        variables.put("count", PAGE_SIZE);
        variables.put("querySource", "typed_query");
        variables.put("product", "Latest");
        variables.put("withGrokTranslatedBio", true);
        if (cursorValue != null && !cursorValue.isEmpty()) {
            variables.put("cursor", cursorValue);
        }
        return variables;
    }

    /**
     * Posts SearchTimeline GraphQL. X currently returns 404 for GET on this operation;
     * also retries alternate query IDs when the configured one is stale.
     */
    private JSONObject fetchSearchTimeline(String rawQuery, String cursorValue, String referer) throws IOException {
        List<String> candidates = new ArrayList<>();
        String configured = queryId("twitter.graphql.search_timeline", DEFAULT_QUERY_SEARCH_TIMELINE);
        candidates.add(configured);
        for (String fallback : SEARCH_TIMELINE_QUERY_FALLBACKS) {
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }

        JSONObject variables = buildSearchTimelineVariables(rawQuery, cursorValue);
        IOException lastError = null;
        for (String qid : candidates) {
            String url = GRAPHQL_BASE + "/" + qid + "/SearchTimeline";
            try {
                JSONObject response = graphqlPost(url, qid, variables, FEATURES_SEARCH_TIMELINE,
                        FIELD_TOGGLES_SEARCH, referer);
                if (!configured.equals(qid)) {
                    logger.info("SearchTimeline succeeded with query id {} (configured id was stale)", qid);
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                Throwable cause = e.getCause();
                int status = cause instanceof HttpStatusException
                        ? ((HttpStatusException) cause).getStatusCode()
                        : -1;
                if (status == 404) {
                    logger.warn("SearchTimeline query id {} returned HTTP 404; trying next id", qid);
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError
                : new IOException("SearchTimeline failed for all known query ids");
    }

    private JSONObject graphqlPost(String url, String queryId, JSONObject variables, JSONObject features,
            JSONObject fieldToggles, String referer) throws IOException {
        currentRequest++;
        logger.info("    Retrieving POST " + url);
        try {
            JSONObject body = new JSONObject();
            body.put("variables", variables);
            body.put("features", features);
            body.put("queryId", queryId);
            if (fieldToggles != null) {
                body.put("fieldToggles", fieldToggles);
            }

            Connection conn = Http.url(url).ignoreContentType().connection();
            conn.method(Connection.Method.POST);
            conn.header("Authorization", "Bearer " + bearerToken);
            conn.header("Accept", "application/json");
            conn.header("Content-Type", "application/json");
            conn.header("X-Requested-With", "XMLHttpRequest");
            conn.header("User-Agent", AbstractRipper.USER_AGENT);
            conn.header("Origin", "https://x.com");
            conn.header("x-twitter-active-user", "yes");
            conn.header("x-twitter-auth-type", "OAuth2Session");
            conn.header("x-twitter-client-language", "en");
            if (referer != null && !referer.isEmpty()) {
                conn.referrer(referer);
            }
            if (twitterCookieHeader != null && !twitterCookieHeader.isEmpty()) {
                conn.header("Cookie", twitterCookieHeader);
            }
            String csrf = getTwitterCookie("ct0");
            if (csrf != null && !csrf.isEmpty()) {
                conn.header("x-csrf-token", csrf);
            }
            conn.requestBody(body.toString());
            Connection.Response resp = conn.execute();
            JSONObject json = new JSONObject(resp.body());
            if (json.has("errors") && !json.has("data")) {
                throw new IOException("X GraphQL errors: " + formatApiErrors(json));
            }
            return json;
        } catch (HttpStatusException e) {
            throw new IOException("X GraphQL returned HTTP " + e.getStatusCode()
                    + ". Log in to https://x.com in Firefox and try again.", e);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * Extracts the numeric rest_id from a UserByScreenName GraphQL response.
     */
    public static String extractUserRestId(JSONObject response) throws IOException {
        try {
            JSONObject data = response.getJSONObject("data");
            JSONObject user = data.getJSONObject("user");
            JSONObject result = user.getJSONObject("result");
            String typename = result.optString("__typename", "");
            if ("UserUnavailable".equals(typename) || !"User".equals(typename) && result.has("reason")) {
                String reason = result.optString("reason", typename);
                throw new IOException("X user unavailable: " + reason);
            }
            String restId = result.optString("rest_id", null);
            if (restId == null || restId.isEmpty()) {
                throw new IOException("UserByScreenName response missing rest_id");
            }
            return restId;
        } catch (JSONException e) {
            throw new IOException("Could not parse UserByScreenName response: " + e.getMessage(), e);
        }
    }

    /**
     * Walks a GraphQL timeline response and returns legacy tweet objects plus pagination metadata.
     */
    public static TimelinePage parseTimelinePage(JSONObject response) {
        TimelinePage page = new TimelinePage();
        List<JSONObject> instructions = findTimelineInstructions(response);
        for (JSONObject instruction : instructions) {
            String type = instruction.optString("type", "");
            if (!"TimelineAddEntries".equals(type) && !"TimelineReplaceEntries".equals(type)) {
                continue;
            }
            JSONArray entries = instruction.optJSONArray("entries");
            if (entries == null) {
                continue;
            }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String entryId = entry.optString("entryId", "");
                JSONObject content = entry.optJSONObject("content");
                if (content == null) {
                    continue;
                }
                String cursorType = content.optString("cursorType", "");
                if ("Bottom".equalsIgnoreCase(cursorType) || entryId.startsWith("cursor-bottom")) {
                    String value = content.optString("value", null);
                    if (value != null && !value.isEmpty()) {
                        page.bottomCursor = value;
                    }
                    continue;
                }
                if ("Top".equalsIgnoreCase(cursorType) || entryId.startsWith("cursor-top")) {
                    continue;
                }
                if (entryId.startsWith("cursor-")) {
                    continue;
                }

                collectTweetsFromEntry(entry, content, page.tweets);
            }
        }
        return page;
    }

    private static List<JSONObject> findTimelineInstructions(JSONObject response) {
        List<JSONObject> instructions = new ArrayList<>();
        try {
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                return instructions;
            }

            // UserTweets: data.user.result.timeline_v2.timeline.instructions
            JSONObject user = data.optJSONObject("user");
            if (user != null) {
                JSONObject result = user.optJSONObject("result");
                if (result != null) {
                    JSONObject timelineV2 = result.optJSONObject("timeline_v2");
                    if (timelineV2 == null) {
                        timelineV2 = result.optJSONObject("timeline");
                    }
                    if (timelineV2 != null) {
                        JSONObject timeline = timelineV2.optJSONObject("timeline");
                        if (timeline != null) {
                            addInstructions(instructions, timeline.optJSONArray("instructions"));
                        }
                    }
                }
            }

            // SearchTimeline: data.search_by_raw_query.search_timeline.timeline.instructions
            JSONObject searchByRaw = data.optJSONObject("search_by_raw_query");
            if (searchByRaw != null) {
                JSONObject searchTimeline = searchByRaw.optJSONObject("search_timeline");
                if (searchTimeline != null) {
                    JSONObject timeline = searchTimeline.optJSONObject("timeline");
                    if (timeline != null) {
                        addInstructions(instructions, timeline.optJSONArray("instructions"));
                    }
                }
            }
        } catch (JSONException e) {
            logger.debug("Failed to walk timeline instructions", e);
        }
        return instructions;
    }

    private static void addInstructions(List<JSONObject> instructions, JSONArray array) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject instruction = array.optJSONObject(i);
            if (instruction != null) {
                instructions.add(instruction);
            }
        }
    }

    private static void collectTweetsFromEntry(JSONObject entry, JSONObject content, List<JSONObject> tweets) {
        String entryId = entry.optString("entryId", "");
        if (!(entryId.startsWith("tweet-")
                || entryId.startsWith("sq-i-t-")
                || entryId.startsWith("profile-conversation"))) {
            // Still try itemContent for unusual entry ids that wrap tweets
            JSONObject itemContent = content.optJSONObject("itemContent");
            if (itemContent != null) {
                JSONObject tweet = unwrapTweetResult(itemContent);
                if (tweet != null) {
                    tweets.add(tweet);
                }
            }
            JSONArray items = content.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject moduleItem = items.optJSONObject(i);
                    if (moduleItem == null) {
                        continue;
                    }
                    JSONObject item = moduleItem.optJSONObject("item");
                    if (item == null) {
                        continue;
                    }
                    JSONObject moduleItemContent = item.optJSONObject("itemContent");
                    if (moduleItemContent == null) {
                        continue;
                    }
                    JSONObject tweet = unwrapTweetResult(moduleItemContent);
                    if (tweet != null) {
                        tweets.add(tweet);
                    }
                }
            }
            return;
        }

        JSONObject itemContent = content.optJSONObject("itemContent");
        if (itemContent != null) {
            JSONObject tweet = unwrapTweetResult(itemContent);
            if (tweet != null) {
                tweets.add(tweet);
            }
        }
        JSONArray items = content.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject moduleItem = items.optJSONObject(i);
                if (moduleItem == null) {
                    continue;
                }
                JSONObject item = moduleItem.optJSONObject("item");
                if (item == null) {
                    continue;
                }
                JSONObject moduleItemContent = item.optJSONObject("itemContent");
                if (moduleItemContent == null) {
                    continue;
                }
                JSONObject tweet = unwrapTweetResult(moduleItemContent);
                if (tweet != null) {
                    tweets.add(tweet);
                }
            }
        }
    }

    /**
     * Unwraps TweetWithVisibilityResults / Tweet results down to a legacy-bearing tweet object.
     */
    public static JSONObject unwrapTweetResult(JSONObject itemContent) {
        if (itemContent == null) {
            return null;
        }
        JSONObject tweetResults = itemContent.optJSONObject("tweet_results");
        if (tweetResults == null) {
            return null;
        }
        JSONObject result = tweetResults.optJSONObject("result");
        if (result == null) {
            return null;
        }
        if ("TweetWithVisibilityResults".equals(result.optString("__typename", ""))) {
            result = result.optJSONObject("tweet");
            if (result == null) {
                return null;
            }
        }
        if (!result.has("legacy")) {
            return null;
        }
        return result;
    }

    /**
     * Extracts downloadable media URLs from a GraphQL tweet result object.
     */
    public static List<String> extractMediaUrls(JSONObject tweetResult, boolean ripRetweets, boolean excludeReplies) {
        List<String> urls = new ArrayList<>();
        if (tweetResult == null) {
            return urls;
        }

        JSONObject legacy = tweetResult.optJSONObject("legacy");
        if (legacy == null) {
            return urls;
        }

        if (excludeReplies) {
            String replyTo = legacy.optString("in_reply_to_status_id_str", "");
            if (replyTo != null && !replyTo.isEmpty()) {
                return urls;
            }
        }

        if (!ripRetweets && legacy.has("retweeted_status_result")) {
            JSONObject rsr = legacy.optJSONObject("retweeted_status_result");
            if (rsr != null && rsr.has("result")) {
                return urls;
            }
        }

        // Prefer media from the retweeted status when present so we still get media of RTs
        JSONObject mediaSource = legacy;
        if (legacy.has("retweeted_status_result")) {
            JSONObject rsr = legacy.optJSONObject("retweeted_status_result");
            if (rsr != null) {
                JSONObject rtResult = rsr.optJSONObject("result");
                if (rtResult != null) {
                    if ("TweetWithVisibilityResults".equals(rtResult.optString("__typename", ""))) {
                        rtResult = rtResult.optJSONObject("tweet");
                    }
                    if (rtResult != null && rtResult.has("legacy")) {
                        mediaSource = rtResult.getJSONObject("legacy");
                    }
                }
            }
        }

        JSONObject entities = mediaSource.has("extended_entities")
                ? mediaSource.optJSONObject("extended_entities")
                : mediaSource.optJSONObject("entities");
        if (entities == null || !entities.has("media")) {
            return urls;
        }

        JSONArray medias = entities.optJSONArray("media");
        if (medias == null) {
            return urls;
        }

        for (int i = 0; i < medias.length(); i++) {
            JSONObject media = medias.optJSONObject(i);
            if (media == null) {
                continue;
            }
            String type = media.optString("type", "photo");
            if ("video".equals(type) || "animated_gif".equals(type)) {
                String videoUrl = selectBestVideoUrl(media);
                if (videoUrl != null) {
                    urls.add(videoUrl);
                }
            } else {
                String photoUrl = media.optString("media_url_https", media.optString("media_url", ""));
                if (!photoUrl.isEmpty()) {
                    urls.add(rewritePhotoUrl(photoUrl));
                }
            }
        }
        return urls;
    }

    public static String selectBestVideoUrl(JSONObject media) {
        JSONObject videoInfo = media.optJSONObject("video_info");
        if (videoInfo == null) {
            return null;
        }
        JSONArray variants = videoInfo.optJSONArray("variants");
        if (variants == null) {
            return null;
        }
        String type = media.optString("type", "video");
        int largestBitrate = -1;
        String bestUrl = null;
        for (int i = 0; i < variants.length(); i++) {
            JSONObject variant = variants.optJSONObject(i);
            if (variant == null) {
                continue;
            }
            String contentType = variant.optString("content_type", "");
            if (!"video/mp4".equals(contentType) && !variant.has("bitrate")) {
                continue;
            }
            String url = variant.optString("url", null);
            if (url == null || url.isEmpty()) {
                continue;
            }
            if (variant.has("bitrate")) {
                int bitrate = variant.optInt("bitrate", 0);
                if (bitrate > largestBitrate) {
                    largestBitrate = bitrate;
                    bestUrl = url;
                }
            } else if ("animated_gif".equals(type) && bestUrl == null) {
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    /**
     * Rewrites pbs.twimg.com photo URLs to request the original size.
     * Keeps the file extension in the path so RipMe's downloader can name the file correctly
     * (query-only {@code ?format=} URLs would otherwise save with no extension).
     */
    public static String rewritePhotoUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (!url.contains("pbs.twimg.com/media/")) {
            if (url.contains(".twimg.com/") && !url.contains("?")) {
                return url + ":orig";
            }
            return url;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            int slash = path.lastIndexOf('/');
            String filename = slash >= 0 ? path.substring(slash + 1) : path;
            // Strip size suffixes like ":large" / ":orig" before reading the extension
            int colon = filename.indexOf(':');
            if (colon > 0) {
                filename = filename.substring(0, colon);
            }
            int dot = filename.lastIndexOf('.');
            String baseName = dot > 0 ? filename.substring(0, dot) : filename;
            String ext = dot > 0 ? filename.substring(dot + 1) : "jpg";
            if (ext.isBlank() || ext.contains("/") || ext.length() > 5) {
                ext = "jpg";
            }
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return uri.getScheme() + "://" + uri.getHost() + dir + baseName + "." + ext
                    + "?format=" + ext + "&name=orig";
        } catch (IllegalArgumentException e) {
            return url.contains("?") ? url : url + ":orig";
        }
    }

    public static String oldestTweetDate(List<JSONObject> tweets) {
        String oldest = null;
        ZonedDateTime oldestTime = null;
        for (JSONObject tweet : tweets) {
            JSONObject legacy = tweet.optJSONObject("legacy");
            if (legacy == null) {
                continue;
            }
            String createdAt = legacy.optString("created_at", null);
            if (createdAt == null || createdAt.isEmpty()) {
                continue;
            }
            try {
                ZonedDateTime time = ZonedDateTime.parse(createdAt, TWITTER_DATE);
                if (oldestTime == null || time.isBefore(oldestTime)) {
                    oldestTime = time;
                    oldest = time.format(UNTIL_DATE);
                }
            } catch (Exception e) {
                logger.debug("Could not parse tweet date: {}", createdAt);
            }
        }
        return oldest;
    }

    public String getPrefix(int index) {
        return Utils.getConfigBoolean("download.save_order", true) ? String.format("%03d_", index) : "";
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        ensureAuthenticated();
        currentRequest = 0;
        cursor = null;
        previousCursor = null;
        lastBottomCursor = null;
        oldestPostDate = null;
        switchedToSearchFallback = false;
        lastPageHadTweets = false;

        if (albumType == ALBUM_TYPE.ACCOUNT) {
            fetchMode = FETCH_MODE.USER_TWEETS;
            String userUrl = buildUserByScreenNameUrl(accountName);
            JSONObject userResponse = graphqlGet(userUrl, "https://x.com/" + accountName);
            userRestId = extractUserRestId(userResponse);
            logger.info("Resolved @{} to rest_id {}", accountName, userRestId);
        } else {
            fetchMode = FETCH_MODE.SEARCH_TIMELINE;
        }

        return fetchTimelinePage();
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException {
        try {
            Thread.sleep(WAIT_TIME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("[!] Interrupted while waiting to load more results", e);
            return null;
        }
        if (maxDownloadLimitReached) {
            return null;
        }
        if (currentRequest >= MAX_REQUESTS) {
            logger.info("Reached twitter.max_requests ({}); stopping.", MAX_REQUESTS);
            return null;
        }

        previousCursor = cursor;
        cursor = lastBottomCursor;

        boolean cursorExhausted = cursor == null || cursor.isEmpty() || cursor.equals(previousCursor);
        if (cursorExhausted
                && albumType == ALBUM_TYPE.ACCOUNT
                && fetchMode == FETCH_MODE.USER_TWEETS
                && !switchedToSearchFallback
                && oldestPostDate != null) {
            logger.info("UserTweets exhausted; switching to SearchTimeline from:{} until:{}",
                    accountName, oldestPostDate);
            fetchMode = FETCH_MODE.SEARCH_TIMELINE;
            switchedToSearchFallback = true;
            cursor = null;
            previousCursor = null;
            lastBottomCursor = null;
        } else if (cursorExhausted && !lastPageHadTweets) {
            logger.info("No more timeline cursor; stopping.");
            return null;
        } else if (cursorExhausted) {
            logger.info("No more timeline cursor; stopping.");
            return null;
        }

        return fetchTimelinePage();
    }

    private JSONObject fetchTimelinePage() throws IOException {
        String referer;
        JSONObject response;
        if (fetchMode == FETCH_MODE.USER_TWEETS) {
            String requestUrl = buildUserTweetsUrl(userRestId, cursor);
            referer = "https://x.com/" + accountName;
            response = graphqlGet(requestUrl, referer);
        } else {
            String rawQuery;
            if (albumType == ALBUM_TYPE.ACCOUNT && switchedToSearchFallback) {
                rawQuery = "from:" + accountName + " until:" + oldestPostDate;
                referer = "https://x.com/search?q=" + urlEncode(rawQuery) + "&src=typed_query&f=latest";
            } else {
                rawQuery = searchText;
                referer = "https://x.com/search?q=" + urlEncode(searchText) + "&src=typed_query&f=latest";
            }
            response = fetchSearchTimeline(rawQuery, cursor, referer);
        }

        TimelinePage page = parseTimelinePage(response);
        lastBottomCursor = page.bottomCursor;
        lastPageHadTweets = !page.tweets.isEmpty();
        String date = oldestTweetDate(page.tweets);
        if (date != null) {
            oldestPostDate = date;
        }

        if (page.tweets.isEmpty()
                && albumType == ALBUM_TYPE.ACCOUNT
                && fetchMode == FETCH_MODE.USER_TWEETS
                && !switchedToSearchFallback
                && oldestPostDate != null) {
            logger.info("Empty UserTweets page; switching to SearchTimeline");
            fetchMode = FETCH_MODE.SEARCH_TIMELINE;
            switchedToSearchFallback = true;
            cursor = null;
            lastBottomCursor = null;
            return fetchTimelinePage();
        }

        JSONArray tweetArray = new JSONArray();
        for (JSONObject tweet : page.tweets) {
            tweetArray.put(tweet);
        }
        JSONObject wrapper = new JSONObject();
        wrapper.put("tweets", tweetArray);
        return wrapper;
    }

    @Override
    protected boolean usesCustomDownloadLimitTracking() {
        return true;
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    protected String getDomain() {
        if (originalHost != null && originalHost.endsWith("x.com")) {
            return "x.com";
        }
        return DOMAIN;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        switch (albumType) {
        case ACCOUNT:
            return "account_" + accountName;
        case SEARCH:
            StringBuilder gid = new StringBuilder();
            for (int i = 0; i < searchText.length(); i++) {
                char c = searchText.charAt(i);
                if (c == '%' || c == ' ' || c == ':' || c == '/') {
                    gid.append('_');
                } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                        || c == '-') {
                    gid.append(c);
                }
            }
            return "search_" + gid;
        }
        throw new MalformedURLException("Could not decide type of URL (search/account): " + url);
    }

    @Override
    public boolean hasASAPRipping() {
        return hasTweets;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        if (maxDownloadLimitReached) {
            hasTweets = false;
            return urls;
        }

        JSONArray statuses = json.optJSONArray("tweets");
        if (statuses == null || statuses.length() == 0) {
            logger.info("   No more tweets found.");
            // If user timeline is done, getNextPage may switch to search fallback
            return urls;
        }

        logger.debug("Twitter GraphQL response #{} tweets: {}", currentRequest, statuses.length());

        for (int i = 0; i < statuses.length(); i++) {
            JSONObject tweet = statuses.optJSONObject(i);
            if (tweet == null) {
                continue;
            }
            urls.addAll(extractMediaUrls(tweet, RIP_RETWEETS, EXCLUDE_REPLIES));
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        int currentIndex = nextIndex.get();
        boolean countTowardsLimit = true;
        if (downloadLimitTracker.isEnabled()) {
            try {
                Path existingPath = getFilePath(url, "", getPrefix(currentIndex), null, null);
                if (Files.exists(existingPath)) {
                    if (!Utils.getConfigBoolean("file.overwrite", false)) {
                        logger.debug("Skipping existing file due to max download limit: {}", existingPath);
                        super.downloadExists(url, existingPath);
                        return;
                    }
                    countTowardsLimit = false;
                }
            } catch (IOException e) {
                logger.warn("Unable to determine existing file path for {}: {}", url, e.getMessage());
            }
        }

        if (!downloadLimitTracker.tryAcquire(url, countTowardsLimit)) {
            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                hasTweets = false;
                if (downloadLimitTracker.shouldNotifyLimitReached()) {
                    String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                    logger.info(message);
                    sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                }
            } else {
                logger.debug("Max download limit of {} currently allocated, deferring {}", maxDownloads, url);
            }
            return;
        }

        boolean added = addURLToDownload(url, getPrefix(currentIndex));
        if (added) {
            nextIndex.incrementAndGet();
            if (Utils.getConfigBoolean("urls_only.save", false)) {
                handleSuccessfulDownload(url);
            }
        } else {
            downloadLimitTracker.onFailure(url);
        }
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith("twitter.com") || host.endsWith("x.com");
    }

    @Override
    public void downloadCompleted(URL url, Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadExists(URL url, Path file) {
        super.downloadExists(url, file);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        downloadLimitTracker.onFailure(url);
        super.downloadErrored(url, reason);
    }

    private void loadTwitterCookies() {
        twitterCookies.clear();
        loadCookiesFromFirefox();
        mergeConfiguredCookies("cookies.x.com");
        mergeConfiguredCookies("cookies.twitter.com");
        twitterCookieHeader = FirefoxCookieUtils.toCookieHeader(twitterCookies);
        if (twitterCookieHeader != null && !twitterCookieHeader.isEmpty()) {
            logger.debug("Prepared twitter cookie header ({} bytes, auth_token={}, ct0={})",
                    twitterCookieHeader.length(),
                    getTwitterCookie("auth_token") != null,
                    getTwitterCookie("ct0") != null);
        }
    }

    private void mergeConfiguredCookies(String configKey) {
        String configured = Utils.getConfigString(configKey, null);
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            Map<String, String> parsed = RipUtils.getCookiesFromString(configured.trim());
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                // Config overrides Firefox when explicitly set
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                    twitterCookies.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not parse {}: {}", configKey, e.getMessage());
        }
    }

    private void loadCookiesFromFirefox() {
        if (!FirefoxCookieUtils.isSQLiteDriverAvailable()) {
            logger.debug("SQLite JDBC driver not available; cannot read Firefox cookies for twitter");
            return;
        }

        Map<String, String> bestCookies = null;
        Path bestProfile = null;
        for (Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
            Map<String, String> cookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath,
                    Arrays.asList("%twitter.com", "%x.com"));
            if (cookies.isEmpty()) {
                continue;
            }
            boolean hasAuth = cookies.containsKey("auth_token") && !isBlank(cookies.get("auth_token"));
            boolean hasCt0 = cookies.containsKey("ct0") && !isBlank(cookies.get("ct0"));
            if (hasAuth && hasCt0) {
                bestCookies = cookies;
                bestProfile = profilePath;
                break;
            }
            if (bestCookies == null) {
                bestCookies = cookies;
                bestProfile = profilePath;
            }
        }

        if (bestCookies != null) {
            twitterCookies.putAll(bestCookies);
            logger.info("Loaded {} twitter cookies from Firefox profile {}", bestCookies.size(),
                    bestProfile != null ? bestProfile.getFileName() : "?");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String getTwitterCookie(String name) {
        if (name == null) {
            return null;
        }
        return twitterCookies.get(name);
    }

    private void handleSuccessfulDownload(URL url) {
        if (downloadLimitTracker.onSuccess(url)) {
            maxDownloadLimitReached = true;
            hasTweets = false;
            if (downloadLimitTracker.shouldNotifyLimitReached()) {
                String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                logger.info(message);
                sendUpdate(STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
            }
        }
    }

    /**
     * Parsed GraphQL timeline page: tweet results and bottom cursor.
     */
    public static final class TimelinePage {
        public final List<JSONObject> tweets = new ArrayList<>();
        public String bottomCursor;
    }
}
