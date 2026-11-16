package com.lamma.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of wallet_transactions: a single change to one wallet's balance.
 *
 * @param createdAt            when it happened, in UTC
 * @param cardBrand            card top-ups: brand of the card used (e.g. "VISA"); otherwise null
 * @param cardLast4            card top-ups: last four digits of the card used; otherwise null
 * @param transferId           transfers: id shared by the sent and received rows; otherwise null
 * @param counterpartyWalletId transfers: the other wallet; otherwise null
 * @param counterpartyName     transfers: "First Last" of the other wallet's owner, or the shared
 *                             wallet's name when the other wallet is shared (read, not stored)
 * @param transferKind         shared-wallet transfers: {@link #KIND_CONTRIBUTION}, {@link #KIND_WITHDRAWAL}
 *                             or {@link #KIND_SHARED_SEND}; null for personal Send Money and top-ups
 * @param initiatedByName      shared-wallet transfers: "First Last" of the member who started it
 * @param expenseTitle         expense payments and reimbursements: the expense's title (read, not stored)
 */
public record WalletTransaction(long transactionId, long walletId, String type, String direction,
                                BigDecimal amount, BigDecimal balanceAfter, String status,
                                LocalDateTime createdAt, String cardBrand, String cardLast4,
                                String transferId, Long counterpartyWalletId, String counterpartyName,
                                String transferKind, String initiatedByName, String expenseTitle) {

    /** A transaction that isn't an expense payment. */
    public WalletTransaction(long transactionId, long walletId, String type, String direction,
                             BigDecimal amount, BigDecimal balanceAfter, String status, LocalDateTime createdAt,
                             String cardBrand, String cardLast4, String transferId, Long counterpartyWalletId,
                             String counterpartyName, String transferKind, String initiatedByName) {
        this(transactionId, walletId, type, direction, amount, balanceAfter, status, createdAt, cardBrand,
                cardLast4, transferId, counterpartyWalletId, counterpartyName, transferKind, initiatedByName, null);
    }

    // Values stored in the database (see V003-V005 migrations).
    public static final String TYPE_CASH_TOP_UP = "CASH_TOP_UP";
    public static final String TYPE_CARD_TOP_UP = "CARD_TOP_UP";
    public static final String TYPE_TRANSFER_OUT = "TRANSFER_OUT";
    public static final String TYPE_TRANSFER_IN = "TRANSFER_IN";
    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    // Kinds of shared-wallet transfer (V008).
    public static final String KIND_CONTRIBUTION = "CONTRIBUTION";
    public static final String KIND_WITHDRAWAL = "WITHDRAWAL";
    public static final String KIND_SHARED_SEND = "SHARED_SEND";
    // Kinds of expense payment (V010).
    public static final String KIND_EXPENSE_PAYMENT = "EXPENSE_PAYMENT";
    public static final String KIND_REIMBURSEMENT = "REIMBURSEMENT";
    // Gift delivery (V011).
    public static final String KIND_GIFT_DELIVERY = "GIFT_DELIVERY";

    /** A transaction that is not a shared-wallet transfer (top-up or personal Send Money). */
    public WalletTransaction(long transactionId, long walletId, String type, String direction,
                             BigDecimal amount, BigDecimal balanceAfter, String status, LocalDateTime createdAt,
                             String cardBrand, String cardLast4, String transferId, Long counterpartyWalletId,
                             String counterpartyName) {
        this(transactionId, walletId, type, direction, amount, balanceAfter, status, createdAt, cardBrand,
                cardLast4, transferId, counterpartyWalletId, counterpartyName, null, null);
    }

    /** A transaction without card or transfer details (e.g. a cash top-up). */
    public WalletTransaction(long transactionId, long walletId, String type, String direction,
                             BigDecimal amount, BigDecimal balanceAfter, String status, LocalDateTime createdAt) {
        this(transactionId, walletId, type, direction, amount, balanceAfter, status, createdAt, null, null);
    }

    /** A transaction that may have card details but no transfer details (a top-up). */
    public WalletTransaction(long transactionId, long walletId, String type, String direction,
                             BigDecimal amount, BigDecimal balanceAfter, String status, LocalDateTime createdAt,
                             String cardBrand, String cardLast4) {
        this(transactionId, walletId, type, direction, amount, balanceAfter, status, createdAt,
                cardBrand, cardLast4, null, null, null);
    }

    public boolean isCredit() {
        return CREDIT.equals(direction);
    }

    /** A copy with the database id filled in (after insert). */
    WalletTransaction withId(long id) {
        return new WalletTransaction(id, walletId, type, direction, amount, balanceAfter, status, createdAt,
                cardBrand, cardLast4, transferId, counterpartyWalletId, counterpartyName, transferKind,
                initiatedByName, expenseTitle);
    }
}
