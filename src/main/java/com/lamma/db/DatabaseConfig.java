package com.lamma.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC connection settings (URL, user, password).
 *
 * <p>Settings are read from {@code config/database.properties} (git-ignored), and any of
 * the environment variables {@code LAMMA_DB_URL}, {@code LAMMA_DB_USER},
 * {@code LAMMA_DB_PASSWORD} override the file. If all three environment variables are
 * set, the file is optional.
 */
public final class DatabaseConfig {

    public static final Path DEFAULT_CONFIG_FILE = Path.of("config", "database.properties");

    private final String url;
    private final String user;
    private final String password;

    public DatabaseConfig(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Loads settings from the default file location and the real environment. */
    public static DatabaseConfig load() throws DatabaseException {
        return load(DEFAULT_CONFIG_FILE, System.getenv());
    }

    /** Loads settings from the given file, letting the given environment override it. */
    public static DatabaseConfig load(Path configFile, Map<String, String> env) throws DatabaseException {
        Properties props = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                throw new DatabaseException("Could not read database settings file " + configFile.toAbsolutePath()
                        + ": " + e.getMessage(), e);
            }
        }

        String url = pick(env.get("LAMMA_DB_URL"), props.getProperty("db.url"));
        String user = pick(env.get("LAMMA_DB_USER"), props.getProperty("db.user"));
        String password = pick(env.get("LAMMA_DB_PASSWORD"), props.getProperty("db.password"));

        if (url == null || user == null || password == null) {
            throw new DatabaseException("Database settings are missing. Copy config/database.properties.example to "
                    + configFile + " and fill in db.url, db.user and db.password "
                    + "(or set LAMMA_DB_URL, LAMMA_DB_USER and LAMMA_DB_PASSWORD). Looked for: "
                    + configFile.toAbsolutePath());
        }
        return new DatabaseConfig(url, user, password);
    }

    private static String pick(String override, String fileValue) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return (fileValue == null || fileValue.isBlank()) ? null : fileValue.trim();
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Never includes the password, so it is safe to print or log. */
    @Override
    public String toString() {
        return "DatabaseConfig[url=" + url + ", user=" + user + "]";
    }
}
