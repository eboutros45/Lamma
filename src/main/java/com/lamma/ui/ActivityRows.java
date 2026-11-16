package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.Money;
import com.lamma.wallet.WalletTransaction;
import java.time.LocalDate;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Transaction rows, shared by the Home activity list, the wallet histories and a shared wallet's
 * recent activity.
 *
 * <p>Each row: an incoming/outgoing icon; the type; what it was about (wraps instead of being
 * cut, so names, expense titles and references stay readable on narrow windows); the time and
 * reference; and on the right the signed amount (+ green in, − out) and the status. Full histories
 * group rows under day headings ("Today", "Yesterday", "Sat, Nov 15, 2026"); the order is the
 * newest first, as given.
 */
final class ActivityRows {

    private ActivityRows() {
    }

    /** Replaces the list's content with the transactions (no day headings), or an empty-state message. */
    static void fill(VBox list, List<WalletTransaction> transactions, String emptyText) {
        fill(list, transactions, emptyText, false);
    }

    /** Like {@link #fill(VBox, List, String)}, grouped under day headings when {@code byDay}. */
    static void fill(VBox list, List<WalletTransaction> transactions, String emptyText, boolean byDay) {
        list.getChildren().clear();
        if (transactions.isEmpty()) {
            Label empty = Screens.hint(emptyText);
            empty.setId("noActivityLabel");
            list.getChildren().add(empty);
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate currentDay = null;
        for (WalletTransaction t : transactions) {
            if (byDay) {
                LocalDate day = TransactionLabels.localDate(t.createdAt());
                if (!day.equals(currentDay)) {
                    currentDay = day;
                    Label heading = Screens.caption(TransactionLabels.day(t.createdAt(), today));
                    heading.getStyleClass().add("day-heading");
                    list.getChildren().add(heading);
                }
            }
            list.getChildren().add(row(t, byDay));
        }
    }

    static HBox row(WalletTransaction t) {
        return row(t, false);
    }

    static HBox row(WalletTransaction t, boolean timeOnly) {
        Label type = new Label(TransactionLabels.type(t));
        type.getStyleClass().add("activity-type");
        type.setWrapText(true);
        VBox left = new VBox(2, type);
        String description = TransactionLabels.description(t);
        if (!description.isEmpty()) {
            left.getChildren().add(wrapping(description));
        }
        left.getChildren().add(wrapping(TransactionLabels.timeAndReference(t, timeOnly)));
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);

        Label amount = new Label(TransactionLabels.signedAmount(t));
        amount.getStyleClass().add(t.isCredit() ? "amount-credit" : "amount-debit");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        amount.setAccessibleText((t.isCredit() ? "Incoming " : "Outgoing ") + Money.formatUsd(t.amount()));
        Label status = Screens.caption(TransactionLabels.status(t));
        status.getStyleClass().add("hint");
        VBox right = new VBox(2, amount, status);
        right.setAlignment(Pos.TOP_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, Screens.iconChip(icon(t), t.isCredit()), left, right);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("activity-row");
        return row;
    }

    /** A secondary line that wraps instead of ending in "…". */
    private static Label wrapping(String text) {
        Label label = Screens.caption(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE); // keep wrapped lines from being squeezed
        return label;
    }

    private static Icon icon(WalletTransaction t) {
        return switch (t.type()) {
            case WalletTransaction.TYPE_CASH_TOP_UP -> Icon.CASH;
            case WalletTransaction.TYPE_CARD_TOP_UP -> Icon.CARD;
            case WalletTransaction.TYPE_TRANSFER_OUT -> Icon.SENT;
            case WalletTransaction.TYPE_TRANSFER_IN -> Icon.RECEIVED;
            default -> t.isCredit() ? Icon.RECEIVED : Icon.SENT;
        };
    }
}
