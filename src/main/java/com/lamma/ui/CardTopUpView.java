package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.PaymentCardService;
import com.lamma.cards.SavedCard;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.Money;
import com.lamma.wallet.TopUpService.TopUpResult;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;

/**
 * Credit card top-up (simulated):
 * choose a saved card or enter a new one -> amount -> review -> confirm -> success,
 * then for a new card: "Would you like to save this card for future top-ups?".
 *
 * <p>Repeated credits are prevented like the cash top-up (disabled button, request id reused
 * on retry). Saving the card afterwards is a separate step: if it fails, the completed
 * top-up is neither undone nor repeated.
 */
final class CardTopUpView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private boolean hasSavedCards;
    /** Exactly one of these is set once a card is chosen. */
    private SavedCard savedCard;
    private CardMetadata newCard;
    private BigDecimal amount;
    /** Identifies this confirmation; reused on retry, replaced when the amount is edited. */
    private String requestId;

    CardTopUpView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        loadCards();
    }

    // ---- Step 1: which card ----

    private void loadCards() {
        Label status = Screens.message("cardTopUpMessage");
        Screens.showInfo(status, "Loading your cards…");
        Button cancel = cancelButton();
        getChildren().setAll(Screens.title("Credit Card Top-Up"), status, cancel);
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> navigator.paymentCardService().listCards(userId),
                (List<SavedCard> cards) -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    hasSavedCards = !cards.isEmpty();
                    if (hasSavedCards) {
                        showCardChoice(cards);
                    } else {
                        showNewCardForm();
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(status, error);
                        Button retry = new Button("Retry");
                        retry.setId("retryCardsButton");
                        retry.setOnAction(event -> loadCards());
                        getChildren().add(2, retry);
                    }
                });
    }

    private void showCardChoice(List<SavedCard> cards) {
        VBox choices = new VBox(8);
        choices.setId("cardChoices");
        YearMonth now = YearMonth.now();
        for (SavedCard card : cards) {
            Button choice = new Button();
            choice.setId("chooseCard-" + card.cardId());
            choice.setGraphic(PaymentMethodsView.cardSummary(card));
            choice.getStyleClass().add("card-choice");
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setAlignment(Pos.CENTER_LEFT);
            if (card.details().isExpired(now)) {
                choice.setDisable(true); // shown, but an expired card can't be used
            }
            choice.setOnAction(event -> {
                savedCard = card;
                newCard = null;
                showAmount("");
            });
            choices.getChildren().add(choice);
        }
        choices.setMaxWidth(Double.MAX_VALUE); // the whole page scrolls (AppShell), not just this list

        Button useNew = new Button("Use a New Card");
        useNew.setId("useNewCardButton");
        useNew.setOnAction(event -> showNewCardForm());

        getChildren().setAll(Screens.title("Choose a Card"),
                Screens.hint("Select a saved card or use a new one. Payments are simulated; no card is charged."),
                choices, useNew, cancelButton());
    }

    private void showNewCardForm() {
        CardForm form = new CardForm(false);
        Label message = Screens.message("cardFormMessage");
        Button next = Screens.primaryButton("Continue", "cardContinueButton");
        next.setOnAction(event -> {
            try {
                newCard = form.validate(); // clears the card number and CVV fields
                savedCard = null;
                showAmount("");
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
            }
        });
        Button back;
        if (hasSavedCards) {
            back = Screens.linkButton("Back to saved cards", "backToCardsButton");
            back.setOnAction(event -> loadCards());
        } else {
            back = cancelButton();
        }
        getChildren().setAll(Screens.title("Pay with a New Card"),
                Screens.hint("Simulated payment: your card is not charged. The full card number and CVV are "
                        + "never stored."),
                form.view(), message, next, back);
    }

    // ---- Step 2: amount ----

    private void showAmount(String initialText) {
        requestId = null;
        TextField amountField = new TextField(initialText);
        amountField.setId("amountField");
        amountField.setPromptText("e.g. 25.00");
        amountField.setMaxWidth(260);
        amountField.getStyleClass().add("amount-field");
        Label message = Screens.message("topUpMessage");

        Button next = Screens.primaryButton("Continue", "continueButton");
        next.setOnAction(event -> {
            try {
                BigDecimal parsed = Amounts.parseUsd(amountField.getText());
                Amounts.checkTopUp(parsed);
                showReview(parsed);
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
            }
        });
        getChildren().setAll(Screens.title("How much?"),
                payingWithLabel("payingWithLabel"),
                Screens.hint("Enter the amount to add, in USD (up to " + Money.formatUsd(Amounts.MAX_TOP_UP) + ")."),
                amountField, message, next, cancelButton());
        Platform.runLater(amountField::requestFocus);
    }

    // ---- Step 3: review and confirm ----

    private void showReview(BigDecimal reviewedAmount) {
        this.amount = reviewedAmount;
        this.requestId = UUID.randomUUID().toString();

        Label amountLabel = new Label(Money.formatUsd(reviewedAmount));
        amountLabel.setId("reviewAmountLabel");
        amountLabel.getStyleClass().add("balance");
        Label message = Screens.message("topUpMessage");

        Button confirm = Screens.primaryButton("Confirm Top-Up", "confirmTopUpButton");
        Button edit = Screens.linkButton("Edit amount", "editAmountButton");
        edit.setOnAction(event -> showAmount(reviewedAmount.toPlainString()));
        confirm.setOnAction(event -> confirm(confirm, edit, message));

        getChildren().setAll(Screens.title("Review your top-up"),
                Screens.hint("Credit card top-up to your Personal Wallet"), amountLabel,
                payingWithLabel("reviewPaymentMethodLabel"),
                Screens.hint("This is a simulated payment. No real card is charged."),
                message, confirm, edit);
    }

    private void confirm(Button confirm, Button edit, Label message) {
        confirm.setDisable(true);
        edit.setDisable(true);
        Screens.showInfo(message, "Processing…");
        long userId = user.userId(); // from the session, never from user input
        BigDecimal confirmedAmount = amount;
        String confirmedRequest = requestId;
        SavedCard chosen = savedCard;
        CardMetadata entered = newCard;
        BackgroundWork.run(() -> chosen != null
                        ? navigator.topUpService().savedCardTopUp(userId, chosen.cardId(), confirmedAmount, confirmedRequest)
                        : navigator.topUpService().newCardTopUp(userId, entered, confirmedAmount, confirmedRequest),
                (TopUpResult result) -> {
                    if (stillCurrent()) {
                        showSuccess(result);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    // Same request id on retry: a top-up that did reach the database is not repeated.
                    Screens.showError(message, error);
                    confirm.setDisable(false);
                    edit.setDisable(false);
                });
    }

    // ---- Step 4: success, then optionally save the new card ----

    private void showSuccess(TopUpResult result) {
        Label done = Screens.title("Top-up complete");
        done.setId("topUpSuccessTitle");
        Label added = Screens.hint(Money.formatUsd(result.transaction().amount())
                + " was added to your Personal Wallet.");
        Label newBalance = new Label("New balance: " + Money.formatUsd(result.transaction().balanceAfter()));
        newBalance.setId("newBalanceLabel");
        newBalance.getStyleClass().add("card-title");
        getChildren().setAll(Screens.successMark(), done, added, newBalance);

        if (newCard == null) {
            Button back = Screens.primaryButton("Done", "backToDashboardButton");
            back.setOnAction(event -> navigator.showDashboard());
            getChildren().add(back);
            return;
        }
        Label question = new Label("Would you like to save this card for future top-ups?");
        question.setId("saveCardQuestion");
        question.getStyleClass().add("card-title");
        Button save = Screens.primaryButton("Save Card", "saveCardButton");
        save.setOnAction(event -> showSaveCard(result));
        Button notNow = notNowButton();
        HBox buttons = new HBox(12, save, notNow);
        buttons.setAlignment(Pos.CENTER);
        getChildren().addAll(question, Screens.hint(newCard.masked() + " · " + newCard.cardholderName()), buttons);
    }

    private void showSaveCard(TopUpResult result) {
        TextField nickname = new TextField();
        nickname.setId("nicknameField");
        nickname.setPromptText("e.g. Personal Visa");
        nickname.setMaxWidth(260);
        Label message = Screens.message("saveCardMessage");
        Button save = Screens.primaryButton("Save", "confirmSaveCardButton");
        Button notNow = notNowButton();
        save.setOnAction(event -> {
            String name;
            try {
                name = PaymentCardService.checkNickname(nickname.getText());
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
                return;
            }
            save.setDisable(true);
            Screens.showInfo(message, "Saving…");
            long userId = user.userId();
            CardMetadata card = newCard;
            BackgroundWork.run(() -> navigator.paymentCardService().addCard(userId, name, card),
                    (SavedCard saved) -> {
                        if (stillCurrent()) {
                            showCardSaved(saved);
                        }
                    },
                    error -> {
                        if (stillCurrent()) {
                            // The top-up is already complete; only the card was not saved.
                            Screens.showError(message, error + "\nYour top-up of "
                                    + Money.formatUsd(result.transaction().amount())
                                    + " is complete and was not affected.");
                            save.setDisable(false);
                        }
                    });
        });
        HBox buttons = new HBox(12, save, notNow);
        buttons.setAlignment(Pos.CENTER);
        getChildren().setAll(Screens.title("Save this card"),
                Screens.hint(newCard.masked() + " · Expires " + newCard.expiry() + " · "
                        + newCard.cardholderName()),
                Screens.hint("Give it a nickname. LAMMA keeps only the cardholder name, brand, last four digits "
                        + "and expiry."),
                nickname, message, buttons);
        Platform.runLater(nickname::requestFocus);
    }

    private void showCardSaved(SavedCard saved) {
        newCard = null;
        Label done = Screens.title("Card saved");
        done.setId("cardSavedTitle");
        Button back = Screens.primaryButton("Done", "backToDashboardButton");
        back.setOnAction(event -> navigator.showDashboard());
        getChildren().setAll(Screens.successMark(), done,
                Screens.hint("“" + saved.nickname() + "” is now in your Payment Methods."), back);
    }

    // ---- helpers ----

    private Label payingWithLabel(String id) {
        String text = savedCard != null
                ? "Paying with " + savedCard.nickname() + " (" + savedCard.details().masked() + ")"
                : "Paying with new card " + newCard.masked() + " · " + newCard.cardholderName();
        Label label = Screens.hint(text);
        label.setId(id);
        return label;
    }

    private Button cancelButton() {
        Button cancel = Screens.linkButton("Cancel", "cancelTopUpButton");
        cancel.setOnAction(event -> navigator.showReceive());
        return cancel;
    }

    private Button notNowButton() {
        Button notNow = new Button("Not Now");
        notNow.setId("notNowButton");
        notNow.setOnAction(event -> {
            newCard = null; // nothing about the card is kept
            navigator.showDashboard();
        });
        return notNow;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
