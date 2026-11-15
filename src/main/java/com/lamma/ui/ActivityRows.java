package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.WalletTransaction;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Transaction rows, shared by the Home activity list and the wallet history. */
final class ActivityRows {

    private ActivityRows() {
    }

    /** Replaces the list's content with the transactions, or an empty-state message. */
    static void fill(VBox list, List<WalletTransaction> transactions, String emptyText) {
        list.getChildren().clear();
        if (transactions.isEmpty()) {
            Label empty = Screens.hint(emptyText);
            empty.setId("noActivityLabel");
            list.getChildren().add(empty);
            return;
        }
        for (WalletTransaction t : transactions) {
            list.getChildren().add(row(t));
        }
    }

    static HBox row(WalletTransaction t) {
        Label type = new Label(TransactionLabels.type(t));
        type.getStyleClass().add("activity-type");
        Label details = Screens.caption(TransactionLabels.details(t));
        details.getStyleClass().add("hint");
        VBox left = new VBox(2, type, details);
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);

        Label amount = new Label(TransactionLabels.signedAmount(t));
        amount.getStyleClass().add(t.isCredit() ? "amount-credit" : "amount-debit");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        Label status = Screens.caption(TransactionLabels.status(t));
        status.getStyleClass().add("hint");
        VBox right = new VBox(2, amount, status);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, Screens.iconChip(icon(t), t.isCredit()), left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("activity-row");
        return row;
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
