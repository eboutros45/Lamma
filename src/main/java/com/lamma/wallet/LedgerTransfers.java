package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The wallet-to-wallet transfer steps shared by shared-wallet money (P03) and expense payments
 * (P04C), so both use the same locking order and the same two-row records as personal Send Money:
 * <ul>
 *   <li>{@link #lockAscending}: lock the wallets with {@code SELECT ... FOR UPDATE}, lowest
 *       {@code wallet_id} first. Every money operation in LAMMA locks wallets in this order, so
 *       no two of them can deadlock on wallets;</li>
 *   <li>{@link #post}: within that transaction, move the money and insert the TRANSFER_OUT
 *       (DEBIT) and TRANSFER_IN (CREDIT) rows linked by one transfer id.</li>
 * </ul>
 * Callers check membership, permissions, idempotency and the balance (each with its own message)
 * between the two steps, and commit or roll back the whole transaction.
 */
final class LedgerTransfers {

    static final String LIMIT_MESSAGE =
            "This would take the receiving wallet's balance above the maximum a wallet can hold.";

    /** A wallet row locked for this transaction. */
    record Locked(long id, String type, String status, String name, BigDecimal balance) {

        boolean isActive(String walletType) {
            return walletType.equals(type) && "ACTIVE".equals(status);
        }
    }

    /** Inserts one transfer row; services pass their own (tests override it to simulate failures). */
    interface Inserter {
        long insert(Connection conn, WalletTransaction t, String requestId, long initiatedBy) throws SQLException;
    }

    /** The two rows of a posted transfer, with their database ids. */
    record Posted(WalletTransaction out, WalletTransaction in) {
    }

    private LedgerTransfers() {
    }

    /** Locks the wallets (duplicates ignored) lowest id first; missing wallets are left out of the map. */
    static Map<Long, Locked> lockAscending(Connection conn, long... walletIds) throws SQLException {
        TreeSet<Long> ordered = new TreeSet<>();
        for (long id : walletIds) {
            ordered.add(id);
        }
        Map<Long, Locked> locked = new TreeMap<>();
        for (long id : ordered) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT wallet_type, status, name, balance FROM wallets WHERE wallet_id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        locked.put(id, new Locked(id, rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getBigDecimal(4)));
                    }
                }
            }
        }
        return locked;
    }

    /**
     * Moves {@code amount} from one locked wallet to another and records both sides. The caller
     * has already checked that {@code from} holds enough. The outgoing row carries
     * {@code requestId} (UNIQUE), so the same request can never be posted twice.
     *
     * @param nameSeenByFrom how the receiving side is named on the sender's row
     * @param nameSeenByTo   how the sending side is named on the receiver's row
     */
    static Posted post(Connection conn, Locked from, Locked to, BigDecimal amount, String kind, long initiatorId,
                       String initiatorName, String nameSeenByFrom, String nameSeenByTo, String requestId,
                       LocalDateTime now, Inserter inserter) throws SQLException, ValidationException {
        if (from.id() == to.id()) {
            throw new IllegalArgumentException("A transfer needs two different wallets");
        }
        if (from.balance().compareTo(amount) < 0) {
            throw new IllegalStateException("The caller must check the balance first");
        }
        BigDecimal fromAfter = from.balance().subtract(amount);
        BigDecimal toAfter = to.balance().add(amount);
        if (toAfter.compareTo(Amounts.MAX_BALANCE) > 0) {
            throw new ValidationException(LIMIT_MESSAGE);
        }
        updateBalance(conn, from.id(), amount.negate());
        updateBalance(conn, to.id(), amount);
        WalletTransaction out = new WalletTransaction(0, from.id(), WalletTransaction.TYPE_TRANSFER_OUT,
                WalletTransaction.DEBIT, amount, fromAfter, WalletTransaction.STATUS_COMPLETED, now, null, null,
                requestId, to.id(), nameSeenByFrom, kind, initiatorName);
        WalletTransaction in = new WalletTransaction(0, to.id(), WalletTransaction.TYPE_TRANSFER_IN,
                WalletTransaction.CREDIT, amount, toAfter, WalletTransaction.STATUS_COMPLETED, now, null, null,
                requestId, from.id(), nameSeenByTo, kind, initiatorName);
        long outId = inserter.insert(conn, out, requestId, initiatorId);
        // The receiving row needs its own unique request id; the transfer id links the two rows.
        long inId = inserter.insert(conn, in, UUID.randomUUID().toString(), initiatorId);
        return new Posted(out.withId(outId), in.withId(inId));
    }

    /** "First Last" of a user with a personal wallet. */
    static String nameOf(Connection conn, long userId) throws SQLException {
        TransferService.Recipient r = TransferService.recipientById(conn, userId);
        if (r == null) {
            throw new SQLException("User " + userId + " has no personal wallet");
        }
        return r.fullName();
    }

    /** The user's PERSONAL wallet id, or null. */
    static Long personalWalletId(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT wallet_id FROM wallets WHERE owner_user_id = ? AND wallet_type = 'PERSONAL'")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * The member's current role (latest committed row, read with a shared lock), or null. Call it
     * after locking the shared wallet's row: membership changes lock that row too, so the answer
     * can't change before this transaction ends.
     */
    static MemberRole lockedRole(Connection conn, long userId, long sharedWalletId) throws SQLException {
        String sql = "SELECT m.member_role FROM shared_wallet_members m JOIN wallets w ON w.wallet_id = m.shared_wallet_id"
                + " WHERE m.user_id = ? AND m.shared_wallet_id = ? AND w.wallet_type = 'SHARED' AND w.status = 'ACTIVE'"
                + " FOR SHARE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, sharedWalletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private static void updateBalance(Connection conn, long walletId, BigDecimal change) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE wallets SET balance = balance + ? WHERE wallet_id = ?")) {
            ps.setBigDecimal(1, change);
            ps.setLong(2, walletId);
            ps.executeUpdate();
        }
    }
}
