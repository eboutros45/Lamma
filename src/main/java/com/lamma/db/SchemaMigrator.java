package com.lamma.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates and evolves the database schema from versioned SQL scripts.
 *
 * <p>How it works:
 * <ol>
 *   <li>A small {@code schema_version} table records which scripts have already run.</li>
 *   <li>Each script in {@link #MIGRATIONS} that is not recorded yet is executed in order,
 *       then recorded.</li>
 * </ol>
 * Running it again does nothing, so it is safe to run every time the application starts.
 *
 * <p>To change the schema later, add a new file such as
 * {@code V002__add_login_attempt_fields.sql} to {@code src/main/resources/db/migration}
 * and append it to {@link #MIGRATIONS}. Never edit a script that has already been applied.
 */
public final class SchemaMigrator {

    private static final String MIGRATION_FOLDER = "/db/migration/";

    /** All migration scripts, in the order they must run. Version = position in this list + 1. */
    static final List<String> MIGRATIONS = List.of(
            "V001__create_users_and_wallets.sql"
    );

    private final Database database;

    public SchemaMigrator(Database database) {
        this.database = database;
    }

    /** The newest schema version this build of the application knows about. */
    public static int latestVersion() {
        return MIGRATIONS.size();
    }

    /**
     * Applies all pending migrations.
     *
     * @return the number of migrations applied (0 if the schema was already up to date)
     */
    public int migrate() throws DatabaseException {
        try (Connection conn = database.getConnection()) {
            createVersionTableIfMissing(conn);
            int current = currentVersion(conn);
            if (current > latestVersion()) {
                throw new DatabaseException("The database schema (version " + current
                        + ") is newer than this application (version " + latestVersion()
                        + "). Update the application.");
            }
            int applied = 0;
            for (int version = current + 1; version <= latestVersion(); version++) {
                String script = MIGRATIONS.get(version - 1);
                applyMigration(conn, version, script);
                applied++;
            }
            return applied;
        } catch (SQLException e) {
            throw new DatabaseException("Setting up the database schema failed: " + e.getMessage(), e);
        }
    }

    /** Reads the schema version currently recorded in the database (0 = no migrations applied). */
    public int currentVersion() throws DatabaseException {
        try (Connection conn = database.getConnection()) {
            createVersionTableIfMissing(conn);
            return currentVersion(conn);
        } catch (SQLException e) {
            throw new DatabaseException("Could not read the database schema version: " + e.getMessage(), e);
        }
    }

    private static void createVersionTableIfMissing(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + " version INT NOT NULL PRIMARY KEY,"
                    + " script_name VARCHAR(255) NOT NULL,"
                    + " applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE = InnoDB");
        }
    }

    private static int currentVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void applyMigration(Connection conn, int version, String script)
            throws SQLException, DatabaseException {
        // Note: MySQL commits each CREATE/ALTER TABLE immediately, so a script that fails
        // halfway cannot be rolled back automatically. Keep scripts small and test them on
        // the lamma_test database first.
        List<String> statements = splitStatements(readScript(script));
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Migration " + script + " failed: " + e.getMessage(), e);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, script_name) VALUES (?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, script);
            ps.executeUpdate();
        }
    }

    private static String readScript(String script) throws DatabaseException {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(MIGRATION_FOLDER + script)) {
            if (in == null) {
                throw new DatabaseException("Migration script not found on the classpath: " + script);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException("Could not read migration script " + script, e);
        }
    }

    /**
     * Splits a script into individual statements. Lines starting with "--" are comments;
     * a statement ends at a line whose last character is ";".
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                statements.add(sql.substring(0, sql.length() - 1).trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }
}
