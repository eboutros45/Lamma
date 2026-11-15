package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.Money;
import com.lamma.wallet.TopUpService.TopUpResult;
import java.math.BigDecimal;
import java.util.UUID;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Cash top-up in three steps: enter amount -> review and confirm -> success.
 *
 * <p>Repeated credits are prevented three ways: the Confirm button is disabled while the
 * top-up runs; after success the Confirm button is gone; and a request id is created once per
 * review step, so even a retried request is credited only once by the database.
 */
final class CashTopUpView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private BigDecimal amount;
    /** Identifies this confirmation; reused on retry, replaced when the amount is edited. */
    private String requestId;

    CashTopUpView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        showEnterAmount("");
    }

    // ---- Step 1: enter amount ----

    private void showEnterAmount(String initialText) {
        requestId = null;
        TextField amountField = new TextField(initialText);
        amountField.setId("amountField");
        amountField.setPromptText("e.g. 25.00");
        amountField.setMaxWidth(220);
        Label message = Screens.message("topUpMessage");

        Button next = Screens.primaryButton("Continue", "continueButton");
        next.setOnAction(event -> {
            try {
                BigDecimal parsed = Amounts.parseUsd(amountField.getText());
                Amounts.checkCashTopUp(parsed);
                showReview(parsed);
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
            }
        });
        Button cancel = Screens.linkButton("Cancel", "cancelTopUpButton");
        cancel.setOnAction(event -> navigator.showReceive());

        getChildren().setAll(Screens.appTitle(), Screens.title("Cash Top-Up"),
                Screens.hint("Enter the amount of cash to add, in USD (up to "
                        + Money.formatUsd(Amounts.MAX_CASH_TOP_UP) + ")."),
                amountField, message, next, cancel);
        Platform.runLater(amountField::requestFocus);
    }

    // ---- Step 2: review and confirm ----

    private void showReview(BigDecimal reviewedAmount) {
        this.amount = reviewedAmount;
        this.requestId = UUID.randomUUID().toString();

        Label amountLabel = new Label(Money.formatUsd(reviewedAmount));
        amountLabel.setId("reviewAmountLabel");
        amountLabel.getStyleClass().add("balance");
        Label message = Screens.message("topUpMessage");

        Button confirm = Screens.primaryButton("Confirm Top-Up", "confirmTopUpButton");
        Button edit = Screens.linkButton("Edit amount", "editAmountButton");
        edit.setOnAction(event -> showEnterAmount(reviewedAmount.toPlainString()));
        confirm.setOnAction(event -> confirm(confirm, edit, message));

        getChildren().setAll(Screens.appTitle(), Screens.title("Review your top-up"),
                Screens.hint("Cash top-up to your Personal Wallet"), amountLabel,
                Screens.hint("This is simulated money. No real payment is made."),
                message, confirm, edit);
    }

    private void confirm(Button confirm, Button edit, Label message) {
        confirm.setDisable(true);
        edit.setDisable(true);
        Screens.showInfo(message, "Processing…");
        long userId = user.userId(); // from the session, never from user input
        BigDecimal confirmedAmount = amount;
        String confirmedRequest = requestId;
        BackgroundWork.run(() -> navigator.topUpService().cashTopUp(userId, confirmedAmount, confirmedRequest),
                (TopUpResult result) -> {
                    if (stillCurrent()) {
                        showSuccess(result);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    // Same request id on retry: if the first attempt did reach the database,
                    // retrying cannot credit the wallet a second time.
                    Screens.showError(message, error);
                    confirm.setDisable(false);
                    edit.setDisable(false);
                });
    }

    // ---- Step 3: success ----

    private void showSuccess(TopUpResult result) {
        Label done = Screens.title("Top-up complete");
        done.setId("topUpSuccessTitle");
        Label added = Screens.hint(Money.formatUsd(result.transaction().amount())
                + " was added to your Personal Wallet.");
        Label newBalance = new Label("New balance: " + Money.formatUsd(result.transaction().balanceAfter()));
        newBalance.setId("newBalanceLabel");
        newBalance.getStyleClass().add("card-title");

        Button back = Screens.primaryButton("Back to Dashboard", "backToDashboardButton");
        back.setOnAction(event -> navigator.showDashboard());

        getChildren().setAll(Screens.appTitle(), done, added, newBalance, back);
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
