package com.lamma.wallet;

import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads wallet data for the dashboard.
 *
 * <p>The wallet is always looked up by the signed-in user's id (from the session) and
 * wallet type PERSONAL. The screen never passes a wallet id, so it cannot ask for
 * somebody else's wallet.
 *
 * <p>Error messages are written for the user and never contain SQL, table names or
 * connection details; the technical cause is kept on the exception for the console log.
 */
public class WalletService {

    static final String UNAVAILABLE_MESSAGE =
            "Your wallet could not be loaded because the database is unavailable. "
            + "Check that MySQL is running, then press Retry.";
    static final String NOT_FOUND_MESSAGE =
            "We couldn't find your personal wallet. Please contact support.";
    static final String INCONSISTENT_MESSAGE =
            "Your wallet information looks inconsistent, so it can't be shown. Please contact support.";

    static final String ACTIVITY_UNAVAILABLE_MESSAGE =
            "Your recent activity could not be loaded because the database is unavailable. "
            + "Check that MySQL is running, then press Retry.";

    /** Columns read for a transaction; the table is aliased as "t" in every query. */
    static final String TRANSACTION_COLUMNS = "t.transaction_id, t.wallet_id, t.transaction_type, t.direction,"
            + " t.amount, t.balance_after, t.status, t.created_at, t.card_brand, t.card_last4";

    private final Database database;

    public WalletService(Database database) {
        this.database = database;
    }

    public PersonalWallet loadPersonalWallet(long userId) throws DatabaseException {
        String sql = "SELECT wallet_id, owner_user_id, balance FROM wallets"
                + " WHERE owner_user_id = ? AND wallet_type = 'PERSONAL'";
        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
        try (conn; PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new DatabaseException(NOT_FOUND_MESSAGE,
                            new IllegalStateException("No PERSONAL wallet for user " + userId));
                }
                PersonalWallet wallet = new PersonalWallet(
                        rs.getLong("wallet_id"), rs.getLong("owner_user_id"), rs.getBigDecimal("balance"));
                if (rs.next()) {
                    // Prevented by uq_wallets_one_personal_per_user; checked in case the schema is ever damaged.
                    throw new DatabaseException(INCONSISTENT_MESSAGE,
                            new IllegalStateException("More than one PERSONAL wallet for user " + userId));
                }
                if (wallet.ownerUserId() != userId || wallet.balance() == null
                        || wallet.balance().compareTo(BigDecimal.ZERO) < 0) {
                    throw new DatabaseException(INCONSISTENT_MESSAGE,
                            new IllegalStateException("Invalid wallet row " + wallet));
                }
                return wallet;
            }
        } catch (SQLException e) {
            throw new DatabaseException(UNAVAILABLE_MESSAGE, e);
        }
    }

    /**
     * The newest transactions of the user's PERSONAL wallet, newest first.
     * Selected by the session's user id (joined through wallets), never by a wallet id.
     */
    public List<WalletTransaction> loadRecentTransactions(long userId, int limit) throws DatabaseException {
        String sql = "SELECT " + TRANSACTION_COLUMNS
                + " FROM wallet_transactions t JOIN wallets w ON w.wallet_id = t.wallet_id"
                + " WHERE w.owner_user_id = ? AND w.wallet_type = 'PERSONAL'"
                + " ORDER BY t.transaction_id DESC LIMIT ?";
        Connection conn;
        try {
            conn = database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(ACTIVITY_UNAVAILABLE_MESSAGE, e);
        }
        try (conn; PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            List<WalletTransaction> transactions = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    transactions.add(readTransaction(rs));
                }
            }
            return transactions;
        } catch (SQLException e) {
            throw new DatabaseException(ACTIVITY_UNAVAILABLE_MESSAGE, e);
        }
    }

    static WalletTransaction readTransaction(ResultSet rs) throws SQLException {
        return new WalletTransaction(
                rs.getLong("transaction_id"),
                rs.getLong("wallet_id"),
                rs.getString("transaction_type"),
                rs.getString("direction"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_after"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("card_brand"),
                rs.getString("card_last4"));
    }
}
