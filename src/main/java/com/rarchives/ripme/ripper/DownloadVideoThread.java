package com.rarchives.ripme.ripper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.net.ssl.HttpsURLConnection;

import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thread for downloading files.
 * Includes retry logic, observer notifications, and other goodies.
 */
class DownloadVideoThread implements Runnable {

    private static final Logger logger = LogManager.getLogger(DownloadVideoThread.class);

    private final URL url;
    private final Path saveAs;
    private final String prettySaveAs;
    private final AbstractRipper observer;
    private final int retries;

    public DownloadVideoThread(URL url, Path saveAs, AbstractRipper observer) {
        super();
        this.url = url;
        this.saveAs = saveAs;
        this.prettySaveAs = Utils.removeCWD(saveAs);
        this.observer = observer;
        this.retries = Utils.getConfigInteger("download.retries", 1);
    }

    /**
     * Attempts to download the file. Retries as needed.
     * Notifies observers upon completion/error/warn.
     */
    @Override
    public void run() {
        try {
            observer.stopCheck();
        } catch (IOException e) {
            observer.downloadErrored(url, "Download interrupted");
            return;
        }
        Path targetPath = saveAs;
        Path workingPath = targetPath;
        Path tempPath = null;
        boolean overwrite = Utils.getConfigBoolean("file.overwrite", false);
        if (Files.exists(targetPath)) {
            if (overwrite) {
                try {
                    tempPath = Files.createTempFile(targetPath.getParent(), "ripme-", ".tmp");
                    workingPath = tempPath;
                } catch (IOException e) {
                    logger.error("[!] Failed to prepare temporary file for {}: {}", prettySaveAs, e.getMessage());
                    observer.downloadErrored(url, "Download interrupted");
                    return;
                }
            } else {
                logger.info("[!] Skipping " + url + " -- file already exists: " + prettySaveAs);
                observer.downloadExists(url, targetPath);
                return;
            }
        }

        int bytesTotal, bytesDownloaded = 0;
        try {
            bytesTotal = getTotalBytes(this.url);
        } catch (IOException e) {
            logger.error("Failed to get file size at " + this.url, e);
            observer.downloadErrored(this.url, "Failed to get file size of " + this.url);
            return;
        }
        observer.setBytesTotal(bytesTotal);
        observer.sendUpdate(STATUS.TOTAL_BYTES, bytesTotal);
        logger.debug("Size of file at " + this.url + " = " + bytesTotal + "b");

        int tries = 0; // Number of attempts to download
        do {
            InputStream bis = null; OutputStream fos = null;
            byte[] data = new byte[1024 * 256];
            int bytesRead;
            try {
                logger.info("    Downloading file: " + url + (tries > 0 ? " Retry #" + tries : ""));
                observer.sendUpdate(STATUS.DOWNLOAD_STARTED, url.toExternalForm());

                // Setup HTTP request
                HttpURLConnection huc;
                if (this.url.toString().startsWith("https")) {
                    huc = (HttpsURLConnection) this.url.openConnection();
                }
                else {
                    huc = (HttpURLConnection) this.url.openConnection();
                }
                huc.setInstanceFollowRedirects(true);
                huc.setConnectTimeout(0); // Never timeout
                huc.setRequestProperty("accept",  "*/*");
                huc.setRequestProperty("Referer", this.url.toExternalForm()); // Sic
                huc.setRequestProperty("User-agent", AbstractRipper.USER_AGENT);
                tries += 1;
                logger.debug("Request properties: " + huc.getRequestProperties().toString());
                huc.connect();
                // Check status code
                bis = new BufferedInputStream(huc.getInputStream());
                fos = Files.newOutputStream(workingPath);
                while ( (bytesRead = bis.read(data)) != -1) {
                    try {
                        observer.stopCheck();
                    } catch (IOException e) {
                        observer.downloadErrored(url, "Download interrupted");
                        return;
                    }
                    fos.write(data, 0, bytesRead);
                    bytesDownloaded += bytesRead;
                    observer.setBytesCompleted(bytesDownloaded);
                    observer.sendUpdate(STATUS.COMPLETED_BYTES, bytesDownloaded);
                }
                bis.close();
                fos.close();
                if (!observer.registerDownloadHash(workingPath)) {
                    logger.warn("[!] Deleting {} because its hash matches a previously downloaded file", prettySaveAs);
                    try {
                        Files.deleteIfExists(workingPath);
                    } catch (IOException e) {
                        logger.warn("[!] Failed to delete duplicate file {}: {}", workingPath, e.getMessage());
                    }
                    observer.downloadErrored(url, "Duplicate file (deleted)");
                    return;
                }
                if (tempPath != null) {
                    try {
                        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        workingPath = targetPath;
                    } catch (IOException moveException) {
                        logger.error("[!] Failed to replace existing file {}: {}", prettySaveAs, moveException.getMessage());
                        Files.deleteIfExists(tempPath);
                        observer.downloadErrored(url, "Download interrupted");
                        return;
                    }
                }
                break; // Download successful: break out of infinite loop
            } catch (IOException e) {
                logger.error("[!] Exception while downloading file: " + url + " - " + e.getMessage(), e);
            } finally {
                // Close any open streams
                try {
                    if (bis != null) { bis.close(); }
                } catch (IOException ignored) { }
                try {
                    if (fos != null) { fos.close(); }
                } catch (IOException ignored) { }
            }
            if (tries > this.retries) {
                logger.error("[!] Exceeded maximum retries (" + this.retries + ") for URL " + url);
                observer.downloadErrored(url, "Failed to download " + url.toExternalForm());
                return;
            }
        } while (true);
        observer.downloadCompleted(url, targetPath);
        logger.info("[+] Saved " + url + " as " + this.prettySaveAs);
    }

    /**
     * @param url
     *      Target URL
     * @return 
     *      Returns connection length
     */
    private int getTotalBytes(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setRequestProperty("accept",  "*/*");
        conn.setRequestProperty("Referer", this.url.toExternalForm()); // Sic
        conn.setRequestProperty("User-agent", AbstractRipper.USER_AGENT);
        return conn.getContentLength();
    }

}