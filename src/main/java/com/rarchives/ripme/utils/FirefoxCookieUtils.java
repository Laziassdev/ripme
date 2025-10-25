package com.rarchives.ripme.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility helpers for discovering Firefox profiles and reading cookies from them.
 */
public final class FirefoxCookieUtils {
    private static final Logger logger = LogManager.getLogger(FirefoxCookieUtils.class);

    private FirefoxCookieUtils() {
        // Utility class
    }

    /**
     * Attempts to load the SQLite JDBC driver.
     *
     * @return {@code true} if the driver could be loaded; {@code false} otherwise.
     */
    public static boolean isSQLiteDriverAvailable() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException e) {
            logger.debug("SQLite JDBC driver not available", e);
            return false;
        }
    }

    /**
     * Discovers Firefox profile directories for the current platform.
     *
     * @return A set of profile paths. The set may be empty if no profiles were found.
     */
    public static Set<Path> discoverFirefoxProfiles() {
        Set<Path> profilePaths = new LinkedHashSet<>();

        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return profilePaths;
        }

        List<Path> iniCandidates = new ArrayList<>();
        iniCandidates.add(Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "profiles.ini"));
        iniCandidates.add(Paths.get(userHome, "Library", "Application Support", "Firefox", "profiles.ini"));
        iniCandidates.add(Paths.get(userHome, ".mozilla", "firefox", "profiles.ini"));

        for (Path iniPath : iniCandidates) {
            if (!Files.exists(iniPath)) {
                continue;
            }
            try {
                profilePaths.addAll(readProfilesFromIni(iniPath));
            } catch (IOException e) {
                logger.debug("Failed to parse Firefox profiles.ini at {}", iniPath, e);
            }
        }

        return profilePaths;
    }

    private static Set<Path> readProfilesFromIni(Path iniPath) throws IOException {
        Set<Path> profiles = new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(iniPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return profiles;
        }

        Path baseDir = iniPath.getParent();
        boolean isRelative = true;

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }

            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("[")) {
                isRelative = true;
                continue;
            }

            if (line.startsWith("IsRelative=")) {
                isRelative = !"0".equals(line.substring("IsRelative=".length()).trim());
                continue;
            }

            if (line.startsWith("Path=")) {
                String profileEntry = line.substring("Path=".length()).trim();
                if (profileEntry.isEmpty()) {
                    continue;
                }

                Path profilePath = isRelative && baseDir != null ? baseDir.resolve(profileEntry) : Paths.get(profileEntry);
                Path normalized = profilePath.normalize();
                if (Files.exists(normalized)) {
                    profiles.add(normalized);
                } else {
                    logger.debug("Firefox profile path {} from {} does not exist", normalized, iniPath);
                }
            }
        }

        return profiles;
    }

    /**
     * Reads cookies from the specified Firefox profile.
     *
     * @param profilePath       The Firefox profile directory.
     * @param hostLikePatterns  SQL LIKE patterns that should match cookie hosts.
     * @return A map of cookie names to values. The map is empty if no matching cookies were found.
     */
    public static Map<String, String> readCookiesFromProfile(Path profilePath, List<String> hostLikePatterns) {
        Map<String, String> cookies = new LinkedHashMap<>();

        if (profilePath == null || hostLikePatterns == null || hostLikePatterns.isEmpty()) {
            return cookies;
        }

        Path sqlitePath = profilePath.resolve("cookies.sqlite");
        if (!Files.exists(sqlitePath)) {
            return cookies;
        }

        Path tempCopy = null;
        try {
            tempCopy = Files.createTempFile("ripme-firefox-cookies", ".sqlite");
            Files.copy(sqlitePath, tempCopy, StandardCopyOption.REPLACE_EXISTING);

            String sql = buildCookieQuery(hostLikePatterns.size());
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempCopy);
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < hostLikePatterns.size(); i++) {
                    stmt.setString(i + 1, hostLikePatterns.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        String value = rs.getString("value");
                        if (name == null || name.isBlank() || value == null || value.isEmpty()) {
                            continue;
                        }
                        cookies.put(name, value);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            logger.debug("Unable to read Firefox cookies from profile {}", profilePath, e);
        } finally {
            if (tempCopy != null) {
                try {
                    Files.deleteIfExists(tempCopy);
                } catch (IOException e) {
                    logger.debug("Failed to delete temporary Firefox cookie copy", e);
                }
            }
        }

        return cookies;
    }

    private static String buildCookieQuery(int patternCount) {
        StringBuilder sql = new StringBuilder("SELECT name, value FROM moz_cookies WHERE ");
        for (int i = 0; i < patternCount; i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("host LIKE ?");
        }
        sql.append(" ORDER BY lastAccessed DESC");
        return sql.toString();
    }

    /**
     * Builds a HTTP Cookie header string from the supplied cookie map.
     *
     * @param cookies Cookie name/value pairs.
     * @return A header string or {@code null} if the input is empty.
     */
    public static String toCookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }

            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return header.length() > 0 ? header.toString() : null;
    }
}

