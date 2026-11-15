package com.lamma.wallet;

import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Send Money: simulated transfers between two registered users' PERSONAL wallets.
 *
 * <p>{@link #send} is one database transaction that
 * <ol>
 *   <li>locks BOTH wallets with {@code SELECT ... FOR UPDATE}, always in ascending
 *       {@code wallet_id} order, so two opposite transfers (A to B and B to A) can never
 *       deadlock each other;</li>
 *   <li>if this {@code requestId} was already processed, returns the earlier result
 *       (a repeated click or a retry after a lost response never sends twice);</li>
 *   <li>re-checks the recipient's wallet and the sender's <em>current</em> balance;</li>
 *   <li>debits the sender, credits the recipient, and records both sides
 *       (TRANSFER_OUT and TRANSFER_IN, linked by the same transfer id);</li>
 *   <li>commits all four changes together, or rolls all of them back.</li>
 * </ol>
 * The sender is always the signed-in user (from the session). The recipient is identified
 * by a user id obtained from {@link #findRecipient}; the screen never supplies a wallet id.
 */
public class TransferService {

    static final String NOT_FOUND_MESSAGE =
            "No LAMMA account uses this phone number. Check the number and try again.";
    static final String SELF_TRANSFER_MESSAGE = "You can't send money to yourself.";
    static final String RECIPIENT_UNAVAILABLE_MESSAGE =
            "This recipient can no longer receive money. Please look them up again.";
    static final String FAILED_MESSAGE =
            "The transfer could not be completed because of a database problem, so no money was sent. "
            + "Check that MySQL is running and try again.";
    static final String LOOKUP_FAILED_MESSAGE =
            "The recipient could not be looked up because the database is unavailable. "
            + "Check that MySQL is running and try again.";
    static final String REQUEST_REUSED_MESSAGE =
            "This transfer was already submitted with different details. Please start a new transfer.";
    static final String RECIPIENT_LIMIT_MESSAGE =
            "This transfer would take the recipient's balance above the maximum a wallet can hold.";

    /** A registered user who can receive money. Contains no PIN or other private data. */
    public record Recipient(long userId, String firstName, String lastName, String phoneNumber) {

        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    /** Result of {@link #send}: the sender's row; {@code alreadyProcessed} is true for a repeat. */
    public record TransferResult(WalletTransaction sent, Recipient recipient, boolean alreadyProcessed) {
    }

    private final Database database;
    private final Clock clock;

    public TransferService(Database database) {
        this(database, Clock.systemUTC());
    }

    /** The clock (always UTC) can be replaced in tests. */
    public TransferService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Finds the registered personal user with this phone number (any accepted format).
     * Rejects unknown numbers and the sender's own number.
     */
    public Recipient findRecipient(long senderUserId, String phone) throws ValidationException, DatabaseException {
        String normalized = PhoneNumbers.normalize(phone);
        try (Connection conn = connect(LOOKUP_FAILED_MESSAGE)) {
            Recipient recipient = recipientByPhone(conn, normalized);
            if (recipient == null) {
                throw new ValidationException(NOT_FOUND_MESSAGE);
            }
            if (recipient.userId() == senderUserId) {
                throw new ValidationException(SELF_TRANSFER_MESSAGE);
            }
            return recipient;
        } catch (SQLException e) {
            throw new DatabaseException(LOOKUP_FAILED_MESSAGE, e);
        }
    }

    public TransferResult send(long senderUserId, long recipientUserId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        Amounts.checkTransfer(amount);
        BigDecimal cents = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (senderUserId == recipientUserId) {
            throw new ValidationException(SELF_TRANSFER_MESSAGE);
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 36) {
            throw new IllegalArgumentException("requestId must be 1-36 characters");
        }

        Connection conn = connect(FAILED_MESSAGE);
        try (conn) {
            conn.setAutoCommit(false);
            try {
                TransferResult result = sendInTransaction(conn, senderUserId, recipientUserId, cents, requestId);
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

    private TransferResult sendInTransaction(Connection conn, long senderUserId, long recipientUserId,
                                             BigDecimal amount, String requestId)
            throws SQLException, ValidationException, DatabaseException {
        Long senderWallet = personalWalletId(conn, senderUserId);
        if (senderWallet == null) {
            throw new DatabaseException(WalletService.NOT_FOUND_MESSAGE,
                    new IllegalStateException("No PERSONAL wallet for user " + senderUserId));
        }
        Long recipientWallet = personalWalletId(conn, recipientUserId);
        Recipient recipient = recipientById(conn, recipientUserId);
        if (recipientWallet == null || recipient == null) {
            throw new ValidationException(RECIPIENT_UNAVAILABLE_MESSAGE);
        }

        // Lock both wallets in ascending wallet_id order (the same order for every transfer).
        long first = Math.min(senderWallet, recipientWallet);
        long second = Math.max(senderWallet, recipientWallet);
        BigDecimal firstBalance = lockWallet(conn, first);
        BigDecimal secondBalance = lockWallet(conn, second);
        BigDecimal senderBalance = senderWallet == first ? firstBalance : secondBalance;
        BigDecimal recipientBalance = senderWallet == first ? secondBalance : firstBalance;

        WalletTransaction earlier = TransactionRecords.findByRequestId(conn, requestId);
        if (earlier != null) {
            boolean sameRequest = WalletTransaction.TYPE_TRANSFER_OUT.equals(earlier.type())
                    && earlier.walletId() == senderWallet
                    && recipientWallet.equals(earlier.counterpartyWalletId())
                    && earlier.amount().compareTo(amount) == 0;
            if (!sameRequest) {
                throw new ValidationException(REQUEST_REUSED_MESSAGE);
            }
            return new TransferResult(earlier, recipient, true);
        }

        // Re-check the real balance now that the wallet is locked (it may have changed since review).
        if (senderBalance.compareTo(amount) < 0) {
            throw new ValidationException("Insufficient funds. Your available balance is "
                    + Money.formatUsd(senderBalance) + ".");
        }
        BigDecimal senderAfter = senderBalance.subtract(amount);
        BigDecimal recipientAfter = recipientBalance.add(amount);
        if (recipientAfter.compareTo(Amounts.MAX_BALANCE) > 0) {
            throw new ValidationException(RECIPIENT_LIMIT_MESSAGE);
        }

        updateBalance(conn, senderWallet, amount.negate());
        updateBalance(conn, recipientWallet, amount);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String senderName = recipientById(conn, senderUserId).fullName();
        WalletTransaction out = new WalletTransaction(0, senderWallet, WalletTransaction.TYPE_TRANSFER_OUT,
                WalletTransaction.DEBIT, amount, senderAfter, WalletTransaction.STATUS_COMPLETED, now,
                null, null, requestId, recipientWallet, recipient.fullName());
        WalletTransaction in = new WalletTransaction(0, recipientWallet, WalletTransaction.TYPE_TRANSFER_IN,
                WalletTransaction.CREDIT, amount, recipientAfter, WalletTransaction.STATUS_COMPLETED, now,
                null, null, requestId, senderWallet, senderName);
        long outId = insertTransaction(conn, out, requestId);
        // The received row needs its own unique request id; the transfer id links the two rows.
        insertTransaction(conn, in, UUID.randomUUID().toString());
        return new TransferResult(out.withId(outId), recipient, false);
    }

    /**
     * Inserts one side of the transfer. Protected only so a test can simulate a failure
     * (e.g. on the second row) and check that the whole transfer is rolled back.
     */
    protected long insertTransaction(Connection conn, WalletTransaction t, String requestId) throws SQLException {
        return TransactionRecords.insert(conn, t, requestId);
    }

    private static BigDecimal lockWallet(Connection conn, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM wallets WHERE wallet_id = ? FOR UPDATE")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Wallet " + walletId + " disappeared during the transfer");
                }
                return rs.getBigDecimal("balance");
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

    private static Long personalWalletId(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT wallet_id FROM wallets WHERE owner_user_id = ? AND wallet_type = 'PERSONAL'")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static final String RECIPIENT_COLUMNS = "SELECT u.user_id, u.first_name, u.last_name, u.phone_number"
            + " FROM users u JOIN wallets w ON w.owner_user_id = u.user_id AND w.wallet_type = 'PERSONAL'";

    private static Recipient recipientByPhone(Connection conn, String normalizedPhone) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(RECIPIENT_COLUMNS + " WHERE u.phone_number = ?")) {
            ps.setString(1, normalizedPhone);
            return readRecipient(ps);
        }
    }

    private static Recipient recipientById(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(RECIPIENT_COLUMNS + " WHERE u.user_id = ?")) {
            ps.setLong(1, userId);
            return readRecipient(ps);
        }
    }

    private static Recipient readRecipient(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Recipient(rs.getLong("user_id"), rs.getString("first_name"),
                    rs.getString("last_name"), rs.getString("phone_number")) : null;
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
