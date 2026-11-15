package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.contacts.ContactService;
import com.lamma.contacts.ContactService.Contact;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Suggestions from the user's own contacts under a recipient phone field. Typing part of a name
 * or number lists up to {@link #MAX_SHOWN} matches; choosing one only fills the phone field with
 * the contact's number. The screen then looks the number up and validates it as if it had been
 * typed, so a contact never bypasses the registered-user check.
 *
 * <p>The list is part of the screen (not a pop-up), so it follows the theme and the layout.
 */
final class ContactSuggestions extends VBox {

    static final int MAX_SHOWN = 5;

    private final TextField field;
    private List<Contact> contacts = List.of();

    private ContactSuggestions(TextField field) {
        super(4);
        this.field = field;
        setId(field.getId() + "Suggestions");
        getStyleClass().add("contact-suggestions");
        setMaxWidth(360);
        hide();
    }

    /**
     * Adds suggestions for this field and returns the node to place right below it. The user's
     * contacts are loaded once, in the background; if that fails the field simply works without
     * suggestions.
     */
    static ContactSuggestions attach(TextField field, AppNavigator navigator, AuthenticatedUser user) {
        ContactSuggestions suggestions = new ContactSuggestions(field);
        ContactService service = navigator.contactService();
        if (service == null) {
            return suggestions; // e.g. a test without contacts
        }
        long userId = user.userId();
        BackgroundWork.run(() -> service.list(userId),
                (List<Contact> loaded) -> {
                    suggestions.contacts = loaded;
                    suggestions.update(field.getText()); // text typed before the contacts arrived
                },
                error -> { }); // suggestions are optional
        field.textProperty().addListener((obs, before, now) -> suggestions.update(now));
        return suggestions;
    }

    private void update(String text) {
        getChildren().clear();
        if (text == null || text.isBlank()) {
            hide();
            return;
        }
        int shown = 0;
        for (Contact c : contacts) {
            if (!ContactService.matches(c, text)) {
                continue;
            }
            String formatted = PhoneNumbers.format(c.phoneNumber());
            if (formatted.equals(text.strip())) {
                hide(); // this contact was just chosen
                return;
            }
            if (shown == MAX_SHOWN) {
                break;
            }
            getChildren().add(option(c, formatted));
            shown++;
        }
        if (shown == 0) {
            hide();
            return;
        }
        getChildren().add(0, caption());
        setVisible(true);
        setManaged(true);
    }

    private Button option(Contact c, String formatted) {
        Button option = new Button(c.name() + " · " + formatted);
        option.setId("contactSuggestion-" + c.contactId());
        option.getStyleClass().add("suggestion-button");
        option.setMaxWidth(Double.MAX_VALUE);
        option.setMnemonicParsing(false);
        option.setOnAction(event -> {
            field.setText(formatted);
            field.requestFocus();
            field.positionCaret(formatted.length());
            hide();
        });
        return option;
    }

    private static Label caption() {
        Label caption = Screens.caption("From your contacts");
        caption.getStyleClass().add("hint");
        return caption;
    }

    private void hide() {
        setVisible(false);
        setManaged(false);
    }
}
