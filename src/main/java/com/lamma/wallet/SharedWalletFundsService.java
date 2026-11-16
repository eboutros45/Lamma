package com.lamma.wallet;

import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.TransferService.Recipient;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.UUID;

/**
 * Money in and out of shared wallets (Shared Wallet P03): contributions, withdrawals, sending
 * shared funds to a registered user, and the shared wallet's history.
 *
 * <p>Every operation is one logical transfer, stored like personal Send Money (V005): a
 * TRANSFER_OUT (DEBIT) row on the wallet the money leaves and a TRANSFER_IN (CREDIT) row on the
 * wallet it enters, linked by one transfer id, plus the kind and the member who started it
 * (V008). {@link #transfer} runs in ONE database transaction that
 * <ol>
 *   <li>locks both wallets with {@code SELECT ... FOR UPDATE} in ascending {@code wallet_id}
 *       order, the same order personal Send Money uses, so no two transfers can deadlock;</li>
 *   <li>returns the earlier result if this request id was already processed (a double click or
 *       a retry after a lost response never moves money twice);</li>
 *   <li>re-checks, with the wallets locked, that both wallets are active and of the right type
 *       and the member's <em>current</em> role (membership changes lock the same shared-wallet
 *       row, so a removal or demotion is either fully before or fully after the transfer);</li>
 *   <li>re-checks the current balance (never below zero) and the receiving wallet's maximum;</li>
 *   <li>updates both balances and inserts both rows, then commits, or rolls everything back.</li>
 * </ol>
 * The acting user always comes from the session. The member's personal wallet is looked up by
 * that user id; the screens never supply a personal wallet id.
 */
public class SharedWalletFundsService {

    static final String CONTRIBUTOR_CANNOT_SPEND_MESSAGE =
            "Only the Owner or a Spender can withdraw or send money from this shared wallet.";
    static final String REQUEST_REUSED_MESSAGE =
            "This operation was already submitted with different details. Please start again.";
    static final String RECIPIENT_UNAVAILABLE_MESSAGE = TransferService.RECIPIENT_UNAVAILABLE_MESSAGE;
    static final String LIMIT_MESSAGE = LedgerTransfers.LIMIT_MESSAGE;
    static final String FAILED_MESSAGE =
            "The operation could not be completed because of a database problem, so no money was moved. "
            + "Check that MySQL is running and try again.";
    static final String LOAD_FAILED_MESSAGE =
            "The shared wallet could not be loaded because the database is unavailable. "
            + "Check that MySQL is running and try again.";
    static final String PERSONAL_WALLET_MISSING_MESSAGE = WalletService.NOT_FOUND_MESSAGE;

    /** What a member sees before moving money: both balances and their role. */
    public record Overview(long walletId, String walletName, BigDecimal sharedBalance, MemberRole myRole,
                           BigDecimal myPersonalBalance, boolean completed) {
    }

    /**
     * Result of a contribution, withdrawal or send.
     *
     * @param sharedSide         the row on the shared wallet
     * @param sharedBalanceAfter the shared wallet's balance right after the operation
     * @param myBalanceAfter     the member's personal balance right after it, or null when it
     *                           didn't change (sending to someone else)
     * @param otherName          the shared wallet's name (contribution, withdrawal) or the recipient's name
     * @param alreadyProcessed   true when this request id had already been carried out
     */
    public record Result(String kind, WalletTransaction sharedSide, String walletName, String otherName,
                         BigDecimal sharedBalanceAfter, BigDecimal myBalanceAfter, boolean alreadyProcessed) {

        public BigDecimal amount() {
            return sharedSide.amount();
        }
    }

    private final Database database;
    private final Clock clock;

    public SharedWalletFundsService(Database database) {
        this(database, Clock.systemUTC());
    }

    /** The clock (always UTC) can be replaced in tests. */
    public SharedWalletFundsService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    // ---------- reading ----------

