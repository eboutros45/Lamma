package com.lamma.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * One-stop database check used by both the JavaFX shell and the command-line setup tool:
 * load settings, connect, apply pending migrations, and report what was found.
 */
public final class DatabaseStatus {

    private DatabaseStatus() {
    }

    /** Result of a successful check. */
    public record Report(String serverVersion, String databaseName, int schemaVersion, int migrationsApplied) {

        public String summary() {
            return "Connected to MySQL " + serverVersion + ", database '" + databaseName
                    + "', schema version " + schemaVersion
                    + (migrationsApplied > 0 ? " (" + migrationsApplied + " migration(s) applied now)." : ".");
        }
    }

    /** Connects, brings the schema up to date, and returns a report. */
    public static Report checkAndMigrate(Database database) throws DatabaseException {
        String serverVersion;
        String databaseName;
        try (Connection conn = database.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            serverVersion = meta.getDatabaseProductVersion();
            databaseName = conn.getCatalog();
        } catch (SQLException e) {
            throw new DatabaseException("Connected to MySQL but could not read server details: " + e.getMessage(), e);
        }
        if (databaseName == null) {
            throw new DatabaseException("The database URL does not name a database. "
                    + "Use a URL like jdbc:mysql://localhost:3306/lamma");
        }
        SchemaMigrator migrator = new SchemaMigrator(database);
        int applied = migrator.migrate();
        return new Report(serverVersion, databaseName, migrator.currentVersion(), applied);
    }

    /** Convenience: load the default configuration and run {@link #checkAndMigrate(Database)}. */
    public static Report checkAndMigrate() throws DatabaseException {
        return checkAndMigrate(new Database(DatabaseConfig.load()));
    }
}
