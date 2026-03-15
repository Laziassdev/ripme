package com.rarchives.ripme.ripper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
    private final int retrySleep;

    public DownloadVideoThread(URL url, Path saveAs, AbstractRipper observer) {
        super();
        this.url = url;
        this.saveAs = saveAs;
        this.prettySaveAs = Utils.removeCWD(saveAs);
        this.observer = observer;
        this.retries = Utils.getConfigInteger("download.retries", 1);
        this.retrySleep = Utils.getConfigInteger("download.retry.sleep", 0);
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

        int bytesTotal;
        try {
            bytesTotal = getTotalBytes(this.url);
        } catch (IOException e) {
            logger.error("Failed to get file size at " + this.url, e);
            observer.downloadErrored(this.url, "Failed to get file size of " + this.url);
            return;
        }
        // -1 means server did not send Content-Length (e.g. chunked); we cannot verify size
        if (bytesTotal < 0) {
            bytesTotal = 0;
        }
        observer.setBytesTotal(bytesTotal);
        observer.sendUpdate(STATUS.TOTAL_BYTES, bytesTotal);
        logger.debug("Size of file at " + this.url + " = " + bytesTotal + "b");

        int tries = 0; // Number of attempts to download
        do {
            InputStream bis = null; OutputStream fos = null;
            HttpURLConnection huc = null;
            byte[] data = new byte[1024 * 256];
            int bytesRead;
            long bytesDownloaded = 0;
            try {
                logger.info("    Downloading file: " + url + (tries > 0 ? " Retry #" + tries : ""));
                observer.sendUpdate(STATUS.DOWNLOAD_STARTED, url.toExternalForm());

                // Setup HTTP request
                if (this.url.toString().startsWith("https")) {
                    huc = (HttpsURLConnection) this.url.openConnection();
                }
                else {
                    huc = (HttpURLConnection) this.url.openConnection();
                }
                huc.setInstanceFollowRedirects(true);
                int connectTimeout = Utils.getConfigInteger("download.timeout", 60000);
                int readTimeout = Math.max(connectTimeout, 300000); // at least 5 min for large videos
                huc.setConnectTimeout(connectTimeout);
                huc.setReadTimeout(readTimeout);
                huc.setRequestProperty("accept",  "*/*");
                huc.setRequestProperty("Referer", this.url.toExternalForm()); // Sic
                huc.setRequestProperty("User-agent", AbstractRipper.USER_AGENT);
                tries += 1;
                logger.debug("Request properties: " + huc.getRequestProperties().toString());
                huc.connect();
                int statusCode = huc.getResponseCode();
                if (statusCode == 429) {
                    String retryAfterHeader = huc.getHeaderField("Retry-After");
                    int waitTimeSeconds = 5;
                    if (retryAfterHeader != null) {
                        try {
                            waitTimeSeconds = Integer.parseInt(retryAfterHeader);
                        } catch (NumberFormatException e) {
                            logger.warn("Retry-After header not a number: {}", retryAfterHeader);
                        }
                    } else {
                        waitTimeSeconds = (int) Math.pow(2, tries);
                    }
                    logger.warn("[429] Too Many Requests for {}. Waiting {} seconds before retrying", url,
                            waitTimeSeconds);
                    Utils.sleep(waitTimeSeconds * 1000L);
                    huc.disconnect();
                    continue;
                }
                if (statusCode / 100 == 4) {
                    observer.downloadErrored(url, "HTTP status code " + statusCode + " while downloading " + url.toExternalForm());
                    return;
                }
                if (statusCode / 100 == 5) {
                    throw new IOException("Retriable status code " + statusCode);
                }
                bis = new BufferedInputStream(huc.getInputStream());
                fos = new BufferedOutputStream(Files.newOutputStream(workingPath));
                while ( (bytesRead = bis.read(data)) != -1) {
                    try {
                        observer.stopCheck();
                    } catch (IOException e) {
                        observer.downloadErrored(url, "Download interrupted");
                        return;
                    }
                    fos.write(data, 0, bytesRead);
                    bytesDownloaded += bytesRead;
                    observer.setBytesCompleted((int) Math.min(bytesDownloaded, Integer.MAX_VALUE));
                    observer.sendUpdate(STATUS.COMPLETED_BYTES, (int) Math.min(bytesDownloaded, Integer.MAX_VALUE));
                }
                fos.flush();
                bis.close();
                fos.close();
                // Verify we got the full file when size was known (avoids corrupt/incomplete files)
                if (bytesTotal > 0 && bytesDownloaded != bytesTotal) {
                    logger.warn("Incomplete download: expected {} bytes, got {}. Retrying.", bytesTotal, bytesDownloaded);
                    Files.deleteIfExists(workingPath);
                    throw new IOException("Incomplete download: " + bytesDownloaded + " / " + bytesTotal);
                }
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
                try {
                    if (bis != null) { bis.close(); }
                } catch (IOException ignored) { }
                try {
                    if (fos != null) { fos.close(); }
                } catch (IOException ignored) { }
                if (huc != null) { huc.disconnect(); }
            }
            if (tries > this.retries) {
                logger.error("[!] Exceeded maximum retries (" + this.retries + ") for URL " + url);
                observer.downloadErrored(url, "Failed to download " + url.toExternalForm());
                return;
            } else if (retrySleep > 0) {
                Utils.sleep(retrySleep);
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