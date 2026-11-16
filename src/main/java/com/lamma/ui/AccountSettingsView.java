package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import java.util.concurrent.Callable;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;

/**
 * Account settings from Profile: edit name, change phone number, change PIN. Changing the phone
 * number or the PIN asks for the current PIN, which the service checks (wrong PINs count towards
 * the sign-in lockout). PINs are typed in the six-box PIN entry (P05B) and never shown.
 */
final class AccountSettingsView extends VBox {

    enum Mode {
        NAME("Edit Name"), PHONE("Change Phone Number"), PIN("Change PIN");

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
    private final Label message = Screens.message("accountMessage");

    AccountSettingsView(AppNavigator navigator, AuthenticatedUser user, Mode mode) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        switch (mode) {
            case NAME -> showName();
            case PHONE -> showPhone();
            case PIN -> showPin();
        }
    }

    private void showName() {
        TextField first = field("firstNameField", user.firstName());
        TextField last = field("lastNameField", user.lastName());
        Button save = Screens.primaryButton("Save Name", "saveNameButton");
        save.setOnAction(event -> submit(save, () -> {
            AuthenticatedUser updated = navigator.accountService().updateName(user.userId(), first.getText(),
                    last.getText());
            return () -> {
                navigator.accountUpdated(user, updated);
                navigator.showProfile("Your name was updated.");
            };
        }));
        show("Edit your name", "This is the name other LAMMA users see when they send you money or invite you.",
                labelled("First name", first), labelled("Last name", last), save);
    }

    private void showPhone() {
        TextField phone = field("newPhoneField", "");
        phone.setPromptText("e.g. 70 123 456 or +961 70 123 456");
        PinInput pin = new PinInput("currentPinField", "Current PIN");
        Button save = Screens.primaryButton("Change Phone Number", "savePhoneButton");
        save.setOnAction(event -> submit(save, () -> {
            AuthenticatedUser updated = navigator.accountService().changePhone(user.userId(), pin.getPin(),
                    phone.getText());
            return () -> {
                navigator.accountUpdated(user, updated);
                navigator.showProfile("Your phone number is now " + PhoneNumbers.format(updated.phoneNumber())
                        + ". Use it the next time you sign in.");
            };
        }, pin));
        Label current = Screens.hint("Current number: " + PhoneNumbers.format(user.phoneNumber()));
        current.setId("currentPhoneLabel");
        show("Change your phone number", "You sign in with your phone number, so we need your current PIN to "
                + "change it. Each number can belong to only one account.", current,
                labelled("New phone number", phone), labelled("Current PIN", pin), save);
    }

    private void showPin() {
        PinInput current = new PinInput("currentPinField", "Current PIN");
        PinInput next = new PinInput("newPinField", "New PIN");
        PinInput confirm = new PinInput("confirmPinField", "Confirm new PIN");
        current.setOnComplete(pin -> next.focusInput()); // move on to the next set of boxes
        next.setOnComplete(pin -> confirm.focusInput());
        Button save = Screens.primaryButton("Change PIN", "savePinButton");
        save.setOnAction(event -> submit(save, () -> {
            navigator.accountService().changePin(user.userId(), current.getPin(), next.getPin(),
                    confirm.getPin());
            return () -> navigator.showProfile("Your PIN was changed. Use the new PIN the next time you sign in.");
        }, current, next, confirm));
        show("Change your PIN", "Enter your current PIN, then a new 6-digit PIN twice. "
                + "After 5 incorrect PINs, changes and sign-in are blocked for 5 minutes.",
                labelled("Current PIN", current), labelled("New PIN", next), labelled("Confirm new PIN", confirm), save);
    }

    /**
     * Runs the change in the background. On success the returned action runs (on the UI thread);
     * on failure the message is shown and the PIN fields are cleared.
     */
    private void submit(Button save, Callable<Runnable> work, PinInput... pins) {
        save.setDisable(true);
        Screens.showInfo(message, "Saving…");
        BackgroundWork.run(work,
                (Runnable done) -> {
                    if (stillCurrent()) {
                        done.run();
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    Screens.showError(message, error);
                    for (PinInput p : pins) {
                        p.clear();
                        p.showError(true);
                    }
                    save.setDisable(false);
                });
    }

    private void show(String title, String hint, javafx.scene.Node... content) {
        VBox form = new VBox(12, content);
        form.setMaxWidth(340);
        form.setAlignment(Pos.CENTER_LEFT);
        Label explanation = Screens.hint(hint);
        explanation.setMaxWidth(420);
        getChildren().setAll(Screens.title(title), explanation, form, message);
        Platform.runLater(() -> {
            for (javafx.scene.Node n : content) {
                if (n instanceof VBox box && box.getChildren().size() > 1
                        && box.getChildren().get(1) instanceof TextInputControl input) {
                    input.requestFocus();
                    return;
                }
                if (n instanceof VBox box && box.getChildren().size() > 1
                        && box.getChildren().get(1) instanceof PinInput boxes) {
                    boxes.focusInput();
                    return;
                }
            }
        });
    }

    private static VBox labelled(String label, javafx.scene.Node input) {
        Label caption = Screens.caption(label);
        caption.setLabelFor(input instanceof PinInput boxes ? boxes.field() : input);
        return new VBox(4, caption, input);
    }

    private static TextField field(String id, String value) {
        TextField field = new TextField(value);
        field.setId(id);
        return field;
    }


    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
