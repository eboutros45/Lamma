package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Simulated cash top-ups into the signed-in user's PERSONAL wallet.
 *
 * <p>Each top-up is one database transaction:
 * <ol>
 *   <li>lock the user's wallet row ({@code SELECT ... FOR UPDATE}), so concurrent top-ups
 *       wait for each other instead of overwriting the balance;</li>
 *   <li>if this {@code requestId} was already processed, return that earlier result
 *       (a repeated click or retry never credits twice);</li>
 *   <li>add the amount to the balance and insert the transaction record;</li>
 *   <li>commit both together, or roll both back on any failure.</li>
 * </ol>
 * The wallet is found from the user id in the session, never from a wallet id supplied by
 * the screen.
 */
public class TopUpService {

    static final String FAILED_MESSAGE =
            "The top-up could not be completed because of a database problem, so your balance was not changed. "
            + "Check that MySQL is running and try again.";
    static final String REQUEST_REUSED_MESSAGE =
            "This top-up was already submitted with different details. Please start a new top-up.";
    static final String BALANCE_LIMIT_MESSAGE =
            "This top-up would take your balance above the maximum the wallet can hold.";

    /** Result of {@link #cashTopUp}; {@code alreadyProcessed} is true for a repeated request. */
    public record TopUpResult(WalletTransaction transaction, boolean alreadyProcessed) {
    }

    private final Database database;
    private final Clock clock;

    public TopUpService(Database database) {
        this(database, Clock.systemUTC());
    }

    /** The clock (always UTC) can be replaced in tests. */
    public TopUpService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public TopUpResult cashTopUp(long userId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        Amounts.checkCashTopUp(amount);
        BigDecimal cents = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (requestId == null || requestId.isBlank() || requestId.length() > 36) {
            throw new IllegalArgumentException("requestId must be 1-36 characters");
        }

        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
        try (conn) {
            conn.setAutoCommit(false);
            try {
                TopUpResult result = topUp(conn, userId, cents, requestId);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | ValidationException | DatabaseException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private TopUpResult topUp(Connection conn, long userId, BigDecimal amount, String requestId)
            throws SQLException, ValidationException, DatabaseException {
        long walletId;
        BigDecimal balance;
        String lockWallet = "SELECT wallet_id, balance FROM wallets"
                + " WHERE owner_user_id = ? AND wallet_type = 'PERSONAL' FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(lockWallet)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new DatabaseException(WalletService.NOT_FOUND_MESSAGE,
                            new IllegalStateException("No PERSONAL wallet for user " + userId));
                }
                walletId = rs.getLong("wallet_id");
                balance = rs.getBigDecimal("balance");
            }
        }

        WalletTransaction earlier = findByRequestId(conn, requestId);
        if (earlier != null) {
            boolean sameRequest = earlier.walletId() == walletId
                    && WalletTransaction.TYPE_CASH_TOP_UP.equals(earlier.type())
                    && earlier.amount().compareTo(amount) == 0;
            if (!sameRequest) {
                throw new ValidationException(REQUEST_REUSED_MESSAGE);
            }
            return new TopUpResult(earlier, true);
        }

        BigDecimal newBalance = balance.add(amount);
        if (newBalance.compareTo(Amounts.MAX_BALANCE) > 0) {
            throw new ValidationException(BALANCE_LIMIT_MESSAGE);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE wallets SET balance = balance + ? WHERE wallet_id = ?")) {
            ps.setBigDecimal(1, amount);
            ps.setLong(2, walletId);
            ps.executeUpdate();
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        long transactionId = insertTransaction(conn, walletId, amount, newBalance, requestId, now);
        return new TopUpResult(new WalletTransaction(transactionId, walletId, WalletTransaction.TYPE_CASH_TOP_UP,
                WalletTransaction.CREDIT, amount, newBalance, WalletTransaction.STATUS_COMPLETED, now), false);
    }

    /**
     * Inserts the transaction record. Protected only so a test can simulate a failure here
     * and check that the balance change is rolled back.
     */
    protected long insertTransaction(Connection conn, long walletId, BigDecimal amount, BigDecimal balanceAfter,
                                     String requestId, LocalDateTime createdAt) throws SQLException {
        String sql = "INSERT INTO wallet_transactions"
                + " (wallet_id, transaction_type, direction, amount, balance_after, status, request_id, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, walletId);
            ps.setString(2, WalletTransaction.TYPE_CASH_TOP_UP);
            ps.setString(3, WalletTransaction.CREDIT);
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, balanceAfter);
            ps.setString(6, WalletTransaction.STATUS_COMPLETED);
            ps.setString(7, requestId);
            ps.setObject(8, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new transaction's id.");
                }
                return keys.getLong(1);
            }
        }
    }

    private static WalletTransaction findByRequestId(Connection conn, String requestId) throws SQLException {
        String sql = "SELECT " + WalletService.TRANSACTION_COLUMNS
                + " FROM wallet_transactions t WHERE t.request_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? WalletService.readTransaction(rs) : null;
            }
        }
    }
}
