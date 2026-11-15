package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sign-in against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class AuthenticationIntegrationTest {

    private final PinHasher hasher = new PinHasher(1_000);
    private final TestClock clock = new TestClock();
    private Database database;
    private AuthenticationService auth;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        userId = new RegistrationService(database, hasher)
                .registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456").userId();
        auth = new AuthenticationService(database, hasher, clock);
    }

    @Test
    void correctCredentialsAuthenticate() throws Exception {
        AuthenticatedUser user = auth.signIn("03 123 456", "123456");
        assertEquals(userId, user.userId());
        assertEquals("Lina", user.firstName());
        assertEquals("Haddad", user.lastName());
        assertEquals("9613123456", user.phoneNumber());
    }

    @Test
    void equivalentPhoneFormatsReachTheSameAccount() throws Exception {
        assertEquals(userId, auth.signIn("+961 3 123 456", "123456").userId());
        assertEquals(userId, auth.signIn("9613123456", "123456").userId());
        assertEquals(userId, auth.signIn("00961-3-123-456", "123456").userId());
    }

    @Test
    void wrongPinAndUnknownPhoneGiveTheSameGenericMessage() {
        AuthenticationException wrongPin = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 123 456", "000000"));
        AuthenticationException unknownPhone = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 999 999", "123456"));

        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, wrongPin.getMessage());
        assertEquals(wrongPin.getMessage(), unknownPhone.getMessage());
    }

    @Test
    void badPinFormatIsAValidationErrorNotACountedAttempt() throws Exception {
        assertThrows(ValidationException.class, () -> auth.signIn("03 123 456", "12345"));
        assertEquals(0, failedAttempts());
    }

    @Test
    void fiveWrongPinsLockTheAccountEvenForTheCorrectPin() throws Exception {
        for (int i = 1; i < AuthenticationService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
            assertEquals(i, failedAttempts());
        }
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));

        // Locked: the correct PIN is refused with the same generic message.
        AuthenticationException locked = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 123 456", "123456"));
        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, locked.getMessage());

        // Still locked one second before the lock ends.
        clock.advance(AuthenticationService.LOCKOUT_DURATION.minusSeconds(1));
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "123456"));

        // After the lockout, the correct PIN works and clears the lock.
        clock.advance(Duration.ofSeconds(2));
        assertEquals(userId, auth.signIn("03 123 456", "123456").userId());
        assertEquals(0, failedAttempts());
        assertNull(lockedUntil());
    }

    @Test
    void successfulSignInResetsTheFailedAttemptCounter() throws Exception {
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
        assertEquals(2, failedAttempts());

        auth.signIn("03 123 456", "123456");
        assertEquals(0, failedAttempts());
    }

    @Test
    void wrongPinAfterAnExpiredLockStartsCountingAgain() throws Exception {
        for (int i = 0; i < AuthenticationService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
        }
        clock.advance(AuthenticationService.LOCKOUT_DURATION.plusSeconds(1));

        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
        assertEquals(1, failedAttempts());
        assertNull(lockedUntil());
    }

    @Test
    void signInAndSignOutUpdateTheSession() throws Exception {
        UserSession session = new UserSession();
        session.signIn(auth.signIn("03 123 456", "123456"));
        assertEquals(userId, session.currentUser().orElseThrow().userId());
        session.signOut();
        assertEquals(false, session.isSignedIn());
    }

    private int failedAttempts() throws Exception {
        return ((Number) column("failed_pin_attempts")).intValue();
    }

    private Object lockedUntil() throws Exception {
        return column("locked_until");
    }

    private Object column(String name) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT " + name + " FROM users WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-15T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