    /** Shared balance, role and personal balance. Non-members get "not available". */
    public Overview overview(long userId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            String sql = "SELECT w.name, w.balance, m.member_role, w.status FROM wallets w"
                    + " JOIN shared_wallets s ON s.shared_wallet_id = w.wallet_id"
                    + " JOIN shared_wallet_members m ON m.shared_wallet_id = w.wallet_id AND m.user_id = ?"
                    + " WHERE w.wallet_id = ? AND w.wallet_type = 'SHARED' AND w.status IN ('ACTIVE', 'COMPLETED')";
            String name;
            BigDecimal balance;
            MemberRole role;
            boolean completed;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
                    }
                    name = rs.getString(1);
                    balance = rs.getBigDecimal(2);
                    role = MemberRole.valueOf(rs.getString(3));
                    completed = "COMPLETED".equals(rs.getString(4));
                }
            }
            BigDecimal personal = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT balance FROM wallets WHERE owner_user_id = ? AND wallet_type = 'PERSONAL'")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        personal = rs.getBigDecimal(1);
                    }
                }
            }
            return new Overview(walletId, name, balance, role, personal, completed);
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /**
     * The shared wallet's transactions, newest first: one row per logical operation (its own
     * side). Any current member may read it; others get "not available".
     */
    public List<WalletTransaction> history(long userId, long walletId, int limit)
            throws ValidationException, DatabaseException {
        try (Connection conn = connect(LOAD_FAILED_MESSAGE)) {
            if (memberOfActiveOrCompleted(conn, userId, walletId) == null) { // completed gifts keep their history
                throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
            }
            String sql = "SELECT " + TransactionRecords.COLUMNS + " FROM wallet_transactions t"
                    + " WHERE t.wallet_id = ? ORDER BY t.transaction_id DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, walletId);
                ps.setInt(2, limit);
                List<WalletTransaction> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(TransactionRecords.read(rs));
                    }
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DatabaseException(LOAD_FAILED_MESSAGE, e);
        }
    }

    /**
     * Looks up who shared funds would go to. Only the Owner or a Spender may do this. Any
     * registered user with a personal wallet qualifies, including the member themself.
     */
    public Recipient findRecipient(long userId, long walletId, String phone)
            throws ValidationException, DatabaseException {
        String normalized = PhoneNumbers.normalize(phone);
        try (Connection conn = connect(TransferService.LOOKUP_FAILED_MESSAGE)) {
            requireSpender(roleOf(conn, userId, walletId, false));
            Recipient recipient = TransferService.recipientByPhone(conn, normalized);
            if (recipient == null) {
                throw new ValidationException(TransferService.NOT_FOUND_MESSAGE);
            }
            return recipient;
        } catch (SQLException e) {
            throw new DatabaseException(TransferService.LOOKUP_FAILED_MESSAGE, e);
        }
    }

    // ---------- moving money ----------

    /** Any member: from their own personal wallet into the shared wallet. */
    public Result contribute(long userId, long walletId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        return transfer(WalletTransaction.KIND_CONTRIBUTION, userId, walletId, userId, amount, requestId);
    }

    /** Owner or Spender: from the shared wallet into their own personal wallet. */
    public Result withdraw(long userId, long walletId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        return transfer(WalletTransaction.KIND_WITHDRAWAL, userId, walletId, userId, amount, requestId);
    }

    /** Owner or Spender: from the shared wallet into a registered user's personal wallet. */
    public Result send(long userId, long walletId, long recipientUserId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        return transfer(WalletTransaction.KIND_SHARED_SEND, userId, walletId, recipientUserId, amount, requestId);
    }

    private Result transfer(String kind, long userId, long walletId, long personalOwnerId, BigDecimal amount,
                            String requestId) throws ValidationException, DatabaseException {
        Amounts.checkTransfer(amount);
        BigDecimal cents = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (requestId == null || requestId.isBlank() || requestId.length() > 36) {
            throw new IllegalArgumentException("requestId must be 1-36 characters");
        }
        Connection conn = connect(FAILED_MESSAGE);
        try (conn) {
            conn.setAutoCommit(false);
            // READ COMMITTED: the duplicate-request check (a locking read of a request id that
            // usually doesn't exist yet) then takes no gap lock, so transfers on unrelated wallets
            // can't deadlock on the unique request_id index. Every decision is still made on rows
            // locked with FOR UPDATE / FOR SHARE, which always read the latest committed data.
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Result result = transferInTransaction(conn, kind, userId, walletId, personalOwnerId, cents, requestId);
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

    private Result transferInTransaction(Connection conn, String kind, long userId, long sharedWalletId,
                                         long personalOwnerId, BigDecimal amount, String requestId)
            throws SQLException, ValidationException, DatabaseException {
        boolean intoShared = WalletTransaction.KIND_CONTRIBUTION.equals(kind);
        Long personalWalletId = LedgerTransfers.personalWalletId(conn, personalOwnerId);
        if (personalWalletId == null) {
            if (personalOwnerId == userId) {
                throw new DatabaseException(PERSONAL_WALLET_MISSING_MESSAGE,
                        new IllegalStateException("No PERSONAL wallet for user " + userId));
            }
            throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
        }
        if (personalWalletId == sharedWalletId) {
            // Can't happen (one is PERSONAL, the other must be SHARED); checked so it can never post.
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }

        // 1. Lock both wallets, lowest id first (the same order as personal Send Money).
        Map<Long, LedgerTransfers.Locked> locked = LedgerTransfers.lockAscending(conn, personalWalletId, sharedWalletId);
        LedgerTransfers.Locked shared = locked.get(sharedWalletId);
        LedgerTransfers.Locked personal = locked.get(personalWalletId);
        if (shared != null && "SHARED".equals(shared.type()) && "COMPLETED".equals(shared.status())
                && LedgerTransfers.lockedRoleAnyStatus(conn, userId, sharedWalletId) != null) {
            throw new ValidationException(GiftDeliveryService.COMPLETED_MESSAGE); // a delivered gift: read-only
        }
        if (shared == null || !shared.isActive("SHARED") || !isSharedWallet(conn, sharedWalletId)) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }
        if (personal == null || !personal.isActive("PERSONAL")) {
            if (personalOwnerId == userId) {
                throw new DatabaseException(PERSONAL_WALLET_MISSING_MESSAGE,
                        new IllegalStateException("Personal wallet " + personalWalletId + " is not usable"));
            }
            throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
        }
        long fromId = intoShared ? personalWalletId : sharedWalletId;
        long toId = intoShared ? sharedWalletId : personalWalletId;

        // 2. The member's current role, read with a lock (never an older snapshot).
        MemberRole role = LedgerTransfers.lockedRole(conn, userId, sharedWalletId);
        if (role == null) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }

        // 3. Already done? Return the earlier result instead of moving money again.
        WalletTransaction earlier = TransactionRecords.findByRequestId(conn, requestId);
        if (earlier != null) {
            boolean same = WalletTransaction.TYPE_TRANSFER_OUT.equals(earlier.type())
                    && kind.equals(earlier.transferKind())
                    && earlier.walletId() == fromId
                    && Long.valueOf(toId).equals(earlier.counterpartyWalletId())
                    && earlier.amount().compareTo(amount) == 0
                    && initiatorOf(conn, requestId) == userId;
            if (!same) {
                throw new ValidationException(REQUEST_REUSED_MESSAGE);
            }
            return earlierResult(conn, kind, earlier, shared, personalOwnerId == userId);
        }

        // 4. Permission, with the role as it is now.
        if (!intoShared) {
            requireSpender(role);
        }

        // 5. Balances, with the wallets locked.
        LedgerTransfers.Locked from = intoShared ? personal : shared;
        LedgerTransfers.Locked to = intoShared ? shared : personal;
        if (from.balance().compareTo(amount) < 0) {
            throw new ValidationException(intoShared
                    ? "Insufficient funds. Your available balance is " + Money.formatUsd(from.balance()) + "."
                    : "Insufficient funds in \u201c" + shared.name() + "\u201d. Its balance is "
                            + Money.formatUsd(from.balance()) + ".");
        }

        // 6. Move the money and record both sides.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String initiatorName = LedgerTransfers.nameOf(conn, userId);
        String personalName = LedgerTransfers.nameOf(conn, personalOwnerId);
        LedgerTransfers.Posted posted = LedgerTransfers.post(conn, from, to, amount, kind, userId, initiatorName,
                intoShared ? shared.name() : personalName, intoShared ? personalName : shared.name(), requestId, now,
                this::insertTransaction);

        WalletTransaction sharedSide = intoShared ? posted.in() : posted.out();
        WalletTransaction personalSide = intoShared ? posted.out() : posted.in();
        return new Result(kind, sharedSide, shared.name(), intoShared ? shared.name() : personalName,
                sharedSide.balanceAfter(), personalOwnerId == userId ? personalSide.balanceAfter() : null, false);
    }

    /**
     * Inserts one side of a transfer. Protected only so a test can simulate a failure (e.g. on
     * the second row) and check that the whole operation is rolled back.
     */
    protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long initiatedBy)
            throws SQLException {
        return TransactionRecords.insert(conn, t, requestId, initiatedBy);
    }

    // ---------- helpers ----------

    /**
     * Plain read on purpose: shared_wallets rows are never deleted, and locking this row here
     * would take locks in the opposite order to membership changes (which lock shared_wallets,
     * then wallets) and could deadlock. The wallets row locked above is the serialization point.
     */
    private static boolean isSharedWallet(Connection conn, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM shared_wallets WHERE shared_wallet_id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The member's role, or null. {@code locking} reads the latest committed row (inside a transfer). */
    private static MemberRole roleOf(Connection conn, long userId, long walletId, boolean locking)
            throws SQLException {
        String sql = "SELECT m.member_role FROM shared_wallet_members m JOIN wallets w ON w.wallet_id = m.shared_wallet_id"
                + " WHERE m.user_id = ? AND m.shared_wallet_id = ? AND w.wallet_type = 'SHARED' AND w.status = 'ACTIVE'"
                + (locking ? " FOR SHARE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
            }
        }
    }

    /** The member's role in an ACTIVE or COMPLETED shared wallet (for reading its history), or null. */
    private static MemberRole memberOfActiveOrCompleted(Connection conn, long userId, long walletId) throws SQLException {
        String sql = "SELECT m.member_role FROM shared_wallet_members m JOIN wallets w ON w.wallet_id = m.shared_wallet_id"
                + " WHERE m.user_id = ? AND m.shared_wallet_id = ? AND w.wallet_type = 'SHARED'"
                + " AND w.status IN ('ACTIVE', 'COMPLETED')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
            }
        }
    }

    /** Non-members learn nothing; Contributors are told why they can't. */
    private static void requireSpender(MemberRole role) throws ValidationException {
        if (role == null) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }
        if (!role.canSendOut()) {
            throw new ValidationException(CONTRIBUTOR_CANNOT_SPEND_MESSAGE);
        }
    }

    private Result earlierResult(Connection conn, String kind, WalletTransaction earlierOut, LedgerTransfers.Locked shared,
                                 boolean personalIsMine) throws SQLException {
        WalletTransaction earlierIn = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + TransactionRecords.COLUMNS
                + " FROM wallet_transactions t WHERE t.transfer_id = ? AND t.transaction_type = 'TRANSFER_IN' FOR SHARE")) {
            ps.setString(1, earlierOut.transferId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    earlierIn = TransactionRecords.read(rs);
                }
            }
        }
        if (earlierIn == null) {
            throw new SQLException("Transfer " + earlierOut.transferId() + " has no receiving row");
        }
        boolean intoShared = WalletTransaction.KIND_CONTRIBUTION.equals(kind);
        WalletTransaction sharedSide = intoShared ? earlierIn : earlierOut;
        WalletTransaction personalSide = intoShared ? earlierOut : earlierIn;
        String other = intoShared ? shared.name() : earlierOut.counterpartyName();
        return new Result(kind, sharedSide, shared.name(), other, sharedSide.balanceAfter(),
                personalIsMine ? personalSide.balanceAfter() : null, true);
    }

    // Locking reads: a plain read could use a snapshot taken before a concurrent identical
    // request committed, and miss its rows.
    private static long initiatorOf(Connection conn, String requestId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT initiated_by_user_id FROM wallet_transactions WHERE request_id = ? FOR SHARE")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
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
