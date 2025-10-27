package com.rarchives.ripme.ripper;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.HttpStatusException;

import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Utils;

/**
 * Thread for downloading files. Includes retry logic, observer notifications,
 * and other goodies.
 */
class DownloadFileThread implements Runnable {
    private static final Logger logger = LogManager.getLogger(DownloadFileThread.class);
    private static final long MIN_FILE_SIZE_BYTES = 10 * 1024;

    private String referrer = "";
    private Map<String, String> cookies = new HashMap<>();

    private final URL url;
    private File saveAs;
    private final String prettySaveAs;
    private final AbstractRipper observer;
    private final int retries;
    private final Boolean getFileExtFromMIME;

    private final int TIMEOUT;

    private final int retrySleep;
    public DownloadFileThread(URL url, File saveAs, AbstractRipper observer, Boolean getFileExtFromMIME) {
        super();
        this.url = url;
        this.saveAs = saveAs;
        this.prettySaveAs = Utils.removeCWD(saveAs.toPath());
        this.observer = observer;
        this.retries = Utils.getConfigInteger("download.retries", 1);
        this.TIMEOUT = Utils.getConfigInteger("download.timeout", 60000);
        this.retrySleep = Utils.getConfigInteger("download.retry.sleep", 0);
        this.getFileExtFromMIME = getFileExtFromMIME;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public void setCookies(Map<String, String> cookies) {
        this.cookies = cookies;
    }

    /**
     * Attempts to download the file. Retries as needed. Notifies observers upon
     * completion/error/warn.
     */
    
    @Override
    public void run() {
        // First thing we make sure the file name doesn't have any illegal chars in it
        File targetFile = new File(
                saveAs.getParentFile().getAbsolutePath() + File.separator + Utils.sanitizeSaveAs(saveAs.getName()));
        saveAs = targetFile;
        File workingFile = targetFile;
        Path targetPath = targetFile.toPath();
        long fileSize = 0;
        int bytesTotal;
        int bytesDownloaded = 0;
        boolean resumeDownload = observer.tryResumeDownload();
        if (targetFile.exists() && resumeDownload) {
            fileSize = targetFile.length();
        }
        try {
            observer.stopCheck();
        } catch (IOException e) {
            observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
            return;
        }
        boolean useTempFile = false;
        if (targetFile.exists() && !resumeDownload && !getFileExtFromMIME
                || Utils.fuzzyExists(Paths.get(targetFile.getParent()), targetFile.getName()) && getFileExtFromMIME
                        && !resumeDownload) {
            if (Utils.getConfigBoolean("file.overwrite", false)) {
                useTempFile = true;
            } else {
                logger.info("[!] " + Utils.getLocalizedString("skipping") + " " + url + " -- "
                        + Utils.getLocalizedString("file.already.exists") + ": " + prettySaveAs);
                observer.downloadExists(url, targetPath);
                return;
            }
        }
        Path tempFilePath = null;
        if (useTempFile) {
            try {
                tempFilePath = Files.createTempFile(targetFile.getParentFile().toPath(), "ripme-", ".tmp");
                workingFile = tempFilePath.toFile();
            } catch (IOException e) {
                logger.error("[!] Failed to prepare temporary file for {}: {}", targetFile.getAbsolutePath(),
                        e.getMessage());
                observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                return;
            }
        }
        URL urlToDownload = this.url;
        boolean redirected = false;
        int tries = 0; // Number of attempts to download
        do {
            tries += 1;
            try {
                logger.info("    Downloading file: " + urlToDownload + (tries > 0 ? " Retry #" + tries : ""));
                observer.sendUpdate(STATUS.DOWNLOAD_STARTED, url.toExternalForm());

                // Setup HTTP request
                HttpURLConnection huc;
                if (this.url.toString().startsWith("https")) {
                    huc = (HttpsURLConnection) urlToDownload.openConnection();
                } else {
                    huc = (HttpURLConnection) urlToDownload.openConnection();
                }
                huc.setInstanceFollowRedirects(true);
                // It is important to set both ConnectTimeout and ReadTimeout. If you don't then
                // ripme will wait forever
                // for the server to send data after connecting.
                huc.setConnectTimeout(TIMEOUT);
                huc.setReadTimeout(TIMEOUT);
                huc.setRequestProperty("accept", "*/*");
                if (!referrer.equals("")) {
                    huc.setRequestProperty("Referer", referrer); // Sic
                }
                huc.setRequestProperty("User-agent", AbstractRipper.USER_AGENT);
                StringBuilder cookie = new StringBuilder();
                for (String key : cookies.keySet()) {
                    if (!cookie.toString().equals("")) {
                        cookie.append("; ");
                    }
                    cookie.append(key).append("=").append(cookies.get(key));
                }
                huc.setRequestProperty("Cookie", cookie.toString());
                if (resumeDownload) {
                    if (fileSize != 0) {
                        huc.setRequestProperty("Range", "bytes=" + fileSize + "-");
                    }
                }
                logger.debug(Utils.getLocalizedString("request.properties") + ": " + huc.getRequestProperties());
                huc.connect();

                int statusCode = huc.getResponseCode();
                logger.debug("Status code: " + statusCode);
                // If the server doesn't allow resuming downloads error out
                if (statusCode != 206 && resumeDownload && targetFile.exists()) {
                    // TODO find a better way to handle servers that don't support resuming
                    // downloads then just erroring out
                    throw new IOException(Utils.getLocalizedString("server.doesnt.support.resuming.downloads"));
                }
                if (statusCode / 100 == 3) { // 3xx Redirect
                    if (!redirected) {
                        // Don't increment retries on the first redirect
                        tries--;
                        redirected = true;
                    }
                    String location = huc.getHeaderField("Location");
                    urlToDownload = new URI(location).toURL();
                    // Throw exception so download can be retried
                    throw new IOException("Redirect status code " + statusCode + " - redirect to " + location);
                }
                if (statusCode == 429) { // Too Many Requests
                    logger.warn("[!] Received 429 Too Many Requests for " + url);
                    String retryAfterHeader = huc.getHeaderField("Retry-After");
                    int waitTimeSeconds = 5; // Default wait time

                    if (retryAfterHeader != null) {
                        try {
                            waitTimeSeconds = Integer.parseInt(retryAfterHeader);
                        } catch (NumberFormatException e) {
                            logger.warn("Retry-After header not a number: " + retryAfterHeader);
                        }
                    } else {
                        // Basic exponential backoff
                        waitTimeSeconds = (int) Math.pow(2, tries);
                    }

                    logger.info("Waiting for " + waitTimeSeconds + " seconds before retrying...");
                    Utils.sleep(waitTimeSeconds * 1000L);
                    continue; // Retry the loop
                } else if (statusCode / 100 == 4) {
                    logger.error("[!] " + Utils.getLocalizedString("nonretriable.status.code") + " " + statusCode
                            + " while downloading from " + url);
                    observer.downloadErrored(url, Utils.getLocalizedString("nonretriable.status.code") + " "
                            + statusCode + " while downloading " + url.toExternalForm());
                    return; // Not retriable, drop out.
                }
                if (statusCode / 100 == 5) { // 5xx errors
                    observer.downloadErrored(url, Utils.getLocalizedString("retriable.status.code") + " " + statusCode
                            + " while downloading " + url.toExternalForm());
                    // Throw exception so download can be retried
                    throw new IOException(Utils.getLocalizedString("retriable.status.code") + " " + statusCode);
                }
                if (huc.getContentLength() == 503 && urlToDownload.getHost().endsWith("imgur.com")) {
                    // Imgur image with 503 bytes is "404"
                    logger.error("[!] Imgur image is 404 (503 bytes long): " + url);
                    observer.downloadErrored(url, "Imgur image is 404: " + url.toExternalForm());
                    return;
                }

                // If the ripper is using the bytes progress bar set bytesTotal to
                // huc.getContentLength()
                if (observer.useByteProgessBar()) {
                    bytesTotal = huc.getContentLength();
                    observer.setBytesTotal(bytesTotal);
                    observer.sendUpdate(STATUS.TOTAL_BYTES, bytesTotal);
                    logger.debug("Size of file at " + this.url + " = " + bytesTotal + "b");
                }

                // Save file
                InputStream bis;
                bis = new BufferedInputStream(huc.getInputStream());

                // Check if we should get the file ext from the MIME type
                if (getFileExtFromMIME) {
                    String fileExt = URLConnection.guessContentTypeFromStream(bis);
                    if (fileExt != null) {
                        fileExt = fileExt.replaceAll("image/", "");
                    } else {
                        logger.error("Was unable to get content type from stream");
                        // Try to get the file type from the magic number
                        byte[] magicBytes = new byte[8];
                        bis.read(magicBytes, 0, 5);
                        bis.reset();
                        fileExt = Utils.getEXTFromMagic(magicBytes);
                        if (fileExt == null) {
                            logger.error(Utils.getLocalizedString("was.unable.to.get.content.type.using.magic.number"));
                            logger.error(
                                    Utils.getLocalizedString("magic.number.was") + ": " + Arrays.toString(magicBytes));
                        }
                    }
                    if (fileExt != null) {
                        targetFile = new File(targetFile.toString() + "." + fileExt);
                        targetPath = targetFile.toPath();
                        saveAs = targetFile;
                        if (!useTempFile) {
                            workingFile = targetFile;
                        }
                    }
                }
                // If we're resuming a download we append data to the existing file
                OutputStream fos = null;
                if (statusCode == 206) {
                    fos = new FileOutputStream(workingFile, true);
                } else {
                    try {
                        fos = new FileOutputStream(workingFile);
                    } catch (FileNotFoundException e) {
                        // We do this because some filesystems have a max name length
                        if (e.getMessage().contains("File name too long")) {
                            logger.error("The filename " + saveAs.getName()
                                    + " is to long to be saved on this file system.");
                            logger.info("Shortening filename");
                            String[] saveAsSplit = workingFile.getName().split("\\.");
                            // Get the file extension so when we shorten the file name we don't cut off the
                            // file extension
                            String fileExt = saveAsSplit[saveAsSplit.length - 1];
                            // The max limit for filenames on Linux with Ext3/4 is 255 bytes
                            logger.info(workingFile.getName().substring(0, 254 - fileExt.length()) + fileExt);
                            String filename = workingFile.getName().substring(0, 254 - fileExt.length()) + "." + fileExt;
                            // We can't just use the new file name as the saveAs because the file name
                            // doesn't include the
                            // users save path, so we get the user save path from the old saveAs
                            targetFile = new File(targetFile.getParentFile().getAbsolutePath() + File.separator + filename);
                            targetPath = targetFile.toPath();
                            saveAs = targetFile;
                            workingFile = useTempFile ? workingFile : targetFile;
                            fos = new FileOutputStream(workingFile);
                        } else if (targetFile.getAbsolutePath().length() > 259 && Utils.isWindows()) {
                            // This if is for when the file path has gone above 260 chars which windows does
                            // not allow
                            fos = Files.newOutputStream(
                                    Utils.shortenSaveAsWindows(targetFile.getParentFile().getPath(), targetFile.getName()));
                            assert fos != null: "After shortenSaveAsWindows: " + targetFile.getAbsolutePath();
                        }
                        assert fos != null: e.getStackTrace();
                    }
                }
                byte[] data = new byte[1024 * 256];
                int bytesRead;
                boolean shouldSkipFileDownload = huc.getContentLength() / 1000000 >= 10 && AbstractRipper.isThisATest();
                // If this is a test rip we skip large downloads
                if (shouldSkipFileDownload) {
                    logger.debug("Not downloading whole file because it is over 10mb and this is a test");
                } else {
                    while ((bytesRead = bis.read(data)) != -1) {
                        try {
                            observer.stopCheck();
                        } catch (IOException e) {
                            observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                            return;
                        }
                        fos.write(data, 0, bytesRead);
                        if (observer.useByteProgessBar()) {
                            bytesDownloaded += bytesRead;
                            observer.setBytesCompleted(bytesDownloaded);
                            observer.sendUpdate(STATUS.COMPLETED_BYTES, bytesDownloaded);
                        }
                    }
                }
                bis.close();
                fos.close();

                File fileToInspect = workingFile;
                long finalSize = fileToInspect.length();
                if (!shouldSkipFileDownload && finalSize < MIN_FILE_SIZE_BYTES) {
                    logger.warn("[!] Deleting {} ({} bytes) because it is smaller than {} bytes", prettySaveAs, finalSize,
                            MIN_FILE_SIZE_BYTES);
                    if (!fileToInspect.delete()) {
                        logger.warn("[!] Failed to delete {} after size check", fileToInspect.getAbsolutePath());
                    }
                    observer.downloadErrored(url, "File smaller than 10KB (deleted)");
                    return;
                }
                if (!shouldSkipFileDownload && !observer.registerDownloadHash(fileToInspect.toPath())) {
                    logger.warn("[!] Deleting {} because its hash matches a previously downloaded file", prettySaveAs);
                    if (!fileToInspect.delete()) {
                        logger.warn("[!] Failed to delete duplicate file {}", fileToInspect.getAbsolutePath());
                    }
                    observer.downloadErrored(url, "Duplicate file (deleted)");
                    return;
                }
                if (useTempFile) {
                    try {
                        Files.move(fileToInspect.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                        workingFile = targetFile;
                    } catch (IOException moveException) {
                        logger.error("[!] Failed to replace existing file {}: {}", targetPath, moveException.getMessage());
                        if (!fileToInspect.delete()) {
                            logger.warn("[!] Failed to delete temporary file {}", fileToInspect.getAbsolutePath());
                        }
                        observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                        return;
                    }
                }
                break; // Download successful: break out of infinite loop
            } catch (SocketTimeoutException timeoutEx) {
                // Handle the timeout
                logger.error("[!] " + url.toExternalForm() + " timedout!");
                // Download failed, break out of loop
                break;
            } catch (HttpStatusException hse) {
                logger.debug(Utils.getLocalizedString("http.status.exception"), hse);
                logger.error("[!] HTTP status " + hse.getStatusCode() + " while downloading from " + urlToDownload);
                Set<Integer> skipStatusCodes = Set.of(404, 410);
                if (skipStatusCodes.contains(hse.getStatusCode()) && Utils.getConfigBoolean("errors.skip404", false)) {
                    observer.downloadErrored(url,
                            "HTTP status code " + hse.getStatusCode() + " while downloading " + url.toExternalForm());
                    return;
                }
            } catch (IOException | URISyntaxException e) {
                logger.debug("IOException", e);
                logger.error("[!] " + Utils.getLocalizedString("exception.while.downloading.file") + ": " + url + " - "
                        + e.getMessage());
            } catch (NullPointerException npe){

                logger.error("[!] " + Utils.getLocalizedString("failed.to.download") + " for URL " + url);
                observer.downloadErrored(url,
                        Utils.getLocalizedString("failed.to.download") + " " + url.toExternalForm());
                return;

            }
            if (tries > this.retries) {
                logger.error("[!] " + Utils.getLocalizedString("exceeded.maximum.retries") + " (" + this.retries
                        + ") for URL " + url);
                observer.downloadErrored(url,
                        Utils.getLocalizedString("failed.to.download") + " " + url.toExternalForm());
                return;
            } else {
                if (retrySleep > 0) {
                    Utils.sleep(retrySleep);
                }
            }
        } while (true);
        observer.downloadCompleted(url, saveAs.toPath());
        logger.info("[+] Saved " + url + " as " + this.prettySaveAs);
    }

}
