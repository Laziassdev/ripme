package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.HqpornerRipper;
import com.rarchives.ripme.utils.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public class HqpornerRipperTest extends RippersTest {

    public void testRip() throws IOException, URISyntaxException {
        if (Utils.getConfigBoolean("test.run_flaky_tests", false)) {
            HqpornerRipper ripper = new HqpornerRipper(
                    new URI("https://hqporner.com/hdporn/84636-pool_lesson_with_a_cheating_husband.html").toURL());
            testRipper(ripper);
        }
    }

    public void testGetGID() throws IOException, URISyntaxException {
        URL poolURL = new URI("https://hqporner.com/hdporn/84636-pool_lesson_with_a_cheating_husband.html").toURL();
        HqpornerRipper ripper = new HqpornerRipper(poolURL);
        Assertions.assertEquals("84636-pool_lesson_with_a_cheating_husband", ripper.getGID(poolURL));
    }
    @Test
    public void testGetURLsFromPage() throws URISyntaxException {
        try {
            URL actressUrl = new URI("https://hqporner.com/actress/kali-roses").toURL();
            HqpornerRipper ripper = new HqpornerRipper(actressUrl);
            assert (ripper.getURLsFromPage(ripper.getFirstPage()).size() >= 2);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Skipping due to network error: " + e.getMessage());
        }
    }
    @Test
    public void testGetNextPage() throws URISyntaxException {
        try {
            URL multiPageUrl = new URI("https://hqporner.com/category/tattooed").toURL();
            HqpornerRipper multiPageRipper = new HqpornerRipper(multiPageUrl);
            assert (multiPageRipper.getNextPage(multiPageRipper.getFirstPage()) != null);

            URL singlePageUrl = new URI("https://hqporner.com/actress/amy-reid").toURL();
            HqpornerRipper ripper = new HqpornerRipper(singlePageUrl);
            try {
                ripper.getNextPage(ripper.getFirstPage());
            } catch (IOException e) {
                Assertions.assertEquals("No next page found.", e.getMessage());
            }
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Skipping due to network error: " + e.getMessage());
        }
    }
    @Test
    public void testMyDaddyVideoHost() throws IOException, URISyntaxException {
        if (Utils.getConfigBoolean("test.run_flaky_tests", false)) {
            URL myDaddyUrl = new URI("https://hqporner.com/hdporn/84636-pool_lesson_with_a_cheating_husband.html").toURL();
            HqpornerRipper myDaddyRipper = new HqpornerRipper(myDaddyUrl);
            testRipper(myDaddyRipper);
        }
    }
    @Test
    public void testFlyFlvVideoHost() throws IOException, URISyntaxException {
        if (Utils.getConfigBoolean("test.run_flaky_tests", false)) {
            URL flyFlvUrl = new URI(
                    "https://hqporner.com/hdporn/69862-bangbros_-_amy_reid_taking_off_a_tight_sexy_swimsuit.html").toURL();
            HqpornerRipper flyFlvRipper = new HqpornerRipper(flyFlvUrl);
            testRipper(flyFlvRipper);
        }
    }
    @Test
    public void testUnknownVideoHost() throws IOException, URISyntaxException {
        if (Utils.getConfigBoolean("test.run_flaky_tests", false)) {
            URL unknownHostUrl = new URI("https://hqporner.com/hdporn/79528-Kayden_Kross_-_Serious_Masturbation.html").toURL(); // howq.cc
            HqpornerRipper unknownHostRipper = new HqpornerRipper(unknownHostUrl);
            testRipper(unknownHostRipper);
        }
    }
}