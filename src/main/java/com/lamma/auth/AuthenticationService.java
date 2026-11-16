package com.lamma.auth;

import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Common sign-in for every account type: phone number + six-digit PIN.
 *
 * <p>Failed-attempt protection: after {@link #MAX_FAILED_ATTEMPTS} wrong PINs in a row,
 * the account is locked for {@link #LOCKOUT_DURATION}. While locked, the PIN is not even
 * checked. A successful sign-in resets the counter.
 *
 * <p>Two steps (P05B): {@link #findAccount} checks the phone number first (the sign-in screen
 * then asks for the PIN on a separate screen), and {@link #signIn} checks the PIN. Because the
 * first step tells whether a number has an account (accepted for this project, as Send Money
 * already shows registered names), the PIN step can say how many attempts are left or that the
 * account is locked. Number lookups are rate-limited ({@link #MAX_LOOKUPS_PER_MINUTE}) so the
 * screen can't be used to list accounts quickly. An unknown number at {@link #signIn} still costs
 * one PIN-hash computation and gets the generic message.
 */
public class AuthenticationService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(5);

    public static final String INVALID_CREDENTIALS_MESSAGE =
            "Incorrect phone number or PIN. After " + MAX_FAILED_ATTEMPTS
            + " failed attempts, sign-in is paused for " + LOCKOUT_DURATION.toMinutes() + " minutes.";

    public static final String NO_ACCOUNT_MESSAGE =
            "No LAMMA account uses this phone number. Check the number, or create an account.";
    public static final String LOCKED_MESSAGE = "Too many incorrect PINs. For your security, sign-in is paused for "
            + LOCKOUT_DURATION.toMinutes() + " minutes. Try again later.";
    static final String LOOKUP_LIMIT_MESSAGE = "Too many attempts. Please wait a minute and try again.";
    /** Most phone-number lookups per minute on this computer (a brake on listing accounts). */
    public static final int MAX_LOOKUPS_PER_MINUTE = 10;

    /** "Incorrect PIN. 3 attempts left before sign-in is paused for 5 minutes." */
    public static String wrongPinMessage(int attemptsLeft) {
        return "Incorrect PIN. " + attemptsLeft + (attemptsLeft == 1 ? " attempt" : " attempts")
                + " left before sign-in is paused for " + LOCKOUT_DURATION.toMinutes() + " minutes.";
    }

    private final java.util.ArrayDeque<java.time.Instant> recentLookups = new java.util.ArrayDeque<>();

    static final String DATABASE_PROBLEM_MESSAGE =
            "Sign-in could not be completed because of a database problem. Check that MySQL is running and try again.";

    private final Database database;
    private final PinHasher pinHasher;
    private final Clock clock;
    /** Hash of a random PIN, verified when the phone is unknown so timing stays similar. */
    private final String dummyHash;

    public AuthenticationService(Database database, PinHasher pinHasher) {
        this(database, pinHasher, Clock.systemUTC());
    }

    /** The clock (always UTC) can be replaced in tests to simulate the lockout expiring. */
    public AuthenticationService(Database database, PinHasher pinHasher, Clock clock) {
        this.database = database;
        this.pinHasher = pinHasher;
        this.clock = clock;
        this.dummyHash = pinHasher.hash("000000");
    }

    /**
     * Step 1: is there an account with this phone number (any accepted format)? Returns the
     * normalized number, or throws {@link #NO_ACCOUNT_MESSAGE}. Reveals nothing else about the
     * account (no name), and is limited to {@link #MAX_LOOKUPS_PER_MINUTE} per minute.
     */
    public String findAccount(String phone) throws ValidationException, DatabaseException {
        String normalized = PhoneNumbers.normalize(phone);
        synchronized (recentLookups) {
            java.time.Instant now = clock.instant();
            while (!recentLookups.isEmpty() && recentLookups.peekFirst().isBefore(now.minusSeconds(60))) {
                recentLookups.removeFirst();
            }
            if (recentLookups.size() >= MAX_LOOKUPS_PER_MINUTE) {
                throw new ValidationException(LOOKUP_LIMIT_MESSAGE);
            }
            recentLookups.addLast(now);
        }
        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
        try (conn; PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE phone_number = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ValidationException(NO_ACCOUNT_MESSAGE);
                }
                return normalized;
            }
        } catch (SQLException e) {
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
    }

    public AuthenticatedUser signIn(String phone, String pin)
            throws ValidationException, AuthenticationException, DatabaseException {
        String normalizedPhone = PhoneNumbers.normalize(phone);
        if (!Pins.isValidFormat(pin)) {
            throw new ValidationException("PIN must be exactly 6 digits.");
        }

        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            // Keep connection details (URL, host) off the Sign In screen; the cause is logged.
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
        try (conn) {
            conn.setAutoCommit(false);
            try {
                Outcome outcome = checkCredentials(conn, normalizedPhone, pin);
                conn.commit(); // saves the updated attempt counter / lock, success or not
                if (outcome.user() == null) {
                    throw new AuthenticationException(outcome.failure());
                }
                return outcome.user();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
    }

    /** The signed-in user, or (user null) the message explaining the failure. */
    private record Outcome(AuthenticatedUser user, String failure) {
    }

    private Outcome checkCredentials(Connection conn, String phone, String pin) throws SQLException {
        String select = "SELECT user_id, first_name, last_name, phone_number, pin_hash, failed_pin_attempts, locked_until"
                + " FROM users WHERE phone_number = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    pinHasher.verify(pin, dummyHash);
                    return new Outcome(null, INVALID_CREDENTIALS_MESSAGE);
                }
                long userId = rs.getLong("user_id");
                LocalDateTime now = LocalDateTime.now(clock);
                LocalDateTime lockedUntil = rs.getObject("locked_until", LocalDateTime.class);
                int failedAttempts = rs.getInt("failed_pin_attempts");

                if (lockedUntil != null && now.isBefore(lockedUntil)) {
                    pinHasher.verify(pin, dummyHash);
                    return new Outcome(null, LOCKED_MESSAGE);
                }

                if (pinHasher.verify(pin, rs.getString("pin_hash"))) {
                    updateAttempts(conn, userId, 0, null);
                    return new Outcome(new AuthenticatedUser(userId, rs.getString("first_name"),
                            rs.getString("last_name"), rs.getString("phone_number")), null);
                }

                if (lockedUntil != null) {
                    failedAttempts = 0; // an earlier lock has expired: start counting again
                }
                failedAttempts++;
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    updateAttempts(conn, userId, 0, now.plus(LOCKOUT_DURATION));
                    return new Outcome(null, LOCKED_MESSAGE);
                }
                updateAttempts(conn, userId, failedAttempts, null);
                return new Outcome(null, wrongPinMessage(MAX_FAILED_ATTEMPTS - failedAttempts));
            }
        }
    }

    private static void updateAttempts(Connection conn, long userId, int attempts, LocalDateTime lockedUntil)
            throws SQLException {
        String sql = "UPDATE users SET failed_pin_attempts = ?, locked_until = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, attempts);
            ps.setObject(2, lockedUntil);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }
}
