package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.BlueskyRipper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlueskyRipperTest {

    @Test
    public void testExtractsVideoPlaylistFromVideoViewEmbed() {
        JSONObject embed = new JSONObject()
                .put("$type", "app.bsky.embed.video#view")
                .put("cid", "bafkreidj3gyri3jd5wdqhnu5lcx62cgtxmsyq3a623li3z6yvafa2ds6si")
                .put("playlist", "https://video.bsky.app/watch/did%3Aplc%3Aexample/bafkreidj3gyri3jd5wdqhnu5lcx62cgtxmsyq3a623li3z6yvafa2ds6si/playlist.m3u8");

        List<String> urls = BlueskyRipper.extractMediaUrlsFromEmbed(embed);

        assertEquals(1, urls.size());
        assertTrue(urls.get(0).endsWith("/playlist.m3u8"));
    }

    @Test
    public void testExtractsLegacyVideoUriEmbed() {
        JSONObject embed = new JSONObject()
                .put("video", new JSONObject().put("uri", "https://cdn.example/video.mp4"));

        List<String> urls = BlueskyRipper.extractMediaUrlsFromEmbed(embed);

        assertEquals(1, urls.size());
        assertTrue(urls.get(0).endsWith("video.mp4"));
    }

    @Test
    public void testExtractsImagesFromImageViewEmbed() {
        JSONObject embed = new JSONObject()
                .put("$type", "app.bsky.embed.images#view")
                .put("images", new org.json.JSONArray()
                        .put(new JSONObject().put("fullsize", "https://cdn.bsky.app/img/full.jpg")));

        List<String> urls = BlueskyRipper.extractMediaUrlsFromEmbed(embed);

        assertEquals(1, urls.size());
        assertTrue(urls.get(0).endsWith("full.jpg"));
    }
}
