package com.lamma.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of wallet_transactions: a single change to one wallet's balance.
 *
 * @param createdAt when it happened, in UTC
 */
public record WalletTransaction(long transactionId, long walletId, String type, String direction,
                                BigDecimal amount, BigDecimal balanceAfter, String status,
                                LocalDateTime createdAt) {

    // Values stored in the database (see V003__create_wallet_transactions.sql).
    public static final String TYPE_CASH_TOP_UP = "CASH_TOP_UP";
    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public boolean isCredit() {
        return CREDIT.equals(direction);
    }
}
