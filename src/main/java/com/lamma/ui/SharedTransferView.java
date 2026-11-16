package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWalletFundsService.Overview;
import com.lamma.wallet.SharedWalletFundsService.Result;
import com.lamma.wallet.TransferService.Recipient;
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
 * Money in or out of a shared wallet (simulated), in the same steps as Send Money:
 * <ul>
 *   <li><b>Contribute</b> (any member): your Personal Wallet → the shared wallet;</li>
 *   <li><b>Withdraw</b> (Owner or Spender): the shared wallet → your Personal Wallet;</li>
 *   <li><b>Send Money</b> (Owner or Spender): the shared wallet → a registered user, found by
 *       phone number (another member, an outsider or yourself).</li>
 * </ul>
 * Amount → review (from, to, balances) → Confirm → success. Nothing moves until Confirm, and the
 * database re-checks the role and balances then. One request id per review is reused on retry,
 * so a double click or a retry after a lost response never moves money twice.
 */
final class SharedTransferView extends VBox {

    /** The three operations; the title is also the screen's header. */
    enum Mode {
        CONTRIBUTE("Contribute"), WITHDRAW("Withdraw"), SEND("Send Money");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        String title() {
            return title;
        }
    }

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final Mode mode;
    private Overview overview;
    private Recipient recipient;
    private BigDecimal amount;
    /** Identifies this confirmation; reused on retry, replaced when the details change. */
    private String requestId;

