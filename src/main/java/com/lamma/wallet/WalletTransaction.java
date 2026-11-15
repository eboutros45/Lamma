package com.lamma.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of wallet_transactions: a single change to one wallet's balance.
 *
 * @param createdAt  when it happened, in UTC
 * @param cardBrand  for card top-ups, the brand of the card used (e.g. "VISA"); otherwise null
 * @param cardLast4  for card top-ups, the last four digits of the card used; otherwise null
 */
public record WalletTransaction(long transactionId, long walletId, String type, String direction,
                                BigDecimal amount, BigDecimal balanceAfter, String status,
                                LocalDateTime createdAt, String cardBrand, String cardLast4) {

    // Values stored in the database (see V003 and V004 migrations).
    public static final String TYPE_CASH_TOP_UP = "CASH_TOP_UP";
    public static final String TYPE_CARD_TOP_UP = "CARD_TOP_UP";
    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** A transaction without card details (e.g. a cash top-up). */
    public WalletTransaction(long transactionId, long walletId, String type, String direction,
                             BigDecimal amount, BigDecimal balanceAfter, String status, LocalDateTime createdAt) {
        this(transactionId, walletId, type, direction, amount, balanceAfter, status, createdAt, null, null);
    }

    public boolean isCredit() {
        return CREDIT.equals(direction);
    }
}
