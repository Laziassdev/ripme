package com.rarchives.ripme.tst.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.CoomerPartyRipper;

public class CoomerPartyRipperTest extends RippersTest {
    @Test
    @Tag("flaky")
    public void testRip() throws IOException, URISyntaxException {
        URL url = new URI("https://coomer.st/onlyfans/user/soogsx").toURL();
        CoomerPartyRipper ripper = new CoomerPartyRipper(url);
        testRipper(ripper);
    }

    @Test
    public void testUrlParsing() throws IOException, URISyntaxException {
        String expectedGid = "onlyfans_soogsx";
        String[] urls = new String[] {
                "https://coomer.st/onlyfans/user/soogsx", // normal url
                "http://coomer.st/onlyfans/user/soogsx", // http, not https
                "https://coomer.st/onlyfans/user/soogsx/", // with slash at the end
                "https://coomer.st/onlyfans/user/soogsx?whatever=abc", // with url params
                "https://coomer.party/onlyfans/user/soogsx", // alternate domain
                "https://coomer.su/onlyfans/user/soogsx", // legacy domain
        };
        for (String stringUrl : urls) {
            URL url = new URI(stringUrl).toURL();
            CoomerPartyRipper ripper = new CoomerPartyRipper(url);
            assertTrue(ripper.canRip(url));
            assertEquals(expectedGid, ripper.getGID(url));
        }
    }
}
