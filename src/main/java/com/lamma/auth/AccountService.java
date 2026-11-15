package com.lamma.auth;

import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Account settings for the signed-in user: name, phone number and PIN.
 *
 * <p>Changing the phone number (the sign-in identity) or the PIN needs the current PIN. A wrong
 * current PIN counts towards the same lockout as sign-in ({@link AuthenticationService#MAX_FAILED_ATTEMPTS}
 * wrong PINs lock the account for {@link AuthenticationService#LOCKOUT_DURATION}), so these screens can't
 * be used to guess the PIN. PINs are only ever stored as salted PBKDF2 hashes and are never shown.
 * The user id always comes from the session.
 */
public class AccountService {

    static final String WRONG_PIN_MESSAGE = "Your current PIN is incorrect.";
    static final String LOCKED_MESSAGE = "Too many incorrect PINs. For your security, changes and sign-in "
            + "are blocked for a few minutes. Try again later.";
    static final String PHONE_TAKEN_MESSAGE = "Another LAMMA account already uses this phone number.";
    static final String SAME_PHONE_MESSAGE = "This is already your phone number.";
    static final String SAME_PIN_MESSAGE = "Your new PIN must be different from your current PIN.";
    static final String DATABASE_PROBLEM_MESSAGE =
            "Your account could not be updated because the database is unavailable. Nothing was changed. "
            + "Check that MySQL is running and try again.";
    private static final int ER_DUP_ENTRY = 1062;

    private final Database database;
    private final PinHasher pinHasher;
    private final Clock clock;

    public AccountService(Database database, PinHasher pinHasher) {
        this(database, pinHasher, Clock.systemUTC());
    }

    public AccountService(Database database, PinHasher pinHasher, Clock clock) {
        this.database = database;
        this.pinHasher = pinHasher;
        this.clock = clock;
    }

    /** Changes the first and last name (same rules as registration). Returns the updated user. */
    public AuthenticatedUser updateName(long userId, String firstName, String lastName)
            throws ValidationException, DatabaseException {
        String first = firstName == null ? "" : firstName.strip();
        String last = lastName == null ? "" : lastName.strip();
        List<String> problems = new ArrayList<>();
        checkName("First name", first, problems);
        checkName("Last name", last, problems);
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return inTransaction(conn -> {
            update(conn, "UPDATE users SET first_name = ?, last_name = ? WHERE user_id = ?", first, last, userId);
            return load(conn, userId);
        });
    }

    /** Changes the phone number used to sign in, after checking the current PIN. */
    public AuthenticatedUser changePhone(long userId, String currentPin, String newPhone)
            throws ValidationException, DatabaseException {
        String normalized = PhoneNumbers.normalize(newPhone);
        requirePinFormat(currentPin, "Enter your current 6-digit PIN.");
        try {
            return inTransaction(conn -> {
                verifyCurrentPin(conn, userId, currentPin);
                if (normalized.equals(load(conn, userId).phoneNumber())) {
                    throw new ValidationException(SAME_PHONE_MESSAGE);
                }
                update(conn, "UPDATE users SET phone_number = ? WHERE user_id = ?", normalized, userId);
                return load(conn, userId);
            });
        } catch (DatabaseException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException dup
                    && dup.getErrorCode() == ER_DUP_ENTRY) {
                throw new ValidationException(PHONE_TAKEN_MESSAGE);
            }
            throw e;
        }
    }

    /** Changes the PIN, after checking the current one. The new PIN is stored only as a hash. */
    public void changePin(long userId, String currentPin, String newPin, String confirmPin)
            throws ValidationException, DatabaseException {
        requirePinFormat(currentPin, "Enter your current 6-digit PIN.");
        requirePinFormat(newPin, "Your new PIN must be exactly 6 digits.");
        if (!newPin.equals(confirmPin)) {
            throw new ValidationException("The new PIN and its confirmation do not match.");
        }
        if (newPin.equals(currentPin)) {
            throw new ValidationException(SAME_PIN_MESSAGE);
        }
        String newHash = pinHasher.hash(newPin);
        inTransaction(conn -> {
            verifyCurrentPin(conn, userId, currentPin);
            update(conn, "UPDATE users SET pin_hash = ? WHERE user_id = ?", newHash, userId);
            return null;
        });
    }

    // ---------- helpers ----------

    /**
     * Checks the current PIN with the user's row locked. A wrong PIN is counted (and may lock the
     * account) and that count is committed even though the change itself is refused.
     */
    private void verifyCurrentPin(Connection conn, long userId, String pin)
            throws SQLException, ValidationException, CountedFailure {
        String sql = "SELECT pin_hash, failed_pin_attempts, locked_until FROM users WHERE user_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("User " + userId + " not found");
                }
                LocalDateTime now = LocalDateTime.now(clock);
                LocalDateTime lockedUntil = rs.getObject("locked_until", LocalDateTime.class);
                int failed = rs.getInt("failed_pin_attempts");
                if (lockedUntil != null && now.isBefore(lockedUntil)) {
                    throw new ValidationException(LOCKED_MESSAGE);
                }
                if (pinHasher.verify(pin, rs.getString("pin_hash"))) {
                    setAttempts(conn, userId, 0, null);
                    return;
                }
                if (lockedUntil != null) {
                    failed = 0; // an earlier lock has expired: start counting again
                }
                failed++;
                if (failed >= AuthenticationService.MAX_FAILED_ATTEMPTS) {
                    setAttempts(conn, userId, 0, now.plus(AuthenticationService.LOCKOUT_DURATION));
                    throw new CountedFailure(LOCKED_MESSAGE);
                }
                setAttempts(conn, userId, failed, null);
                throw new CountedFailure(WRONG_PIN_MESSAGE);
            }
        }
    }

    /** A refusal whose attempt counter must still be committed. */
    private static final class CountedFailure extends Exception {
        CountedFailure(String message) {
            super(message);
        }
    }

    private static void setAttempts(Connection conn, long userId, int attempts, LocalDateTime lockedUntil)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET failed_pin_attempts = ?, locked_until = ? WHERE user_id = ?")) {
            ps.setInt(1, attempts);
            ps.setObject(2, lockedUntil);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    private static AuthenticatedUser load(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT first_name, last_name, phone_number FROM users WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("User " + userId + " not found");
                }
                return new AuthenticatedUser(userId, rs.getString(1), rs.getString(2), rs.getString(3));
            }
        }
    }

    private static void update(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void checkName(String label, String value, List<String> problems) {
        if (value.isEmpty()) {
            problems.add(label + " is required.");
        } else if (value.length() > RegistrationService.MAX_NAME_LENGTH) {
            problems.add(label + " must be " + RegistrationService.MAX_NAME_LENGTH + " characters or fewer.");
        }
    }

    private static void requirePinFormat(String pin, String message) throws ValidationException {
        if (!Pins.isValidFormat(pin)) {
            throw new ValidationException(message);
        }
    }

    private interface Work<T> {
        T run(Connection conn) throws SQLException, ValidationException, CountedFailure;
    }

    private <T> T inTransaction(Work<T> work) throws ValidationException, DatabaseException {
        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
        try (conn) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (CountedFailure e) {
                conn.commit(); // keep the wrong-PIN count; nothing else was changed
                throw new ValidationException(e.getMessage());
            } catch (SQLException | RuntimeException | ValidationException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(DATABASE_PROBLEM_MESSAGE, e);
        }
    }
}
