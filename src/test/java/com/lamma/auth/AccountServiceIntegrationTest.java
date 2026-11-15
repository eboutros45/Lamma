package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Account settings against real MySQL (skipped without LAMMA_TEST_DB_*). */
class AccountServiceIntegrationTest {

    private Database database;
    private AccountService accounts;
    private AuthenticationService auth;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T10:00:00Z"));
    private long lina;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        PinHasher hasher = new PinHasher(1_000);
        RegistrationService registration = new RegistrationService(database, hasher);
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        accounts = new AccountService(database, hasher, clock);
        auth = new AuthenticationService(database, hasher, clock);
    }

    @Test
    void nameChangesAreValidatedAndSaved() throws Exception {
        AuthenticatedUser updated = accounts.updateName(lina, "  Lina-Maria ", "Haddad Khoury");
        assertEquals("Lina-Maria", updated.firstName());
        assertEquals("Haddad Khoury", updated.lastName());
        assertEquals("9613123456", updated.phoneNumber());
        assertEquals("Lina-Maria", auth.signIn("03 123 456", "111111").firstName());
        ValidationException e = assertThrows(ValidationException.class, () -> accounts.updateName(lina, " ", "x".repeat(51)));
        assertEquals(2, e.problems().size());
        assertEquals("Lina-Maria", value("SELECT first_name FROM users WHERE user_id = " + lina));
    }

    @Test
    void phoneChangeNeedsTheCurrentPinAndAFreeValidNumber() throws Exception {
        assertMessage(AccountService.WRONG_PIN_MESSAGE, () -> accounts.changePhone(lina, "999999", "71 555 666"));
        assertMessage(AccountService.PHONE_TAKEN_MESSAGE, () -> accounts.changePhone(lina, "111111", "+961 70 123 456"));
        assertMessage(AccountService.SAME_PHONE_MESSAGE, () -> accounts.changePhone(lina, "111111", "03 123 456"));
        assertThrows(ValidationException.class, () -> accounts.changePhone(lina, "111111", "12ab"));
        assertThrows(ValidationException.class, () -> accounts.changePhone(lina, "12", "71 555 666"), "PIN format");
        assertEquals("9613123456", value("SELECT phone_number FROM users WHERE user_id = " + lina));

        AuthenticatedUser updated = accounts.changePhone(lina, "111111", "71 555 666");
        assertEquals("96171555666", updated.phoneNumber());
        assertEquals(lina, auth.signIn("+961 71 555 666", "111111").userId(), "sign in with the new number");
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "111111"), "old number no longer works");
    }

    @Test
    void pinChangeStoresOnlyANewHash() throws Exception {
        String oldHash = value("SELECT pin_hash FROM users WHERE user_id = " + lina);
        assertMessage(AccountService.WRONG_PIN_MESSAGE, () -> accounts.changePin(lina, "000000", "246802", "246802"));
        assertMessage("The new PIN and its confirmation do not match.", () -> accounts.changePin(lina, "111111", "246802", "246803"));
        assertMessage(AccountService.SAME_PIN_MESSAGE, () -> accounts.changePin(lina, "111111", "111111", "111111"));
        assertThrows(ValidationException.class, () -> accounts.changePin(lina, "111111", "12345", "12345"));
        assertThrows(ValidationException.class, () -> accounts.changePin(lina, "111111", "abcdef", "abcdef"));
        assertEquals(oldHash, value("SELECT pin_hash FROM users WHERE user_id = " + lina));

        accounts.changePin(lina, "111111", "246802", "246802");
        String newHash = value("SELECT pin_hash FROM users WHERE user_id = " + lina);
        assertFalse(newHash.equals(oldHash));
        assertFalse(newHash.contains("246802"), "never stored in plain text");
        assertTrue(newHash.startsWith("pbkdf2-sha256"));
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "111111"));
        assertEquals(lina, auth.signIn("03 123 456", "246802").userId());
    }

    @Test
    void wrongCurrentPinsCountTowardsTheSignInLockout() throws Exception {
        for (int i = 0; i < AuthenticationService.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertMessage(AccountService.WRONG_PIN_MESSAGE, () -> accounts.changePin(lina, "000000", "246802", "246802"));
        }
        assertMessage(AccountService.LOCKED_MESSAGE, () -> accounts.changePin(lina, "000000", "246802", "246802"));
        assertMessage(AccountService.LOCKED_MESSAGE, () -> accounts.changePhone(lina, "111111", "71 555 666"),
                "even the right PIN is refused while locked");
        assertThrows(AuthenticationException.class, () -> auth.signIn("03 123 456", "111111"), "sign-in is locked too");

        now.set(now.get().plus(AuthenticationService.LOCKOUT_DURATION).plusSeconds(1));
        accounts.changePin(lina, "111111", "246802", "246802"); // works again after the lock expires
        assertEquals("0", value("SELECT failed_pin_attempts FROM users WHERE user_id = " + lina));
    }

    @Test
    void aCorrectPinResetsTheCounter() throws Exception {
        assertMessage(AccountService.WRONG_PIN_MESSAGE, () -> accounts.changePin(lina, "000000", "246802", "246802"));
        assertEquals("1", value("SELECT failed_pin_attempts FROM users WHERE user_id = " + lina));
        accounts.changePin(lina, "111111", "246802", "246802");
        assertEquals("0", value("SELECT failed_pin_attempts FROM users WHERE user_id = " + lina));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertMessage(String expected, Action action) {
        assertMessage(expected, action, "");
    }

    private static void assertMessage(String expected, Action action, String why) {
        ValidationException e = assertThrows(ValidationException.class, action::run, why);
        assertEquals(expected, e.getMessage(), why);
    }

    private String value(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
