package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gift delivery (P05): the Owner of a gift wallet sends its whole balance to the gift recipient's
 * personal wallet, and the wallet becomes COMPLETED (read-only, kept with its history).
 *
 * <p>{@link #deliver} is one database transaction, built from the same steps as P03 transfers
 * ({@link LedgerTransfers}):
 * <ol>
 *   <li>lock the shared wallet and the recipient's personal wallet, lowest {@code wallet_id}
 *       first (the order every money operation uses). Contributions, withdrawals, sends, expense
 *       payments and membership changes all lock the shared wallet's row too, so they happen
 *       entirely before or entirely after the delivery;</li>
 *   <li>if the wallet is already COMPLETED, return the earlier delivery (a double click or a retry
 *       after a lost answer never sends twice);</li>
 *   <li>re-check, with the locks held: the caller is the Owner; it is a gift; the recipient still
 *       has an active personal wallet; no expense still has money outstanding; the balance is above
 *       zero and is exactly the amount the Owner confirmed;</li>
 *   <li>move the whole balance (TRANSFER_OUT + TRANSFER_IN, kind GIFT_DELIVERY), close pending
 *       invitations and join requests, and mark the wallet COMPLETED, then commit, or roll all of
 *       it back.</li>
 * </ol>
 * The transfer's {@code request_id} is {@code gift-delivery-<wallet id>} (UNIQUE), so even if every
 * other check failed, a gift could not be delivered twice. The database also refuses a COMPLETED
 * wallet with a non-zero balance (V011), so no later transfer can put money into it.
 */
public class GiftDeliveryService {

    static final String OWNER_ONLY_MESSAGE = "Only the Owner can send this gift.";
    static final String NOT_A_GIFT_MESSAGE = "This shared wallet isn't a gift wallet.";
    static final String ZERO_BALANCE_MESSAGE =
            "There's nothing to deliver yet: the gift balance is $0.00. Members can contribute first.";
    static final String RECIPIENT_UNAVAILABLE_MESSAGE =
            "The gift recipient no longer has a LAMMA wallet that can receive money, so the gift can't be sent.";
    static final String BALANCE_CHANGED_MESSAGE =
            "The gift balance changed since you reviewed it. Please review the new amount and confirm again.";
    public static final String COMPLETED_MESSAGE =
            "This gift has been delivered. The wallet is now read-only: no more money can go in or out.";
    static final String FAILED_MESSAGE =
            "The gift could not be sent because of a database problem, so nothing changed and no money was moved. "
            + "Check that MySQL is running and try again.";
    static final String LOAD_FAILED_MESSAGE = "The gift details could not be loaded because the database is "
            + "unavailable. Check that MySQL is running and try again.";

    /**
     * What the Owner reviews before sending.
     *
     * @param unsettledExpenses titles of expenses that still have money outstanding (delivery is
     *                          blocked until they are settled or cancelled)
     */
    public record Preview(long walletId, String walletName, String recipientName, String recipientPhone,
                          BigDecimal balance, BigDecimal targetAmount, List<String> unsettledExpenses,
                          int pendingInvitations, int pendingJoinRequests) {

        /** Can be sent now (no blocking reason). */
        public boolean canDeliver() {
            return balance.signum() > 0 && unsettledExpenses.isEmpty();
        }
    }

    /** A completed delivery. {@code alreadyDelivered}: it had already happened; nothing moved now. */
    public record Delivery(long walletId, String walletName, String recipientName, BigDecimal amount,
                           LocalDateTime deliveredAt, String deliveredByName, String transferId,
                           boolean alreadyDelivered) {
    }

    private final Database database;
    private final Clock clock;

    public GiftDeliveryService(Database database) {
        this(database, Clock.systemUTC());
    }

    public GiftDeliveryService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /** What would be sent, for the Owner's confirmation screen. */
    public Preview preview(long ownerId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            GiftRow gift = gift(conn, walletId);
            requireOwnerOfActiveGift(conn, ownerId, gift, false);
            TransferService.Recipient recipient = TransferService.recipientById(conn, gift.recipientId);
            if (recipient == null) {
                throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
            }
            return new Preview(walletId, gift.name, recipient.fullName(), recipient.phoneNumber(), gift.balance,
                    gift.target, unsettledExpenses(conn, walletId), count(conn,
                    "SELECT COUNT(*) FROM shared_wallet_invitations WHERE shared_wallet_id = ? AND status = 'PENDING'",
                    walletId), count(conn,
                    "SELECT COUNT(*) FROM shared_wallet_join_requests WHERE shared_wallet_id = ? AND status = 'PENDING'",
                    walletId));
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /**
     * Sends the whole gift balance to the recipient and completes the wallet.
     *
     * @param confirmedAmount the balance the Owner saw and confirmed; if the balance has changed
     *                        since, nothing is sent and the Owner is asked to review again
     */
    public Delivery deliver(long ownerId, long walletId, BigDecimal confirmedAmount)
            throws ValidationException, DatabaseException {
        Connection conn = connect(FAILED_MESSAGE);
        try (conn) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); // as for every money operation
            try {
                Delivery result = deliverInTransaction(conn, ownerId, walletId, confirmedAmount);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | ValidationException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private Delivery deliverInTransaction(Connection conn, long ownerId, long walletId, BigDecimal confirmedAmount)
            throws SQLException, ValidationException {
        // Where the money would go (re-checked below with the locks held; the recipient never changes, P04B).
        GiftRow seen = gift(conn, walletId);
        Long recipientWallet = LedgerTransfers.personalWalletId(conn, seen.recipientId);
        if (recipientWallet == null) {
            requireOwnerOfActiveGift(conn, ownerId, seen, false);
            throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
        }

        // 1. Lock both wallets, lowest id first.
        Map<Long, LedgerTransfers.Locked> locked = LedgerTransfers.lockAscending(conn, walletId, recipientWallet);
        LedgerTransfers.Locked shared = locked.get(walletId);
        LedgerTransfers.Locked recipient = locked.get(recipientWallet);
        if (shared == null || !"SHARED".equals(shared.type())) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }

        // 2. Already delivered: the Owner gets the earlier result; nothing moves again.
        if ("COMPLETED".equals(shared.status())) {
            MemberRole role = LedgerTransfers.lockedRoleAnyStatus(conn, ownerId, walletId);
            if (role == null) {
                throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
            }
            if (role != MemberRole.OWNER) {
                throw new ValidationException(OWNER_ONLY_MESSAGE);
            }
            Delivery earlier = delivery(conn, walletId);
            if (earlier == null) {
                throw new SQLException("Completed wallet " + walletId + " has no delivery transfer");
            }
            return new Delivery(earlier.walletId(), earlier.walletName(), earlier.recipientName(), earlier.amount(),
                    earlier.deliveredAt(), earlier.deliveredByName(), earlier.transferId(), true);
        }

        // 3. Re-check everything with the locks held.
        requireOwnerOfActiveGift(conn, ownerId, seen, true);
        if (recipient == null || !recipient.isActive("PERSONAL")) {
            throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
        }
        List<String> unsettled = unsettledExpenses(conn, walletId);
        if (!unsettled.isEmpty()) {
            throw new ValidationException(unsettledMessage(unsettled));
        }
        BigDecimal balance = shared.balance();
        if (balance.signum() <= 0) {
            throw new ValidationException(ZERO_BALANCE_MESSAGE);
        }
        if (confirmedAmount == null || balance.compareTo(confirmedAmount) != 0) {
            throw new ValidationException(BALANCE_CHANGED_MESSAGE);
        }

        // 4. Send the whole balance, close what is still open, and complete the wallet.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String ownerName = LedgerTransfers.nameOf(conn, ownerId);
        String recipientName = LedgerTransfers.nameOf(conn, seen.recipientId);
        LedgerTransfers.Posted posted = LedgerTransfers.post(conn, shared, recipient, balance,
                WalletTransaction.KIND_GIFT_DELIVERY, ownerId, ownerName, recipientName, shared.name(),
                transferRequestId(walletId), now, this::insertTransaction);
        update(conn, "UPDATE shared_wallet_invitations SET status = 'CANCELLED', responded_at = ?"
                + " WHERE shared_wallet_id = ? AND status = 'PENDING'", now, walletId);
        update(conn, "UPDATE shared_wallet_join_requests SET status = 'REJECTED', decided_at = ?, decided_by_user_id = ?"
                + " WHERE shared_wallet_id = ? AND status = 'PENDING'", now, ownerId, walletId);
        int completed = update(conn, "UPDATE wallets SET status = 'COMPLETED', completed_at = ?"
                + " WHERE wallet_id = ? AND status = 'ACTIVE'", now, walletId);
        if (completed != 1) {
            throw new SQLException("Wallet " + walletId + " changed during delivery");
        }
        return new Delivery(walletId, shared.name(), recipientName, balance, now, ownerName,
                posted.out().transferId(), false);
    }

    /** The delivery of a completed gift, for any of its members (null while it isn't delivered). */
    public Delivery deliveryFor(long userId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM shared_wallet_members"
                    + " WHERE user_id = ? AND shared_wallet_id = ?")) {
                ps.setLong(1, userId);
                ps.setLong(2, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
                    }
                }
            }
            return delivery(conn, walletId);
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /**
     * Inserts one transfer row. Protected only so a test can simulate a failure and check that
     * the delivery is rolled back and the gift stays active.
     */
    protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long initiatedBy)
            throws SQLException {
        return TransactionRecords.insert(conn, t, requestId, initiatedBy);
    }

    static String transferRequestId(long walletId) {
        return TRANSFER_PREFIX + walletId;
    }

    /** Prefix of a gift delivery's transfer id (one per wallet, so a gift can't be delivered twice). */
    public static final String TRANSFER_PREFIX = "gift-delivery-";

    static String unsettledMessage(List<String> titles) {
        return "Settle or cancel " + (titles.size() == 1 ? "this expense" : "these expenses")
                + " before sending the gift: " + String.join(", ", titles)
                + ". Their payment requests would otherwise be left unpaid.";
    }

    // ---------- helpers ----------

    private record GiftRow(long walletId, String name, String status, BigDecimal balance, BigDecimal target,
                           long recipientId) {
    }

    private static GiftRow gift(Connection conn, long walletId) throws SQLException, ValidationException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT w.name, w.status, w.balance, s.target_amount,"
                + " s.gift_recipient_id FROM shared_wallets s JOIN wallets w ON w.wallet_id = s.shared_wallet_id"
                + " WHERE s.shared_wallet_id = ? AND w.wallet_type = 'SHARED'")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
                }
                long recipient = rs.getLong(5);
                boolean notAGift = rs.wasNull(); // checked right after reading column 5
                return new GiftRow(walletId, rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), notAGift ? 0 : recipient);
            }
        }
    }

    /** Non-members learn nothing; members learn why they can't. */
    private static void requireOwnerOfActiveGift(Connection conn, long userId, GiftRow gift, boolean locking)
            throws SQLException, ValidationException {
        MemberRole role;
        if (locking) {
            role = LedgerTransfers.lockedRoleAnyStatus(conn, userId, gift.walletId);
        } else {
            try (PreparedStatement ps = conn.prepareStatement("SELECT member_role FROM shared_wallet_members"
                    + " WHERE user_id = ? AND shared_wallet_id = ?")) {
                ps.setLong(1, userId);
                ps.setLong(2, gift.walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    role = rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
                }
            }
        }
        if (role == null) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }
        if (gift.recipientId == 0) {
            throw new ValidationException(NOT_A_GIFT_MESSAGE);
        }
        if (role != MemberRole.OWNER) {
            throw new ValidationException(OWNER_ONLY_MESSAGE);
        }
        if (!locking && "COMPLETED".equals(gift.status)) {
            throw new ValidationException(COMPLETED_MESSAGE);
        }
    }

    /** OPEN expenses of the wallet that still have money outstanding (unpaid, uncovered shares). */
    private static List<String> unsettledExpenses(Connection conn, long walletId) throws SQLException {
        String sql = "SELECT e.title FROM expenses e WHERE e.shared_wallet_id = ? AND e.status = 'OPEN'"
                + " AND e.total_amount > (SELECT COALESCE(SUM(r.amount), 0) FROM payment_requests r"
                + "   WHERE r.expense_id = e.expense_id AND r.status = 'PAID')"
                + " + (SELECT COALESCE(SUM(s.amount), 0) FROM expense_shares s"
                + "   WHERE s.expense_id = e.expense_id AND s.covered_by_payer)"
                + " ORDER BY e.expense_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            List<String> titles = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    titles.add(rs.getString(1));
                }
            }
            return titles;
        }
    }

    private static Delivery delivery(Connection conn, long walletId) throws SQLException {
        String sql = "SELECT w.name, t.amount, t.created_at, t.transfer_id, t.counterparty_wallet_id,"
                + " (SELECT CONCAT(u.first_name, ' ', u.last_name) FROM wallets cw JOIN users u ON u.user_id = cw.owner_user_id"
                + "   WHERE cw.wallet_id = t.counterparty_wallet_id),"
                + " (SELECT CONCAT(iu.first_name, ' ', iu.last_name) FROM users iu WHERE iu.user_id = t.initiated_by_user_id)"
                + " FROM wallet_transactions t JOIN wallets w ON w.wallet_id = t.wallet_id"
                + " WHERE t.wallet_id = ? AND t.transfer_kind = 'GIFT_DELIVERY' AND t.transaction_type = 'TRANSFER_OUT'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Delivery(walletId, rs.getString(1), rs.getString(6), rs.getBigDecimal(2),
                        rs.getObject(3, LocalDateTime.class), rs.getString(7), rs.getString(4), false);
            }
        }
    }

    private static int count(Connection conn, String sql, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int update(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
            return ps.executeUpdate();
        }
    }

    private Connection connect(String message) throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(message, e);
        }
    }
}
