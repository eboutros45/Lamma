package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import java.net.ServerSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When MySQL is down, Sign In and Sign Up show a plain message without connection details
 * (JDBC URL, host, port, config keys). No MySQL needed: uses a port where nothing listens.
 */
class DatabaseOutageMessagesTest {

    private Database unreachable;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        unreachable = new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + port + "/lamma?connectTimeout=2000", "lamma_app", "pw"));
    }

    @Test
    void signInDuringOutageGivesPlainMessage() {
        AuthenticationService auth = new AuthenticationService(unreachable, new PinHasher(1_000));

        DatabaseException e = assertThrows(DatabaseException.class, () -> auth.signIn("03 123 456", "123456"));

        assertTrue(e.getMessage().startsWith("Sign-in could not be completed"), e.getMessage());
        assertNoConnectionDetails(e.getMessage());
    }

    @Test
    void registrationDuringOutageGivesPlainMessage() {
        RegistrationService registration = new RegistrationService(unreachable, new PinHasher(1_000));

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456"));

        assertTrue(e.getMessage().startsWith("Registration could not be saved"), e.getMessage());
        assertNoConnectionDetails(e.getMessage());
    }

    private void assertNoConnectionDetails(String message) {
        assertFalse(message.contains("jdbc:"), message);
        assertFalse(message.contains("127.0.0.1"), message);
        assertFalse(message.contains(String.valueOf(port)), message);
        assertFalse(message.contains("db.url"), message);
    }
}
