package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared wallets: creation, the member's list, and details.
 *
 * <p>A shared wallet is a row in {@code wallets} (type SHARED, its own balance), a row in
 * {@code shared_wallets} (purpose, description, target) and membership rows in
 * {@code shared_wallet_members}. <b>Access is membership:</b> every read is limited by
 * {@code user_id = ?} with the session's user id, and a wallet the user is not a member of
 * gives the same answer as one that does not exist.
 *
 * <p>No money moves into or out of shared wallets in this phase.
 */
public class SharedWalletService {

    static final int MAX_NAME = 60;
    static final int MAX_PURPOSE = 80;
    static final int MAX_DESCRIPTION = 500;

    public static final String NOT_AVAILABLE_MESSAGE =
            "This shared wallet isn't available. It may not exist, or you may not be a member.";
    static final String FAILED_MESSAGE =
            "The shared wallet could not be created because of a database problem, so nothing was saved. "
            + "Check that MySQL is running and try again.";
    static final String UNAVAILABLE_MESSAGE =
            "Your shared wallets could not be loaded because the database is unavailable. "
            + "Check that MySQL is running and try again.";

    /** Validated creation input. */
    public record NewSharedWallet(String name, String purpose, String description, BigDecimal targetAmount) {
    }

    private final Database database;
    private final Clock clock;

    public SharedWalletService(Database database) {
        this(database, Clock.systemUTC());
    }

