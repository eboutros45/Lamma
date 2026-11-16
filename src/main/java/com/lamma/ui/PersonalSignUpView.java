package com.lamma.ui;

import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.Pins;
import com.lamma.auth.RegistrationService.Registration;
import com.lamma.auth.ValidationException;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Personal registration: first and last name, phone number, a six-digit PIN and its
 * confirmation (the same PIN boxes as Sign In). Problems are shown under the field they concern
 * before anything is sent; the service checks everything again (and that the number is free).
 * On success the user goes straight to the PIN step of Sign In.
 */
final class PersonalSignUpView extends VBox {

    private final AppNavigator navigator;
    private final TextField firstNameField = new TextField();
    private final TextField lastNameField = new TextField();
    private final TextField phoneField = new TextField();
    private final PinInput pin = new PinInput("pinField", "PIN");
    private final PinInput confirmPin = new PinInput("confirmPinField", "Confirm PIN");
    private final Label firstNameError = fieldError("firstNameError");
    private final Label lastNameError = fieldError("lastNameError");
    private final Label phoneError = fieldError("phoneError");
    private final Label pinError = fieldError("pinError");
    private final Label confirmError = fieldError("confirmPinError");
    private final Label message = Screens.message("signUpMessage");
    private final Button createButton = Screens.primaryButton("Create Account", "createAccountButton");

    PersonalSignUpView(AppNavigator navigator, String phone) {
        this.navigator = navigator;
        getStyleClass().addAll("auth-card", "auth-step");

        firstNameField.setId("firstNameField");
        lastNameField.setId("lastNameField");
        phoneField.setId("phoneField");
        phoneField.setPromptText("e.g. 03 123 456 or +961 3 123 456");
        phoneField.setText(phone);
        pin.setOnComplete(entered -> confirmPin.focusInput()); // straight on to the confirmation

        HBox names = new HBox(12, labelled("First name", firstNameField, firstNameError),
                labelled("Last name", lastNameField, lastNameError));
        names.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        VBox form = new VBox(14, names, labelled("Phone number", phoneField, phoneError),
                labelled("PIN (6 digits)", pin, pinError), labelled("Confirm PIN", confirmPin, confirmError));

        createButton.setOnAction(event -> register());
        Button back = Screens.linkButton("Already have an account? Sign In", "backToSignInButton");
        back.setOnAction(event -> navigator.showSignIn(phoneField.getText().strip(), ""));

        Screens.wideButton(createButton);
        getChildren().addAll(Screens.appTitle(), Screens.title("Create your account"),
                Screens.hint("You'll sign in with your phone number and a 6-digit PIN."),
                form, message, createButton, back);
    }

    private void register() {
        if (!checkFields()) {
            Screens.showError(message, "Please fix the highlighted fields.");
            return;
        }
        String first = firstNameField.getText();
        String last = lastNameField.getText();
        String phone = phoneField.getText();
        String enteredPin = pin.getPin();
        String confirm = confirmPin.getPin();
        createButton.setDisable(true); // one account per click
        Screens.showInfo(message, "Creating your account…");
        BackgroundWork.run(
                () -> navigator.registrationService().registerPersonal(first, last, phone, enteredPin, confirm),
                (Registration registration) -> {
                    pin.clear();
                    confirmPin.clear();
                    // Only move on if the user is still on this form (the account exists either way).
                    if (navigator.isShowing(this)) {
                        navigator.showPinEntry(registration.phoneNumber(), "Account created. Enter your new PIN to sign in.");
                    }
                },
                error -> {
                    pin.clear();
                    confirmPin.clear();
                    Screens.showError(message, error);
                    createButton.setDisable(false);
                });
    }

    /** Shows each problem under its field; true when everything looks right. */
    private boolean checkFields() {
        boolean ok = true;
        ok &= show(firstNameError, firstNameField.getText().isBlank() ? "First name is required."
                : firstNameField.getText().strip().length() > 50 ? "Use 50 characters or fewer." : null);
        ok &= show(lastNameError, lastNameField.getText().isBlank() ? "Last name is required."
                : lastNameField.getText().strip().length() > 50 ? "Use 50 characters or fewer." : null);
        String phoneProblem = null;
        try {
            PhoneNumbers.normalize(phoneField.getText());
        } catch (ValidationException e) {
            phoneProblem = e.getMessage();
        }
        ok &= show(phoneError, phoneProblem);
        ok &= show(pinError, Pins.isValidFormat(pin.getPin()) ? null : "Enter 6 digits.");
        ok &= show(confirmError, !confirmPin.isComplete() ? "Enter the same 6 digits again."
                : !confirmPin.getPin().equals(pin.getPin()) ? "The PINs don't match." : null);
        if (!ok) {
            pin.showError(!Pins.isValidFormat(pin.getPin()));
            confirmPin.showError(confirmError.isVisible());
        }
        return ok;
    }

    private static boolean show(Label error, String text) {
        boolean problem = text != null;
        error.setText(problem ? text : "");
        error.setVisible(problem);
        error.setManaged(problem);
        return !problem;
    }

    private static VBox labelled(String label, Node input, Label error) {
        Label caption = new Label(label);
        caption.getStyleClass().add("form-label");
        return new VBox(6, caption, input, error);
    }

    private static Label fieldError(String id) {
        Label error = new Label();
        error.setId(id);
        error.getStyleClass().addAll("message-error", "field-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }
}
