package com.lamma.ui;

import com.lamma.auth.RegistrationService.Registration;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Personal registration form. On success, the user is sent to Sign In. */
final class PersonalSignUpView extends VBox {

    private final AppNavigator navigator;
    private final TextField firstNameField = new TextField();
    private final TextField lastNameField = new TextField();
    private final TextField phoneField = new TextField();
    private final PasswordField pinField = new PasswordField();
    private final PasswordField confirmPinField = new PasswordField();
    private final Label message = Screens.message("signUpMessage");
    private final Button createButton = Screens.primaryButton("Create Account", "createAccountButton");

    PersonalSignUpView(AppNavigator navigator) {
        this.navigator = navigator;
        getStyleClass().add("auth-card");

        firstNameField.setId("firstNameField");
        lastNameField.setId("lastNameField");
        phoneField.setId("phoneField");
        phoneField.setPromptText("e.g. 03 123 456 or +961 3 123 456");
        pinField.setId("pinField");
        pinField.setPromptText("6 digits");
        confirmPinField.setId("confirmPinField");
        confirmPinField.setPromptText("6 digits");

        HBox names = new HBox(12, Screens.field("First name", firstNameField), Screens.field("Last name", lastNameField));
        HBox pins = new HBox(12, Screens.field("PIN", pinField), Screens.field("Confirm PIN", confirmPinField));
        for (HBox row : new HBox[] {names, pins}) {
            row.getChildren().forEach(n -> HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS));
        }
        VBox form = new VBox(12, names, Screens.field("Phone number", phoneField), pins);

        createButton.setOnAction(event -> register());
        Button back = Screens.linkButton("Back to Sign In", "backToSignInButton");
        back.setOnAction(event -> navigator.showSignIn("", ""));

        Screens.wideButton(createButton);
        getChildren().addAll(Screens.appTitle(), Screens.title("Personal account"),
                Screens.hint("Your PIN is 6 digits. You'll use your phone number and PIN to sign in."),
                form, message, createButton, back);
    }

    private void register() {
        String first = firstNameField.getText();
        String last = lastNameField.getText();
        String phone = phoneField.getText();
        String pin = pinField.getText();
        String confirm = confirmPinField.getText();
        createButton.setDisable(true);
        message.setText("Creating your account…");
        BackgroundWork.run(
                () -> navigator.registrationService().registerPersonal(first, last, phone, pin, confirm),
                (Registration registration) -> {
                    // Only move on if the user is still on this form (the account exists either way).
                    if (navigator.isShowing(this)) {
                        navigator.showSignIn(phone.strip(), "Account created. Sign in with your phone number and PIN.");
                    }
                },
                error -> {
                    pinField.clear();
                    confirmPinField.clear();
                    Screens.showError(message, error);
                    createButton.setDisable(false);
                });
    }
}
