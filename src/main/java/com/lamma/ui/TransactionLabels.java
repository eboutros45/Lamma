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

    /** Second line of an activity row: the other person or card (if any), then the local date and time. */
    static String details(WalletTransaction t) {
        String when = dateTime(t.createdAt());
        if (t.counterpartyName() != null) {
            return (t.isCredit() ? "From " : "To ") + t.counterpartyName() + " \u00b7 " + when;
        }
        if (t.cardLast4() == null) {
            return when;
        }
        return CardBrand.fromStored(t.cardBrand()).displayName() + " \u2022\u2022\u2022\u2022 " + t.cardLast4()
                + " \u00b7 " + when;
    }

    /** Stored in UTC; shown in this computer's time zone. */
    static String dateTime(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME);
    }
}
