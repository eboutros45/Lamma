package com.lamma.ui;

import com.lamma.cards.CardBrand;
import com.lamma.wallet.Money;
import com.lamma.wallet.WalletTransaction;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Turns stored transaction values into text for the screen. */
final class TransactionLabels {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US);

    private TransactionLabels() {
    }

    static String type(WalletTransaction t) {
        if (t.transferKind() != null) {
            return switch (t.transferKind()) {
                case WalletTransaction.KIND_CONTRIBUTION -> "Contribution";
                case WalletTransaction.KIND_WITHDRAWAL -> "Withdrawal";
                case WalletTransaction.KIND_EXPENSE_PAYMENT -> "Expense payment";
                case WalletTransaction.KIND_REIMBURSEMENT -> "Reimbursement";
                case WalletTransaction.KIND_GIFT_DELIVERY -> t.isCredit() ? "Gift received" : "Gift delivered";
                default -> t.isCredit() ? "Money Received" : "Money Sent";
            };
        }
        return switch (t.type()) {
            case WalletTransaction.TYPE_CASH_TOP_UP -> "Cash Top-Up";
            case WalletTransaction.TYPE_CARD_TOP_UP -> "Card Top-Up";
            case WalletTransaction.TYPE_TRANSFER_OUT -> "Money Sent";
            case WalletTransaction.TYPE_TRANSFER_IN -> "Money Received";
            default -> t.type();
        };
    }

    static String status(WalletTransaction t) {
        return switch (t.status()) {
            case WalletTransaction.STATUS_COMPLETED -> "Completed";
            default -> t.status();
        };
    }

    /** "+$25.00" for money in, "-$25.00" for money out. */
    static String signedAmount(WalletTransaction t) {
        return (t.isCredit() ? "+" : "-") + Money.formatUsd(t.amount());
    }

    /**
     * Second line of an activity row: the other person, shared wallet or card (if any), then the
     * local date and time. Shared-wallet transfers also say who sent shared money and give a
     * short reference.
     */
    static String details(WalletTransaction t) {
        String when = dateTime(t.createdAt());
        if (t.transferKind() != null && t.counterpartyName() != null) {
            String text = (t.isCredit() ? "From " : "To ") + t.counterpartyName();
            if ((WalletTransaction.KIND_SHARED_SEND.equals(t.transferKind())
                    || WalletTransaction.KIND_GIFT_DELIVERY.equals(t.transferKind())) && t.initiatedByName() != null) {
                text += (t.isCredit() ? " \u00b7 sent by " : " \u00b7 by ") + t.initiatedByName();
            }
            if (t.expenseTitle() != null) {
                text += " \u00b7 for \u201c" + t.expenseTitle() + "\u201d";
            }
            return text + " \u00b7 " + when + " \u00b7 Ref " + reference(t);
        }
        if (t.counterpartyName() != null) {
            return (t.isCredit() ? "From " : "To ") + t.counterpartyName() + " \u00b7 " + when;
        }
        if (t.cardLast4() == null) {
            return when;
        }
        return CardBrand.fromStored(t.cardBrand()).displayName() + " \u2022\u2022\u2022\u2022 " + t.cardLast4()
                + " \u00b7 " + when;
    }

    /**
     * A short reference for a transfer: the first 8 characters of its transfer id, which both
     * rows of the transfer share (the full id is stored).
     */
    static String reference(WalletTransaction t) {
        return reference(t.transferId());
    }

    /**
     * The short reference for a transfer id. Expense payments use their payment request's number
     * ("EXP-12"), because their transfer id is derived from it; others the first 8 characters.
     */
    static String reference(String transferId) {
        if (transferId != null && transferId.startsWith(com.lamma.wallet.GiftDeliveryService.TRANSFER_PREFIX)) {
            return "GIFT-" + transferId.substring(com.lamma.wallet.GiftDeliveryService.TRANSFER_PREFIX.length());
        }
        if (transferId != null && transferId.startsWith(com.lamma.wallet.ExpenseService.TRANSFER_PREFIX)) {
            return "EXP-" + transferId.substring(com.lamma.wallet.ExpenseService.TRANSFER_PREFIX.length());
        }
        String id = transferId == null ? "" : transferId.replace("-", "");
        return id.substring(0, Math.min(8, id.length())).toUpperCase(Locale.ROOT);
    }

    /** Stored in UTC; shown in this computer's time zone. */
    static String dateTime(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME);
    }
}
