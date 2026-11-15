package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.TransferService.Recipient;
import com.lamma.wallet.TransferService.TransferResult;
import java.math.BigDecimal;
import java.util.UUID;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Send Money (simulated): recipient phone -> look up and show their name -> amount ->
 * review -> confirm -> success.
 *
 * <p>Nothing is deducted until Confirm, and the database re-checks the balance then.
 * Repeated sends are prevented like top-ups: the buttons are disabled while processing and
 * one request id per review is reused on retry, so a retry after a lost response cannot send
 * twice. If the phone number is edited after a lookup, the recipient is cleared and must be
 * looked up again.
 */
final class SendMoneyView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private Recipient recipient;
    /** The sender's balance as last loaded (for guidance only; the database re-checks it). */
    private BigDecimal availableBalance;
    private BigDecimal amount;
    /** Identifies this confirmation; reused on retry, replaced when the details change. */
    private String requestId;

    SendMoneyView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        showRecipientStep("", null);
    }

    // ---- Step 1: recipient ----

    private void showRecipientStep(String phone, Recipient found) {
        requestId = null;
        recipient = found;
        TextField phoneField = new TextField(phone);
        phoneField.setId("recipientPhoneField");
        phoneField.setPromptText("e.g. 70 123 456 or +961 70 123 456");
        phoneField.setMaxWidth(280);
        Label message = Screens.message("sendMessage");
        Button find = Screens.primaryButton("Find Recipient", "findRecipientButton");

        Label name = new Label();
        name.setId("recipientNameLabel");
        name.getStyleClass().add("card-title");
        Label number = centeredHint("");
        number.setId("recipientPhoneLabel");
        Button next = new Button("Continue");
        next.setId("recipientContinueButton");
        next.setOnAction(event -> showAmountStep(""));
        VBox card = new VBox(4, centeredHint("You are sending to:"), name, number, next);
        card.setId("recipientCard");
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("card-row");
        card.setMaxWidth(360);

        Runnable showRecipient = () -> {
            boolean known = recipient != null;
            card.setVisible(known);
            card.setManaged(known);
            find.setDefaultButton(!known);
            next.setDefaultButton(known);
            if (known) {
                name.setText(recipient.fullName());
                number.setText(PhoneNumbers.format(recipient.phoneNumber()));
            }
        };
        showRecipient.run();

        // Editing the number after a lookup invalidates the recipient.
        phoneField.textProperty().addListener((obs, before, now) -> {
            if (recipient != null) {
                recipient = null;
                message.setText("");
                showRecipient.run();
            }
        });

        find.setOnAction(event -> {
            String asked = phoneField.getText();
            find.setDisable(true);
            Screens.showInfo(message, "Looking up…");
            long userId = user.userId(); // from the session
            BackgroundWork.run(() -> navigator.transferService().findRecipient(userId, asked),
                    (Recipient r) -> {
                        find.setDisable(false);
                        // Ignore the answer if the user has left, or typed a different number meanwhile.
                        if (!stillCurrent() || !asked.equals(phoneField.getText())) {
                            return;
                        }
                        message.setText("");
                        recipient = r;
                        showRecipient.run();
                    },
                    error -> {
                        find.setDisable(false);
                        if (stillCurrent() && asked.equals(phoneField.getText())) {
                            Screens.showError(message, error);
                        }
                    });
        });

        Button cancel = cancelButton();
        getChildren().setAll(Screens.title("Who are you paying?"),
                centeredHint("Enter the registered phone number of the LAMMA user you want to pay."),
                phoneField, find, message, card, cancel);
        Platform.runLater(phoneField::requestFocus);
    }

    // ---- Step 2: amount ----

    private void showAmountStep(String initialText) {
        requestId = null;
        Label balanceLabel = centeredHint("Available balance: loading…");
        balanceLabel.setId("availableBalanceLabel");
        TextField amountField = new TextField(initialText);
        amountField.setId("amountField");
        amountField.setPromptText("e.g. 25.00");
        amountField.setMaxWidth(260);
        amountField.getStyleClass().add("amount-field");
        Label message = Screens.message("sendMessage");

        long userId = user.userId();
        BackgroundWork.run(() -> navigator.walletService().loadPersonalWallet(userId),
                (PersonalWallet wallet) -> {
                    if (stillCurrent()) {
                        availableBalance = wallet.balance();
                        balanceLabel.setText("Available balance: " + Money.formatUsd(availableBalance));
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        balanceLabel.setText("Available balance could not be loaded. It will be checked when you confirm.");
                    }
                });

        Button next = Screens.primaryButton("Continue", "continueButton");
        next.setOnAction(event -> {
            try {
                BigDecimal parsed = Amounts.parseUsd(amountField.getText());
                Amounts.checkTransfer(parsed);
                if (availableBalance != null && parsed.compareTo(availableBalance) > 0) {
                    throw new ValidationException("Insufficient funds. Your available balance is "
                            + Money.formatUsd(availableBalance) + ".");
                }
                showReview(parsed);
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
            }
        });
        Button back = Screens.linkButton("Back", "backToRecipientButton");
        Recipient chosen = recipient;
        back.setOnAction(event -> showRecipientStep(chosen.phoneNumber(), chosen));

        getChildren().setAll(Screens.title("How much?"), sendingToLabel("sendingToLabel"), balanceLabel,
                centeredHint("Enter the amount to send, in USD (up to " + Money.formatUsd(Amounts.MAX_TOP_UP) + ")."),
                amountField, message, next, back);
        Platform.runLater(amountField::requestFocus);
    }

    // ---- Step 3: review and confirm (nothing is deducted until Confirm) ----

    private void showReview(BigDecimal reviewedAmount) {
        this.amount = reviewedAmount;
        this.requestId = UUID.randomUUID().toString();

        Label name = Screens.title(recipient.fullName());
        name.setId("reviewRecipientName");
        Label phone = centeredHint(PhoneNumbers.format(recipient.phoneNumber()));
        phone.setId("reviewRecipientPhone");
        Label amountLabel = new Label(Money.formatUsd(reviewedAmount));
        amountLabel.setId("reviewAmountLabel");
        amountLabel.getStyleClass().add("balance");
        Label balance = centeredHint("Your available balance: "
                + (availableBalance == null ? "not loaded" : Money.formatUsd(availableBalance)));
        balance.setId("reviewBalanceLabel");
        Label message = Screens.message("sendMessage");

        Button confirm = Screens.primaryButton("Confirm and Send", "confirmTransferButton");
        Button back = Screens.linkButton("Back", "editTransferButton");
        back.setOnAction(event -> showAmountStep(reviewedAmount.toPlainString()));
        Button cancel = cancelButton();
        confirm.setOnAction(event -> confirm(confirm, back, cancel, message));
        HBox links = new HBox(16, back, cancel);
        links.setAlignment(Pos.CENTER);

        getChildren().setAll(Screens.title("Review your transfer"), centeredHint("You are sending"), amountLabel,
                centeredHint("to"), name, phone, balance,
                centeredHint("This moves simulated funds between LAMMA wallets. No real money is sent."),
                message, confirm, links);
    }

    private void confirm(Button confirm, Button back, Button cancel, Label message) {
        confirm.setDisable(true);
        back.setDisable(true);
        cancel.setDisable(true);
        Screens.showInfo(message, "Sending…");
        long userId = user.userId(); // the sender is always the signed-in user
        long recipientId = recipient.userId();
        BigDecimal confirmedAmount = amount;
        String confirmedRequest = requestId;
        BackgroundWork.run(() -> navigator.transferService().send(userId, recipientId, confirmedAmount, confirmedRequest),
                (TransferResult result) -> {
                    if (stillCurrent()) {
                        showSuccess(result);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    // Same request id on retry: a transfer that did reach the database is not repeated.
                    Screens.showError(message, error);
                    confirm.setDisable(false);
                    back.setDisable(false);
                    cancel.setDisable(false);
                });
    }

    // ---- Step 4: success ----

    private void showSuccess(TransferResult result) {
        Label done = Screens.title("Money sent");
        done.setId("transferSuccessTitle");
        Label detail = centeredHint(Money.formatUsd(result.sent().amount()) + " was sent to "
                + result.recipient().fullName() + ".");
        detail.setId("transferSuccessDetail");
        Label newBalance = new Label("New balance: " + Money.formatUsd(result.sent().balanceAfter()));
        newBalance.setId("newBalanceLabel");
        newBalance.getStyleClass().add("card-title");
        Button back = Screens.primaryButton("Done", "backToDashboardButton");
        back.setOnAction(event -> navigator.showDashboard());
        getChildren().setAll(Screens.successMark(), done, detail, newBalance, back);
    }

    // ---- helpers ----

    private Label sendingToLabel(String id) {
        Label label = centeredHint("Sending to " + recipient.fullName() + " · "
                + PhoneNumbers.format(recipient.phoneNumber()));
        label.setId(id);
        return label;
    }

    /** A hint line centred on this screen (Screens.hint is left-aligned for use in rows). */
    private static Label centeredHint(String text) {
        Label label = Screens.hint(text);
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Button cancelButton() {
        Button cancel = Screens.linkButton("Cancel", "cancelSendButton");
        cancel.setOnAction(event -> navigator.showDashboard());
        return cancel;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
