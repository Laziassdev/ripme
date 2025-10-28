package com.rarchives.ripme.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class TumblrRipperHiddenMediaTest {

    @SuppressWarnings("unchecked")
    @Test
    public void selectsLargestHiddenMediaVariant() throws Exception {
        TumblrRipper ripper = new TumblrRipper(new URL("https://example.tumblr.com"));
        Method method = TumblrRipper.class.getDeclaredMethod("selectBestHiddenMediaUrls", Set.class);
        method.setAccessible(true);

        Set<String> variants = new LinkedHashSet<>(Arrays.asList(
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s2048x3072/aec20659ad036e489fb14fc32a96d7cbf1c82991.jpg",
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s1280x1920/6406dd6aa919151ebb8c4297f678e49b6461b2bb.jpg",
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s640x960/329973fffbd76b0c55bd3123f2f5336f7ef3b1b3.jpg",
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s540x810/7e1d39174754349cb8aa0e49fc6dc67b9884401f.jpg",
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s500x750/9b4e39d47200e4cf3ddf3f544259a612dce57882.jpg"));

        Set<String> result = (Set<String>) method.invoke(ripper, variants);

        assertEquals(1, result.size(), "Only the largest variant should remain");
        assertEquals(
                "https://64.media.tumblr.com/72ade68589b8b3c46fe628a9a1051117/6ffb1d622ee3ff35-31/s2048x3072/aec20659ad036e489fb14fc32a96d7cbf1c82991.jpg",
                result.iterator().next(),
                "The s2048x3072 variant should be preferred");
    }
}