    SharedTransferView(AppNavigator navigator, AuthenticatedUser user, long walletId, Mode mode) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        this.mode = mode;
        getStyleClass().add("screen");
        load();
    }

    // ---- Step 0: load the wallet, role and balances ----

    private void load() {
        Label loading = centeredHint("Loading…");
        loading.setId("sharedTransferLoading");
        getChildren().setAll(loading);
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> navigator.fundsService().overview(userId, walletId),
                (Overview o) -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    overview = o;
                    if (o.completed()) {
                        showProblem(com.lamma.wallet.GiftDeliveryService.COMPLETED_MESSAGE, false);
                    } else if (mode != Mode.CONTRIBUTE && !o.myRole().canSendOut()) {
                        showProblem("Only the Owner or a Spender can withdraw or send money from this shared wallet.",
                                false);
                    } else if (mode == Mode.SEND) {
                        showRecipientStep("");
                    } else {
                        showAmountStep("");
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        showProblem(error, !com.lamma.wallet.SharedWalletService.NOT_AVAILABLE_MESSAGE.equals(error));
                    }
                });
    }

    private void showProblem(String text, boolean canRetry) {
        Label message = Screens.message("sharedTransferMessage");
        Screens.showError(message, text);
        getChildren().setAll(Screens.title(mode.title()), message);
        if (canRetry) {
            Button retry = new Button("Retry");
            retry.setId("retrySharedTransferButton");
            retry.setOnAction(event -> load());
            getChildren().add(retry);
        }
        getChildren().add(backToWalletButton("Back to wallet"));
    }

    // ---- Step 1 (Send Money only): recipient ----

    private void showRecipientStep(String phone) {
        requestId = null;
        recipient = null;
        TextField phoneField = new TextField(phone);
        phoneField.setId("sharedRecipientPhoneField");
        phoneField.setPromptText("e.g. 70 123 456 or +961 70 123 456");
        phoneField.setMaxWidth(280);
        Label message = Screens.message("sharedTransferMessage");
        Button find = Screens.primaryButton("Find Recipient", "sharedFindRecipientButton");

        Label name = new Label();
        name.setId("sharedRecipientName");
        name.getStyleClass().add("card-title");
        Label number = centeredHint("");
        Button next = new Button("Continue");
        next.setId("sharedRecipientContinueButton");
        next.setOnAction(event -> showAmountStep(""));
        VBox card = new VBox(4, centeredHint("Paying from “" + overview.walletName() + "” to:"), name, number, next);
        card.setId("sharedRecipientCard");
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
                name.setText(recipientName());
                number.setText(PhoneNumbers.format(recipient.phoneNumber()));
            }
        };
        showRecipient.run();
        phoneField.textProperty().addListener((obs, before, now) -> {
            if (recipient != null) { // editing the number after a lookup clears the recipient
                recipient = null;
                message.setText("");
                showRecipient.run();
            }
        });

        find.setOnAction(event -> {
            String asked = phoneField.getText();
            find.setDisable(true);
            Screens.showInfo(message, "Looking up…");
            long userId = user.userId();
            BackgroundWork.run(() -> navigator.fundsService().findRecipient(userId, walletId, asked),
                    (Recipient r) -> {
                        find.setDisable(false);
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

        getChildren().setAll(Screens.title("Who are you paying?"),
                centeredHint("Money goes from the shared wallet “" + overview.walletName()
                        + "” to the Personal Wallet of any registered LAMMA user. Enter their phone number."),
                phoneField, ContactSuggestions.attach(phoneField, navigator, user), find, message, card,
                cancelButton());
        Platform.runLater(phoneField::requestFocus);
    }

    // ---- Step 2: amount ----

    private void showAmountStep(String initialText) {
        requestId = null;
        TextField amountField = new TextField(initialText);
        amountField.setId("sharedAmountField");
        amountField.setPromptText("e.g. 25.00");
        amountField.setMaxWidth(260);
        amountField.getStyleClass().add("amount-field");
        Label message = Screens.message("sharedTransferMessage");
        Label available = centeredHint(availableText());
        available.setId("sharedAvailableLabel");

        Button next = Screens.primaryButton("Continue", "sharedContinueButton");
        next.setOnAction(event -> {
            try {
                BigDecimal parsed = Amounts.parseUsd(amountField.getText());
                Amounts.checkTransfer(parsed);
                BigDecimal limit = sourceBalance();
                if (limit != null && parsed.compareTo(limit) > 0) {
                    throw new ValidationException(mode == Mode.CONTRIBUTE
                            ? "Insufficient funds. Your available balance is " + Money.formatUsd(limit) + "."
                            : "Insufficient funds in “" + overview.walletName() + "”. Its balance is "
                                    + Money.formatUsd(limit) + ".");
                }
                showReview(parsed);
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
            }
        });

        Label route = centeredHint("From " + fromName() + " to " + toName());
        route.setId("sharedRouteLabel");
        getChildren().setAll(Screens.title("How much?"), route, available,
                centeredHint("Enter the amount in USD (up to " + Money.formatUsd(Amounts.MAX_TOP_UP)
                        + " at a time)."), amountField, message, next);
        if (mode == Mode.SEND) {
            Button back = Screens.linkButton("Back", "sharedBackToRecipientButton");
            Recipient chosen = recipient;
            back.setOnAction(event -> {
                showRecipientStep(PhoneNumbers.format(chosen.phoneNumber()));
            });
            getChildren().add(back);
        } else {
            getChildren().add(cancelButton());
        }
        Platform.runLater(amountField::requestFocus);
    }

    // ---- Step 3: review and confirm (nothing moves until Confirm) ----

    private void showReview(BigDecimal reviewedAmount) {
        this.amount = reviewedAmount;
        this.requestId = UUID.randomUUID().toString();

        Label amountLabel = new Label(Money.formatUsd(reviewedAmount));
        amountLabel.setId("reviewSharedAmount");
        amountLabel.getStyleClass().add("balance");
        VBox from = endpoint("From", fromName(), fromDetail(), "reviewFromLabel");
        VBox to = endpoint("To", toName(), toDetail(), "reviewToLabel");
        Label message = Screens.message("sharedTransferMessage");

        Button confirm = Screens.primaryButton(confirmText(), "confirmSharedTransferButton");
        Button back = Screens.linkButton("Back", "editSharedTransferButton");
        back.setOnAction(event -> showAmountStep(reviewedAmount.toPlainString()));
        Button cancel = cancelButton();
        confirm.setOnAction(event -> confirm(confirm, back, cancel, message));
        HBox links = new HBox(16, back, cancel);
        links.setAlignment(Pos.CENTER);

        getChildren().setAll(Screens.title("Review"), amountLabel, from, to,
                centeredHint("This moves simulated funds between LAMMA wallets. No real money is sent."),
                message, confirm, links);
    }

    private void confirm(Button confirm, Button back, Button cancel, Label message) {
        confirm.setDisable(true);
        back.setDisable(true);
        cancel.setDisable(true);
        Screens.showInfo(message, "Processing…");
        long userId = user.userId(); // the acting member is always the signed-in user
        BigDecimal confirmedAmount = amount;
        String confirmedRequest = requestId;
        Long recipientId = recipient == null ? null : recipient.userId();
        BackgroundWork.run(() -> switch (mode) {
                    case CONTRIBUTE -> navigator.fundsService().contribute(userId, walletId, confirmedAmount,
                            confirmedRequest);
                    case WITHDRAW -> navigator.fundsService().withdraw(userId, walletId, confirmedAmount,
                            confirmedRequest);
                    case SEND -> navigator.fundsService().send(userId, walletId, recipientId, confirmedAmount,
                            confirmedRequest);
                },
                (Result result) -> {
                    if (stillCurrent()) {
                        showSuccess(result);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    // Same request id on retry: an operation that did reach the database is not repeated.
                    Screens.showError(message, error);
                    confirm.setDisable(false);
                    back.setDisable(false);
                    cancel.setDisable(false);
                });
    }

    // ---- Step 4: success ----

    private void showSuccess(Result result) {
        String amountText = Money.formatUsd(result.amount());
        Label done = Screens.title(switch (mode) {
            case CONTRIBUTE -> "Contribution complete";
            case WITHDRAW -> "Withdrawal complete";
            case SEND -> "Money sent";
        });
        done.setId("sharedTransferSuccessTitle");
        Label detail = centeredHint(switch (mode) {
            case CONTRIBUTE -> "You contributed " + amountText + " to “" + result.walletName() + "”.";
            case WITHDRAW -> "You withdrew " + amountText + " from “" + result.walletName() + "” to your Personal Wallet.";
            case SEND -> "“" + result.walletName() + "” sent " + amountText + " to " + recipientName() + ".";
        });
        detail.setId("sharedTransferSuccessDetail");
        Label shared = new Label("“" + result.walletName() + "” balance: " + Money.formatUsd(result.sharedBalanceAfter()));
        shared.setId("sharedBalanceAfterLabel");
        shared.getStyleClass().add("card-title");
        shared.setWrapText(true);
        VBox balances = new VBox(4, shared);
        balances.setAlignment(Pos.CENTER);
        if (result.myBalanceAfter() != null) {
            Label mine = centeredHint("Your Personal Wallet: " + Money.formatUsd(result.myBalanceAfter()));
            mine.setId("myBalanceAfterLabel");
            balances.getChildren().add(mine);
        }
        Button back = Screens.primaryButton("Done", "sharedTransferDoneButton");
        back.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        getChildren().setAll(Screens.successMark(), done, detail, balances, back);
    }

    // ---- helpers ----

    private BigDecimal sourceBalance() {
        return mode == Mode.CONTRIBUTE ? overview.myPersonalBalance() : overview.sharedBalance();
    }

    private String availableText() {
        BigDecimal balance = sourceBalance();
        String value = balance == null ? "not loaded" : Money.formatUsd(balance);
        return mode == Mode.CONTRIBUTE ? "Your available balance: " + value
                : "Available in “" + overview.walletName() + "”: " + value;
    }

    private String sharedName() {
        return "“" + overview.walletName() + "”";
    }

    private String fromName() {
        return mode == Mode.CONTRIBUTE ? "your Personal Wallet" : sharedName();
    }

    private String toName() {
        return switch (mode) {
            case CONTRIBUTE -> sharedName();
            case WITHDRAW -> "your Personal Wallet";
            case SEND -> recipientName();
        };
    }

    private String fromDetail() {
        return mode == Mode.CONTRIBUTE
                ? "Personal Wallet · " + balanceText(overview.myPersonalBalance())
                : "Shared wallet · " + balanceText(overview.sharedBalance());
    }

    private String toDetail() {
        return switch (mode) {
            case CONTRIBUTE -> "Shared wallet · " + balanceText(overview.sharedBalance());
            case WITHDRAW -> "Personal Wallet · " + balanceText(overview.myPersonalBalance());
            case SEND -> "Personal Wallet · " + PhoneNumbers.format(recipient.phoneNumber());
        };
    }

    private static String balanceText(BigDecimal balance) {
        return "balance " + (balance == null ? "not loaded" : Money.formatUsd(balance));
    }

    private String confirmText() {
        return switch (mode) {
            case CONTRIBUTE -> "Confirm Contribution";
            case WITHDRAW -> "Confirm Withdrawal";
            case SEND -> "Confirm and Send";
        };
    }

    private String recipientName() {
        return recipient.fullName() + (recipient.userId() == user.userId() ? " (you)" : "");
    }

    private static VBox endpoint(String caption, String name, String detail, String id) {
        Label title = Screens.caption(caption.toUpperCase(java.util.Locale.ROOT));
        title.getStyleClass().add("hint");
        Label main = new Label(capitalize(name));
        main.setId(id);
        main.getStyleClass().add("card-title");
        main.setWrapText(true);
        Label sub = Screens.hint(detail);
        VBox box = new VBox(2, title, main, sub);
        box.getStyleClass().add("card-row");
        box.setMaxWidth(360);
        return box;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
        return backToWalletButton("Cancel");
    }

    private Button backToWalletButton(String text) {
        Button cancel = Screens.linkButton(text, "cancelSharedTransferButton");
        cancel.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        return cancel;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
