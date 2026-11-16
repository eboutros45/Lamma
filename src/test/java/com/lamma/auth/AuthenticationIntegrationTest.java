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
    void wrongPinsSayHowManyAttemptsAreLeft() {
        // P05B: the phone step already tells whether the number has an account, so the PIN step
        // can be specific. An unknown number given straight to signIn keeps the generic message.
        AuthenticationException wrongPin = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 123 456", "000000"));
        assertEquals(AuthenticationService.wrongPinMessage(4), wrongPin.getMessage());
        assertEquals("Incorrect PIN. 4 attempts left before sign-in is paused for 5 minutes.", wrongPin.getMessage());
        AuthenticationException unknownPhone = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 999 999", "123456"));
        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, unknownPhone.getMessage());
    }

    @Test
    void phoneStepFindsExistingAccountsInAnyFormatAndRefusesOthers() throws Exception {
        assertEquals("9613123456", auth.findAccount("03 123 456"));
        assertEquals("9613123456", auth.findAccount("+961 3 123 456"));
        assertEquals(AuthenticationService.NO_ACCOUNT_MESSAGE,
                assertThrows(ValidationException.class, () -> auth.findAccount("03 999 999")).getMessage());
        assertThrows(ValidationException.class, () -> auth.findAccount("12ab"), "invalid format");
        assertThrows(ValidationException.class, () -> auth.findAccount(""), "required");
        assertEquals(0, failedAttempts(), "looking up a number is not a PIN attempt");
    }

    @Test
    void phoneLookupsAreRateLimited() throws Exception {
        for (int i = 0; i < AuthenticationService.MAX_LOOKUPS_PER_MINUTE - 4; i++) {
            assertThrows(ValidationException.class, () -> auth.findAccount("03 999 999"));
        }
        for (int i = 0; i < 4; i++) {
            auth.findAccount("03 123 456");
        }
        assertEquals(AuthenticationService.LOOKUP_LIMIT_MESSAGE,
                assertThrows(ValidationException.class, () -> auth.findAccount("03 123 456")).getMessage(),
                "even a real number is refused once the limit is reached");
        clock.advance(Duration.ofSeconds(61));
        assertEquals("9613123456", auth.findAccount("03 123 456"), "allowed again after a minute");
    }

    @Test
    void badPinFormatIsAValidationErrorNotACountedAttempt() throws Exception {
        assertThrows(ValidationException.class, () -> auth.signIn("03 123 456", "12345"));
        assertEquals(0, failedAttempts());
    }

    @Test
    void fiveWrongPinsLockTheAccountEvenForTheCorrectPin() throws Exception {
        for (int i = 1; i < AuthenticationService.MAX_FAILED_ATTEMPTS; i++) {
            AuthenticationException e = assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "000000"));
            assertEquals(AuthenticationService.wrongPinMessage(AuthenticationService.MAX_FAILED_ATTEMPTS - i), e.getMessage());
            assertEquals(i, failedAttempts());
        }
        assertEquals(AuthenticationService.LOCKED_MESSAGE, assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 123 456", "000000")).getMessage(), "the fifth wrong PIN locks");

        // Locked: even the correct PIN is refused (without being checked).
        AuthenticationException locked = assertThrows(AuthenticationException.class,
                () -> auth.signIn("03 123 456", "123456"));
        assertEquals(AuthenticationService.LOCKED_MESSAGE, locked.getMessage());

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
