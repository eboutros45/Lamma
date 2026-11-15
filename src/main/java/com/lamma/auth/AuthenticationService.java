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
 * <p>Every failure (unknown phone, wrong PIN, locked account) produces the same message,
 * and an unknown phone still costs one PIN-hash computation, so the screen and its timing
 * do not reveal which phone numbers are registered.
 */
public class AuthenticationService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(5);

    public static final String INVALID_CREDENTIALS_MESSAGE =
            "Incorrect phone number or PIN. After " + MAX_FAILED_ATTEMPTS
            + " failed attempts, sign-in is paused for " + LOCKOUT_DURATION.toMinutes() + " minutes.";

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

    public AuthenticatedUser signIn(String phone, String pin)
            throws ValidationException, AuthenticationException, DatabaseException {
        String normalizedPhone = PhoneNumbers.normalize(phone);
        if (!Pins.isValidFormat(pin)) {
            throw new ValidationException("PIN must be exactly 6 digits.");
        }

        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                AuthenticatedUser user = checkCredentials(conn, normalizedPhone, pin);
                conn.commit(); // saves the updated attempt counter / lock, success or not
                if (user == null) {
                    throw new AuthenticationException(INVALID_CREDENTIALS_MESSAGE);
                }
                return user;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Sign-in could not be completed because of a database problem. "
                    + "Check that MySQL is running and try again.", e);
        }
    }

    /** Returns the user if the PIN is right, or null for any kind of failure. */
    private AuthenticatedUser checkCredentials(Connection conn, String phone, String pin) throws SQLException {
        String select = "SELECT user_id, first_name, last_name, phone_number, pin_hash, failed_pin_attempts, locked_until"
                + " FROM users WHERE phone_number = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    pinHasher.verify(pin, dummyHash);
                    return null;
                }
                long userId = rs.getLong("user_id");
                LocalDateTime now = LocalDateTime.now(clock);
                LocalDateTime lockedUntil = rs.getObject("locked_until", LocalDateTime.class);
                int failedAttempts = rs.getInt("failed_pin_attempts");

                if (lockedUntil != null && now.isBefore(lockedUntil)) {
                    pinHasher.verify(pin, dummyHash);
                    return null;
                }

                if (pinHasher.verify(pin, rs.getString("pin_hash"))) {
                    updateAttempts(conn, userId, 0, null);
                    return new AuthenticatedUser(userId, rs.getString("first_name"),
                            rs.getString("last_name"), rs.getString("phone_number"));
                }

                if (lockedUntil != null) {
                    failedAttempts = 0; // an earlier lock has expired: start counting again
                }
                failedAttempts++;
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    updateAttempts(conn, userId, 0, now.plus(LOCKOUT_DURATION));
                } else {
                    updateAttempts(conn, userId, failedAttempts, null);
                }
                return null;
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
