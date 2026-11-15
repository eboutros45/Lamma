package com.lamma.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens JDBC connections to the LAMMA MySQL database and turns low-level
 * {@link SQLException}s into understandable {@link DatabaseException}s.
 *
 * <p>Callers own the connection they receive and should close it with try-with-resources.
 */
public final class Database {

    // MySQL server error codes (see MySQL "Server Error Message Reference").
    private static final int ER_ACCESS_DENIED = 1045;
    private static final int ER_DBACCESS_DENIED = 1044;
    private static final int ER_BAD_DB = 1049;

    private final DatabaseConfig config;

    public Database(DatabaseConfig config) {
        this.config = config;
    }

    public DatabaseConfig config() {
        return config;
    }

    public Connection getConnection() throws DatabaseException {
        if (!config.url().startsWith("jdbc:mysql://")) {
            throw new DatabaseException("The database URL is not a valid MySQL JDBC URL: " + config.url()
                    + ". It should look like jdbc:mysql://localhost:3306/lamma");
        }
        try {
            return DriverManager.getConnection(config.url(), config.user(), config.password());
        } catch (SQLException e) {
            throw new DatabaseException(describeConnectionFailure(e), e);
        }
    }

    /** Builds a user-facing explanation of why connecting to MySQL failed. */
    String describeConnectionFailure(SQLException e) {
        String sqlState = e.getSQLState() == null ? "" : e.getSQLState();
        int code = e.getErrorCode();

        if (code == ER_ACCESS_DENIED) {
            return "MySQL rejected the username or password for user '" + config.user()
                    + "'. Check db.user and db.password in config/database.properties.";
        }
        if (code == ER_DBACCESS_DENIED) {
            return "MySQL user '" + config.user() + "' is not allowed to use this database. "
                    + "Run database/create_database.sql as a MySQL administrator.";
        }
        if (code == ER_BAD_DB) {
            return "The LAMMA database does not exist yet. "
                    + "Run database/create_database.sql as a MySQL administrator.";
        }
        if (sqlState.startsWith("08")) {
            return "Cannot reach the MySQL server (" + urlWithoutOptions()
                    + "). Make sure MySQL is installed and running, and that db.url has the right host and port.";
        }
        return "Could not connect to MySQL: " + e.getMessage();
    }

    /** The JDBC URL without its "?option=..." part, for shorter messages. */
    private String urlWithoutOptions() {
        int question = config.url().indexOf('?');
        return question < 0 ? config.url() : config.url().substring(0, question);
    }
}
