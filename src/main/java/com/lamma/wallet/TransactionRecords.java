package com.lamma.wallet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;

/** Reading and writing wallet_transactions rows; shared by top-ups, transfers and history. */
final class TransactionRecords {

    /**
     * Columns read for a transaction. The table must be aliased as "t". The counterparty's
     * name is looked up through the counterparty wallet (transfers only; otherwise NULL).
     */
    static final String COLUMNS = "t.transaction_id, t.wallet_id, t.transaction_type, t.direction,"
            + " t.amount, t.balance_after, t.status, t.created_at, t.card_brand, t.card_last4,"
            + " t.transfer_id, t.counterparty_wallet_id,"
            + " (SELECT CONCAT(cu.first_name, ' ', cu.last_name) FROM wallets cw"
            + "   JOIN users cu ON cu.user_id = cw.owner_user_id"
            + "   WHERE cw.wallet_id = t.counterparty_wallet_id) AS counterparty_name";

    private TransactionRecords() {
    }

    static WalletTransaction read(ResultSet rs) throws SQLException {
        long counterparty = rs.getLong("counterparty_wallet_id");
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
                rs.getString("card_last4"),
                rs.getString("transfer_id"),
                counterparty == 0 ? null : counterparty, // NULL reads as 0; wallet ids start at 1
                rs.getString("counterparty_name"));
    }

    /** Inserts one row and returns its new transaction id. */
    static long insert(Connection conn, WalletTransaction t, String requestId) throws SQLException {
        String sql = "INSERT INTO wallet_transactions (wallet_id, transaction_type, direction, amount,"
                + " balance_after, status, card_brand, card_last4, transfer_id, counterparty_wallet_id,"
                + " request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, t.walletId());
            ps.setString(2, t.type());
            ps.setString(3, t.direction());
            ps.setBigDecimal(4, t.amount());
            ps.setBigDecimal(5, t.balanceAfter());
            ps.setString(6, t.status());
            ps.setString(7, t.cardBrand());
            ps.setString(8, t.cardLast4());
            ps.setString(9, t.transferId());
            if (t.counterpartyWalletId() == null) {
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(10, t.counterpartyWalletId());
            }
            ps.setString(11, requestId);
            ps.setObject(12, t.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The database did not return the new transaction's id.");
                }
                return keys.getLong(1);
            }
        }
    }

    /** Finds the row with this request id (locking it), or null. */
    static WalletTransaction findByRequestId(Connection conn, String requestId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM wallet_transactions t WHERE t.request_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }
}
