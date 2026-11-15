package com.lamma.ui;

import com.lamma.auth.ValidationException;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.CardValidator;
import java.time.YearMonth;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Card-entry fields (cardholder, number, expiry, CVV, and optionally a nickname).
 *
 * <p>{@link #validate()} turns the input into {@link CardMetadata} and immediately clears the
 * card number and CVV fields, so they are not kept on screen or in memory after submission.
 */
final class CardForm {

    private final TextField holder = new TextField();
    private final TextField number = new TextField();
    private final TextField expiry = new TextField();
    private final PasswordField cvv = new PasswordField();
    private final TextField nickname = new TextField();
    private final GridPane grid = new GridPane();

    CardForm(boolean withNickname) {
        holder.setId("cardholderField");
        holder.setPromptText("Name on card");
        number.setId("cardNumberField");
        number.setPromptText("e.g. 4242 4242 4242 4242");
        expiry.setId("expiryField");
        expiry.setPromptText("MM/YY");
        cvv.setId("cvvField");
        cvv.setPromptText("3 or 4 digits");
        nickname.setId("nicknameField");
        nickname.setPromptText("e.g. Personal Visa");

        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        int row = 0;
        if (withNickname) {
            grid.addRow(row++, new Label("Card nickname"), nickname);
        }
        grid.addRow(row++, new Label("Cardholder name"), holder);
        grid.addRow(row++, new Label("Card number"), number);
        grid.addRow(row++, new Label("Expiration date"), expiry);
        grid.addRow(row, new Label("CVV"), cvv);
    }

    GridPane view() {
        return grid;
    }

    String nickname() {
        return nickname.getText();
    }

    /** Checks the card without clearing anything (to report problems alongside other fields). */
    void check() throws ValidationException {
        CardValidator.validate(holder.getText(), number.getText(), expiry.getText(), cvv.getText(), YearMonth.now());
    }

    /** Validates the card; on success clears the card number and CVV and returns the metadata. */
    CardMetadata validate() throws ValidationException {
        CardMetadata card = CardValidator.validate(holder.getText(), number.getText(), expiry.getText(),
                cvv.getText(), YearMonth.now());
        number.clear();
        cvv.clear();
        return card;
    }

    /** After a successful validation: show which card was accepted and lock the card fields. */
    void lockAs(CardMetadata card) {
        number.setPromptText(card.masked());
        cvv.setPromptText("•••");
        for (TextField field : new TextField[] {holder, number, expiry, cvv}) {
            field.setDisable(true);
        }
    }
}
