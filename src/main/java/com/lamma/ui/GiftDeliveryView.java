package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.wallet.GiftDeliveryService.Delivery;
import com.lamma.wallet.GiftDeliveryService.Preview;
import com.lamma.wallet.Money;
import java.math.BigDecimal;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Send Gift (Owner only): review → confirm → delivered. The review shows the wallet, the recipient
 * (name and registered number), the current balance, the amount that will be delivered (the whole
 * balance) and the resulting $0.00. If something blocks delivery (a zero balance, or expenses with
 * money outstanding) it explains why instead of offering Confirm. Confirm sends exactly the balance
 * reviewed; if it changed meanwhile, the service refuses and the review is shown again.
 */
final class GiftDeliveryView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final Label message = Screens.message("giftDeliveryMessage");

    GiftDeliveryView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");
        load(null);
    }

    private void load(String problem) {
        getChildren().setAll(Screens.hint("Loading the gift…"));
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.giftService().preview(userId, walletId),
                (Preview p) -> {
                    if (stillCurrent()) {
                        showReview(p);
                        if (problem != null) {
                            Screens.showError(message, problem);
                        }
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        getChildren().setAll(Screens.title("Send Gift"), message, backButton("Back to wallet"));
                    }
                });
    }

    private void showReview(Preview p) {
        message.setText("");
        GridPane facts = new GridPane();
        facts.setHgap(16);
        facts.setVgap(8);
        facts.setId("giftFacts");
        row(facts, 0, "Gift wallet", p.walletName(), "giftWalletName");
        row(facts, 1, "Recipient", p.recipientName(), "giftRecipientFullName");
        row(facts, 2, "Registered number", PhoneNumbers.format(p.recipientPhone()), "giftRecipientPhone");
        row(facts, 3, "Current gift balance", Money.formatUsd(p.balance()), "giftCurrentBalance");
        row(facts, 4, "Amount delivered", Money.formatUsd(p.balance()), "giftDeliveryAmount");
        row(facts, 5, "Wallet balance after", Money.formatUsd(BigDecimal.ZERO), "giftBalanceAfter");
        if (p.targetAmount() != null) {
            row(facts, 6, "Target", Money.formatUsd(p.targetAmount()) + " (information only)", "giftTarget");
        }
        VBox card = new VBox(10, Screens.sectionTitle("Review"), facts);
        card.getStyleClass().add("card");
        card.setMaxWidth(480);

        getChildren().setAll(Screens.title("Send Gift"), card);
        if (p.pendingInvitations() > 0 || p.pendingJoinRequests() > 0) {
            Label closing = Screens.hint("Pending invitations (" + p.pendingInvitations() + ") and join requests ("
                    + p.pendingJoinRequests() + ") will be closed, because a completed gift takes no new members.");
            closing.setId("giftClosingNote");
            closing.setMaxWidth(480);
            getChildren().add(closing);
        }

        if (p.balance().signum() <= 0) {
            Label why = Screens.hint("There's nothing to deliver yet: the gift balance is $0.00. "
                    + "Members can contribute first.");
            why.setId("giftBlockedReason");
            why.setMaxWidth(480);
            getChildren().addAll(why, message, backButton("Back to wallet"));
            return;
        }
        if (!p.unsettledExpenses().isEmpty()) {
            Label why = Screens.hint("Settle or cancel " + (p.unsettledExpenses().size() == 1 ? "this expense" : "these expenses")
                    + " first: " + String.join(", ", p.unsettledExpenses()) + ". Their payment requests would "
                    + "otherwise be left unpaid in a wallet that can no longer receive money.");
            why.setId("giftBlockedReason");
            why.setMaxWidth(480);
            getChildren().addAll(why, message, backButton("Back to wallet"));
            return;
        }

        Label warning = Screens.hint("This sends the whole balance to " + p.recipientName() + "'s Personal Wallet and "
                + "completes the gift. The wallet becomes read-only. This can't be undone.");
        warning.setMaxWidth(480);
        Button confirm = Screens.primaryButton("Send " + Money.formatUsd(p.balance()) + " to " + p.recipientName(),
                "confirmGiftDeliveryButton");
        Button cancel = backButton("Cancel");
        confirm.setOnAction(event -> confirm(p, confirm, cancel));
        getChildren().addAll(warning, message, Screens.keepWidth(confirm), cancel);
    }

    private void confirm(Preview p, Button confirm, Button cancel) {
        confirm.setDisable(true); // one click, one delivery (and the service sends a gift only once anyway)
        cancel.setDisable(true);
        Screens.showInfo(message, "Sending the gift…");
        long userId = user.userId();
        BigDecimal reviewed = p.balance();
        BackgroundWork.run(() -> navigator.giftService().deliver(userId, walletId, reviewed),
                (Delivery d) -> {
                    if (stillCurrent()) {
                        showDone(d);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        load(error); // fresh review (e.g. a new balance), with the reason
                    }
                });
    }

    private void showDone(Delivery d) {
        Label done = Screens.title("Gift delivered");
        done.setId("giftDeliveredTitle");
        Label detail = Screens.hint(Money.formatUsd(d.amount()) + " was sent to " + d.recipientName()
                + "'s Personal Wallet. “" + d.walletName() + "” is now a completed, read-only gift; "
                + "its members can still see its history.");
        detail.setId("giftDeliveredDetail");
        detail.setMaxWidth(480);
        detail.setAlignment(Pos.CENTER);
        detail.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button back = Screens.primaryButton("Done", "giftDeliveredDoneButton");
        back.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        getChildren().setAll(Screens.successMark(), done, detail, back);
    }

    private static void row(GridPane grid, int row, String label, String value, String id) {
        Label name = Screens.caption(label);
        name.getStyleClass().add("hint");
        Label v = new Label(value);
        v.setId(id);
        v.getStyleClass().add("activity-type");
        v.setWrapText(true);
        GridPane.setHgrow(v, Priority.ALWAYS);
        grid.add(name, 0, row);
        grid.add(v, 1, row);
    }

    private Button backButton(String text) {
        Button back = Screens.linkButton(text, "cancelGiftDeliveryButton");
        back.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        return back;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
