package com.rarchives.ripme.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.Utils;

public class InstagramRipperLimitTest {

    @Test
    void skipsExistingFilesWhenLimitActive() throws Exception {
        int originalMaxDownloads = Utils.getConfigInteger("maxdownloads", -1);
        String originalRipsDirectory = Utils.getConfigString("rips.directory", Utils.getWorkingDirectory().toString());
        boolean originalUrlsOnly = Utils.getConfigBoolean("urls_only.save", false);
        boolean originalOverwrite = Utils.getConfigBoolean("file.overwrite", true);

        Path tempRipsDir = Files.createTempDirectory("ripme-instagram-test");

        try {
            Utils.setConfigInteger("maxdownloads", 1);
            Utils.setConfigString("rips.directory", tempRipsDir.toString());
            Utils.setConfigBoolean("urls_only.save", true);
            Utils.setConfigBoolean("file.overwrite", true);

            InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
            ripper.setup();

            URL existingUrl = new URL("https://example.com/media1.jpg");
            Path existingPath = ripper.getFilePath(existingUrl, "", ripper.getPrefix(1), null, null);
            Files.createDirectories(existingPath.getParent());
            Files.write(existingPath, new byte[] { 1 });

            ripper.downloadURL(existingUrl, 1);

            assertTrue(Files.exists(existingPath), "Existing file should not be deleted when re-queued");

            URL newUrl = new URL("https://example.com/media2.jpg");
            ripper.downloadURL(newUrl, 2);

            Path urlsFile = ripper.getWorkingDir().toPath().resolve("urls.txt");
            assertTrue(Files.exists(urlsFile), "New downloads should still be recorded");
            String urlsContent = Files.readString(urlsFile);
            assertTrue(urlsContent.contains(newUrl.toExternalForm()), "New URL should be saved after skipping existing files");
        } finally {
            Utils.setConfigInteger("maxdownloads", originalMaxDownloads);
            Utils.setConfigString("rips.directory", originalRipsDirectory);
            Utils.setConfigBoolean("urls_only.save", originalUrlsOnly);
            Utils.setConfigBoolean("file.overwrite", originalOverwrite);
            try (Stream<Path> paths = Files.walk(tempRipsDir)) {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
            }
        }
    }
}
