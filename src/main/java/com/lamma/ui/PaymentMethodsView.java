package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.cards.SavedCard;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.Callable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Payment Methods: the user's saved cards with Rename and Remove, plus Add Card. */
final class PaymentMethodsView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final VBox list = new VBox(8);
    private final Label message = Screens.message("paymentMethodsMessage");

    PaymentMethodsView(AppNavigator navigator, AuthenticatedUser user, String notice) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().addAll("screen", "payment-methods");
        setStyle("-fx-max-width: 720; -fx-alignment: top-left;"); // destination page, like Wallets

        list.setId("cardList");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-scroll");
        // No forced growth: the list takes the height it needs (Add Card stays right below it)
        // and shrinks and scrolls only when there are many cards.

        Button add = Screens.primaryButton("Add Card", "addCardButton");
        add.setOnAction(event -> navigator.showAddCard());

        if (!notice.isEmpty()) {
            Screens.showSuccess(message, notice);
        }
        Screens.withIcon(add, com.lamma.ui.Icons.Icon.ADD);
        getChildren().addAll(Screens.title("Cards"),
                Screens.hint("Saved cards are used only for simulated top-ups. LAMMA never stores your full "
                        + "card number or CVV."),
                message, scroll, add);
        load();
    }

    private void load() {
        list.getChildren().setAll(Screens.hint("Loading your cards…"));
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> navigator.paymentCardService().listCards(userId),
                (List<SavedCard> cards) -> {
                    if (stillCurrent()) {
                        show(cards);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        list.getChildren().clear();
                        Screens.showError(message, error);
                    }
                });
    }

    private void show(List<SavedCard> cards) {
        list.getChildren().clear();
        if (cards.isEmpty()) {
            Label empty = Screens.hint("You have no saved cards yet. Add a card to use it for simulated top-ups.");
            empty.setId("noCardsLabel");
            list.getChildren().add(empty);
            return;
        }
        for (SavedCard card : cards) {
            list.getChildren().add(cardRow(card));
        }
    }

    private VBox cardRow(SavedCard card) {
        VBox row = new VBox(4);
        row.getStyleClass().add("card-row");
        showDetails(row, card);
        return row;
    }

    private void showDetails(VBox row, SavedCard card) {
        Button rename = new Button("Rename");
        rename.setId("renameCard-" + card.cardId());
        rename.setOnAction(event -> showRename(row, card));
        Button remove = new Button("Remove");
        remove.setId("removeCard-" + card.cardId());
        remove.setOnAction(event -> showRemove(row, card));
        HBox actions = new HBox(8, rename, remove);
        actions.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().setAll(cardSummary(card), actions);
    }

    /** Card graphic, then nickname prominently, cardholder and masked details. Also used by card selection. */
    static HBox cardSummary(SavedCard card) {
        Label nickname = new Label(card.nickname());
        nickname.setId("cardNickname-" + card.cardId());
        nickname.getStyleClass().add("card-nickname");
        Label holder = Screens.hint(card.details().cardholderName());
        boolean expired = card.details().isExpired(YearMonth.now());
        Label details = Screens.hint(card.details().masked() + " · Expires " + card.details().expiry()
                + (expired ? " · Expired" : ""));
        // Short single lines: no wrapping. (Wrapping labels inside a Button's graphic are measured
        // at a tiny width and would make the card-choice button hundreds of pixels tall.)
        holder.setWrapText(false);
        details.setWrapText(false);
        Label brandMark = new Label(card.details().brand() == com.lamma.cards.CardBrand.OTHER
                ? "CARD" : card.details().brand().name());
        javafx.scene.layout.StackPane face = new javafx.scene.layout.StackPane(brandMark);
        face.getStyleClass().add("card-face");
        HBox summary = new HBox(14, face, new VBox(2, nickname, holder, details));
        summary.setAlignment(Pos.CENTER_LEFT);
        return summary;
    }

    private void showRename(VBox row, SavedCard card) {
        TextField field = new TextField(card.nickname());
        field.setId("renameField-" + card.cardId());
        Button save = new Button("Save");
        save.setId("saveRename-" + card.cardId());
        save.setDefaultButton(true);
        Button cancel = Screens.linkButton("Cancel", "cancelRename-" + card.cardId());
        cancel.setOnAction(event -> showDetails(row, card));
        save.setOnAction(event -> change(save,
                () -> {
                    navigator.paymentCardService().renameCard(user.userId(), card.cardId(), field.getText());
                    return null;
                }, "Card renamed."));
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox edit = new HBox(8, field, save, cancel);
        edit.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().setAll(Screens.hint("New nickname for " + card.details().masked() + ":"), edit);
        field.requestFocus();
    }

    private void showRemove(VBox row, SavedCard card) {
        Label question = new Label("Remove “" + card.nickname() + "”? Past top-ups stay in your activity.");
        question.setWrapText(true);
        Button confirm = new Button("Remove");
        confirm.setId("confirmRemove-" + card.cardId());
        confirm.getStyleClass().add("danger-button");
        Button cancel = Screens.linkButton("Cancel", "cancelRemove-" + card.cardId());
        cancel.setOnAction(event -> showDetails(row, card));
        confirm.setOnAction(event -> change(confirm,
                () -> {
                    navigator.paymentCardService().removeCard(user.userId(), card.cardId());
                    return null;
                }, "Card removed."));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, confirm, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().setAll(question, buttons);
    }

    /** Runs a rename/remove in the background, then reloads the list. */
    private void change(Button button, Callable<Void> work, String doneMessage) {
        button.setDisable(true);
        BackgroundWork.run(work,
                ignored -> {
                    if (stillCurrent()) {
                        Screens.showSuccess(message, doneMessage);
                        load();
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        button.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
