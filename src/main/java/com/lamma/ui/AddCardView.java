package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.PaymentCardService;
import com.lamma.cards.SavedCard;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Payment Methods > Add Card: nickname plus card details; saves metadata only. */
final class AddCardView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final CardForm form = new CardForm(true);
    private final Label message = Screens.message("addCardMessage");
    private final Button save = Screens.primaryButton("Save Card", "saveNewCardButton");
    /** Set once the card details passed validation (number and CVV are then already cleared). */
    private CardMetadata validatedCard;

    AddCardView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");

        save.setOnAction(event -> save());
        Button cancel = Screens.linkButton("Cancel", "cancelAddCardButton");
        cancel.setOnAction(event -> navigator.showPaymentMethods(""));

        getChildren().addAll(Screens.title("Add a Card"),
                Screens.hint("For simulated top-ups only. LAMMA keeps the cardholder name, brand, last four digits "
                        + "and expiry, never the full card number or CVV."),
                form.view(), message, save, cancel);
    }

    private void save() {
        List<String> problems = new ArrayList<>();
        String nickname = null;
        try {
            nickname = PaymentCardService.checkNickname(form.nickname());
        } catch (ValidationException e) {
            problems.addAll(e.problems());
        }
        if (validatedCard == null) {
            try {
                form.check(); // report card problems together with nickname problems
            } catch (ValidationException e) {
                problems.addAll(e.problems());
            }
        }
        if (!problems.isEmpty()) {
            Screens.showError(message, String.join("\n", problems));
            return;
        }
        if (validatedCard == null) {
            try {
                validatedCard = form.validate(); // clears the card number and CVV
                form.lockAs(validatedCard);
            } catch (ValidationException e) {
                Screens.showError(message, e.getMessage());
                return;
            }
        }

        String name = nickname;
        CardMetadata card = validatedCard;
        save.setDisable(true);
        Screens.showInfo(message, "Saving…");
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> navigator.paymentCardService().addCard(userId, name, card),
                (SavedCard saved) -> {
                    if (stillCurrent()) {
                        navigator.showPaymentMethods("“" + saved.nickname() + "” was saved.");
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        save.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
