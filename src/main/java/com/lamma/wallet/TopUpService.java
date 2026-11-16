package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.PaymentCardService;
import com.lamma.cards.SavedCard;
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
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Simulated top-ups into the signed-in user's PERSONAL wallet: cash, a saved card, or a new
 * card. No real money moves and no card is ever charged or authorised.
 *
 * <p>Each top-up is one database transaction:
 * <ol>
 *   <li>lock the user's wallet row ({@code SELECT ... FOR UPDATE}), so concurrent top-ups
 *       wait for each other instead of overwriting the balance;</li>
 *   <li>if this {@code requestId} was already processed, return that earlier result
 *       (a repeated click or retry never credits twice);</li>
 *   <li>for a saved card, load it with {@code card_id = ? AND user_id = ?} (only the user's
 *       own cards) and check it has not expired;</li>
 *   <li>add the amount to the balance and insert the transaction record (for card top-ups,
 *       with a copy of the card's brand and last four digits);</li>
 *   <li>commit both together, or roll both back on any failure.</li>
 * </ol>
 * The wallet is found from the user id in the session, never from a wallet id supplied by
 * the screen. Card top-ups receive only {@link CardMetadata}; full card numbers and CVVs
 * never reach this class.
 */
public class TopUpService {

    static final String FAILED_MESSAGE =
            "The top-up could not be completed because of a database problem, so your balance was not changed. "
            + "Check that MySQL is running and try again.";
    static final String REQUEST_REUSED_MESSAGE =
            "This top-up was already submitted with different details. Please start a new top-up.";
    static final String BALANCE_LIMIT_MESSAGE =
            "This top-up would take your balance above the maximum the wallet can hold.";
    static final String CARD_EXPIRED_MESSAGE =
            "This card has expired. Choose another card or use a new card.";

    /** Result of a top-up; {@code alreadyProcessed} is true for a repeated request. */
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
        return topUp(userId, amount, requestId, WalletTransaction.TYPE_CASH_TOP_UP, null, null);
    }

    /** Simulated payment with one of the user's saved cards. */
    public TopUpResult savedCardTopUp(long userId, long cardId, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        return topUp(userId, amount, requestId, WalletTransaction.TYPE_CARD_TOP_UP, cardId, null);
    }

    /** Simulated payment with a card just entered (already validated; metadata only). */
    public TopUpResult newCardTopUp(long userId, CardMetadata card, BigDecimal amount, String requestId)
            throws ValidationException, DatabaseException {
        if (card == null) {
            throw new IllegalArgumentException("card is required");
        }
        return topUp(userId, amount, requestId, WalletTransaction.TYPE_CARD_TOP_UP, null, card);
    }

    private TopUpResult topUp(long userId, BigDecimal amount, String requestId, String type,
                              Long savedCardId, CardMetadata newCard)
            throws ValidationException, DatabaseException {
        Amounts.checkTopUp(amount);
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
            // READ COMMITTED: the duplicate-request check (a locking read of a request id that
            // usually doesn't exist yet) then takes no gap lock, so transfers on unrelated wallets
            // can't deadlock on the unique request_id index. Every decision is still made on rows
            // locked with FOR UPDATE / FOR SHARE, which always read the latest committed data.
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                TopUpResult result = topUpInTransaction(conn, userId, cents, requestId, type, savedCardId, newCard);
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

    private TopUpResult topUpInTransaction(Connection conn, long userId, BigDecimal amount, String requestId,
                                           String type, Long savedCardId, CardMetadata newCard)
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

        // Idempotency comes first: a retry of a completed top-up returns the original result,
        // even if the saved card was removed in the meantime.
        WalletTransaction earlier = findByRequestId(conn, requestId);
        if (earlier != null) {
            boolean sameRequest = earlier.walletId() == walletId
                    && type.equals(earlier.type())
                    && earlier.amount().compareTo(amount) == 0;
            if (!sameRequest) {
                throw new ValidationException(REQUEST_REUSED_MESSAGE);
            }
            return new TopUpResult(earlier, true);
        }

        CardMetadata card = newCard;
        if (savedCardId != null) {
            SavedCard saved = PaymentCardService.findOwnedCard(conn, userId, savedCardId);
            if (saved == null) {
                throw new ValidationException(PaymentCardService.NOT_FOUND_MESSAGE);
            }
            card = saved.details();
        }
        if (card != null && card.isExpired(YearMonth.now(clock))) {
            throw new ValidationException(CARD_EXPIRED_MESSAGE);
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
        WalletTransaction record = new WalletTransaction(0, walletId, type, WalletTransaction.CREDIT, amount,
                newBalance, WalletTransaction.STATUS_COMPLETED, now,
                card == null ? null : card.brand().name(), card == null ? null : card.last4());
        long transactionId = insertTransaction(conn, record, requestId);
        return new TopUpResult(new WalletTransaction(transactionId, walletId, type, WalletTransaction.CREDIT,
                amount, newBalance, WalletTransaction.STATUS_COMPLETED, now, record.cardBrand(), record.cardLast4()),
                false);
    }

    /**
     * Inserts the transaction record and returns its id. Protected only so a test can simulate
     * a failure here and check that the balance change is rolled back.
     */
    protected long insertTransaction(Connection conn, WalletTransaction t, String requestId) throws SQLException {
        return TransactionRecords.insert(conn, t, requestId);
    }

    private static WalletTransaction findByRequestId(Connection conn, String requestId) throws SQLException {
        return TransactionRecords.findByRequestId(conn, requestId);
    }
}