    public SharedWalletService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Checks the creation form. Name is required; purpose, description and target are optional
     * (blank means "not given"); a target must be a positive USD amount with at most 2 decimals.
     */
    public static NewSharedWallet validate(String name, String purpose, String description, String targetAmount)
            throws ValidationException {
        List<String> problems = new ArrayList<>();
        String cleanName = clean(name);
        if (cleanName == null) {
            problems.add("Wallet name is required.");
        } else if (cleanName.length() > MAX_NAME) {
            problems.add("Wallet name must be " + MAX_NAME + " characters or fewer.");
        }
        String cleanPurpose = clean(purpose);
        if (cleanPurpose != null && cleanPurpose.length() > MAX_PURPOSE) {
            problems.add("Purpose must be " + MAX_PURPOSE + " characters or fewer.");
        }
        String cleanDescription = description == null || description.isBlank() ? null : description.strip();
        if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION) {
            problems.add("Description must be " + MAX_DESCRIPTION + " characters or fewer.");
        }
        BigDecimal target = null;
        if (targetAmount != null && !targetAmount.isBlank()) {
            try {
                target = Amounts.parseUsd(targetAmount);
                if (target.signum() <= 0) {
                    problems.add("Target amount must be greater than zero.");
                }
            } catch (ValidationException e) {
                problems.add("Target amount: " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return new NewSharedWallet(cleanName, cleanPurpose, cleanDescription, target);
    }

    /**
     * Creates a shared wallet with a zero balance and the creator as OWNER, all in one
     * transaction: the wallet, its shared-wallet row and the membership are saved together
     * or not at all.
     *
     * @return the new wallet's id
     */
    public long create(long creatorUserId, NewSharedWallet input) throws DatabaseException {
        Connection conn = connect(FAILED_MESSAGE);
        try (conn) {
            conn.setAutoCommit(false);
            try {
                long walletId = insertWallet(conn, input.name());
                insertSharedWallet(conn, walletId, input);
                insertOwnerMembership(conn, creatorUserId, walletId,
                        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
                conn.commit();
                return walletId;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    /** The shared wallets this user is a member of (oldest first). */
    public List<SharedWallet.Summary> listForMember(long userId) throws DatabaseException {
        String sql = SUMMARY_SELECT + " WHERE m.user_id = ? AND w.status = 'ACTIVE' ORDER BY m.joined_at, w.wallet_id";
        try (Connection conn = connect(UNAVAILABLE_MESSAGE); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            List<SharedWallet.Summary> wallets = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    wallets.add(readSummary(rs));
                }
            }
            return wallets;
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    /**
     * A shared wallet with its members, for a user who is a member. Anyone else (and any id
     * that is not an active shared wallet) gets {@link #NOT_AVAILABLE_MESSAGE}.
     */
    public SharedWallet.Details details(long userId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(UNAVAILABLE_MESSAGE)) {
            SharedWallet.Summary summary;
            try (PreparedStatement ps = conn.prepareStatement(SUMMARY_SELECT
                    + " WHERE m.user_id = ? AND w.wallet_id = ? AND w.status = 'ACTIVE'")) {
                ps.setLong(1, userId);
                ps.setLong(2, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ValidationException(NOT_AVAILABLE_MESSAGE);
                    }
                    summary = readSummary(rs);
                }
            }
            List<SharedWallet.Member> members = new ArrayList<>();
            String sql = "SELECT u.user_id, u.first_name, u.last_name, m.member_role, m.joined_at"
                    + " FROM shared_wallet_members m JOIN users u ON u.user_id = m.user_id"
                    + " WHERE m.shared_wallet_id = ?"
                    + " ORDER BY CASE m.member_role WHEN 'OWNER' THEN 0 ELSE 1 END, m.joined_at, u.user_id";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        members.add(new SharedWallet.Member(rs.getLong("user_id"), rs.getString("first_name"),
                                rs.getString("last_name"), MemberRole.valueOf(rs.getString("member_role")),
                                rs.getObject("joined_at", LocalDateTime.class)));
                    }
                }
            }
            return new SharedWallet.Details(summary, members);
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    // ---------- inserts (one transaction) ----------

    private static long insertWallet(Connection conn, String name) throws SQLException {
        String sql = "INSERT INTO wallets (wallet_type, name, owner_user_id, balance, status)"
                + " VALUES ('SHARED', ?, NULL, 0.00, 'ACTIVE')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new wallet's id.");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertSharedWallet(Connection conn, long walletId, NewSharedWallet input) throws SQLException {
        String sql = "INSERT INTO shared_wallets (shared_wallet_id, gift_recipient_id, purpose, description, target_amount)"
                + " VALUES (?, NULL, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            ps.setString(2, input.purpose());
            ps.setString(3, input.description());
            if (input.targetAmount() == null) {
                ps.setNull(4, Types.DECIMAL);
            } else {
                ps.setBigDecimal(4, input.targetAmount());
            }
            ps.executeUpdate();
        }
    }

    /**
     * Makes the creator the OWNER. Protected only so a test can simulate a failure at this
     * last step and check that the wallet rows are rolled back.
     */
    protected void insertOwnerMembership(Connection conn, long userId, long walletId, LocalDateTime joinedAt)
            throws SQLException {
        String sql = "INSERT INTO shared_wallet_members (user_id, shared_wallet_id, member_role, joined_at)"
                + " VALUES (?, ?, 'OWNER', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            ps.setObject(3, joinedAt);
            ps.executeUpdate();
        }
    }

    // ---------- reading ----------

    /** Shared wallet columns plus the viewing member's role; the WHERE clause limits by member. */
    private static final String SUMMARY_SELECT = "SELECT w.wallet_id, w.name, w.balance, s.purpose, s.description,"
            + " s.target_amount, m.member_role"
            + " FROM shared_wallet_members m"
            + " JOIN shared_wallets s ON s.shared_wallet_id = m.shared_wallet_id"
            + " JOIN wallets w ON w.wallet_id = s.shared_wallet_id AND w.wallet_type = 'SHARED'";

    private static SharedWallet.Summary readSummary(ResultSet rs) throws SQLException {
        return new SharedWallet.Summary(rs.getLong("wallet_id"), rs.getString("name"), rs.getBigDecimal("balance"),
                rs.getString("purpose"), rs.getString("description"), rs.getBigDecimal("target_amount"),
                MemberRole.valueOf(rs.getString("member_role")));
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip().replaceAll("\\s+", " ");
        return stripped.isEmpty() ? null : stripped;
    }

    private Connection connect(String message) throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(message, e);
        }
    }
}
