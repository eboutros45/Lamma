package com.lamma.db;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Verifies that connection problems become readable DatabaseExceptions (no MySQL needed). */
class DatabaseConnectionFailureTest {

    private final Database database =
            new Database(new DatabaseConfig("jdbc:mysql://localhost:3306/lamma", "lamma_app", "pw"));

    @Test
    void unreachableServerGivesClearMessage() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort(); // closed again, so nothing listens here
        }
        Database unreachable = new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + freePort + "/lamma?connectTimeout=2000", "lamma_app", "pw"));

        DatabaseException e = assertThrows(DatabaseException.class, unreachable::getConnection);

        assertTrue(e.getMessage().contains("Cannot reach the MySQL server"), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void unreachableServerDuringFullCheckGivesClearMessage() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        Database unreachable = new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + freePort + "/lamma?connectTimeout=2000", "lamma_app", "pw"));

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> DatabaseStatus.checkAndMigrate(unreachable));

        assertTrue(e.getMessage().contains("Make sure MySQL is installed and running"), e.getMessage());
    }

    @Test
    void wrongPasswordIsExplained() {
        String message = database.describeConnectionFailure(new SQLException("Access denied", "28000", 1045));
        assertTrue(message.contains("username or password"), message);
    }

    @Test
    void missingDatabaseIsExplained() {
        String message = database.describeConnectionFailure(new SQLException("Unknown database", "42000", 1049));
        assertTrue(message.contains("create_database.sql"), message);
    }

    @Test
    void invalidUrlIsExplained() {
        Database badUrl = new Database(new DatabaseConfig("not-a-jdbc-url", "u", "p"));

        DatabaseException e = assertThrows(DatabaseException.class, badUrl::getConnection);

        assertTrue(e.getMessage().contains("not a valid MySQL JDBC URL"), e.getMessage());
    }
}
