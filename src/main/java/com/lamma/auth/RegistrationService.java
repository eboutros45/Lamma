package com.lamma.auth;

import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Personal sign-up: validates the form, then creates the user and their PERSONAL wallet
 * (balance 0.00) in one database transaction. Either both rows are saved or neither is.
 */
public class RegistrationService {

    /** Same limit as the VARCHAR(50) name columns. */
    static final int MAX_NAME_LENGTH = 50;

    /** MySQL "duplicate entry" error code. */
    private static final int ER_DUP_ENTRY = 1062;

    private final Database database;
    private final PinHasher pinHasher;

    public RegistrationService(Database database, PinHasher pinHasher) {
        this.database = database;
        this.pinHasher = pinHasher;
    }

    /** Result of a successful registration. */
    public record Registration(long userId, long walletId, String phoneNumber) {
    }

    public Registration registerPersonal(String firstName, String lastName, String phone,
                                         String pin, String confirmPin)
            throws ValidationException, DatabaseException {
        String first = firstName == null ? "" : firstName.strip();
        String last = lastName == null ? "" : lastName.strip();

        List<String> problems = new ArrayList<>();
        checkName("First name", first, problems);
        checkName("Last name", last, problems);
        String normalizedPhone = null;
        try {
            normalizedPhone = PhoneNumbers.normalize(phone);
        } catch (ValidationException e) {
            problems.addAll(e.problems());
        }
        if (pin == null || pin.isEmpty()) {
            problems.add("PIN is required.");
        } else if (!Pins.isValidFormat(pin)) {
            problems.add("PIN must be exactly 6 digits.");
        } else if (!pin.equals(confirmPin)) {
            problems.add("PIN and Confirm PIN do not match.");
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        String pinHash = pinHasher.hash(pin);

        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long userId = insertUser(conn, first, last, normalizedPhone, pinHash);
                long walletId = insertPersonalWallet(conn, userId);
                conn.commit();
                return new Registration(userId, walletId, normalizedPhone);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY && String.valueOf(e.getMessage()).contains("uq_users_phone_number")) {
                throw new ValidationException("An account with this phone number already exists. Try signing in.");
            }
            throw new DatabaseException("Registration could not be saved, so no account was created.", e);
        } catch (SQLException e) {
            throw new DatabaseException("Registration could not be saved, so no account was created. "
                    + "Check that MySQL is running and try again.", e);
        }
    }

    private static void checkName(String label, String value, List<String> problems) {
        if (value.isEmpty()) {
            problems.add(label + " is required.");
        } else if (value.length() > MAX_NAME_LENGTH) {
            problems.add(label + " must be " + MAX_NAME_LENGTH + " characters or fewer.");
        }
    }

    private static long insertUser(Connection conn, String first, String last, String phone, String pinHash)
            throws SQLException {
        String sql = "INSERT INTO users (first_name, last_name, phone_number, pin_hash) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, first);
            ps.setString(2, last);
            ps.setString(3, phone);
            ps.setString(4, pinHash);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * Creates the user's PERSONAL wallet with the default 0.00 balance.
     * Protected only so a test can simulate a failure here and check the rollback.
     */
    protected long insertPersonalWallet(Connection conn, long userId) throws SQLException {
        String sql = "INSERT INTO wallets (wallet_type, owner_user_id, balance) VALUES ('PERSONAL', ?, 0.00)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The database did not return the new row's id.");
            }
            return keys.getLong(1);
        }
    }
}
