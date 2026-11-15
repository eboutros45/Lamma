package com.lamma.ui;

import com.lamma.auth.RegistrationService.Registration;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
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
        getStyleClass().add("screen");

        firstNameField.setId("firstNameField");
        lastNameField.setId("lastNameField");
        phoneField.setId("phoneField");
        phoneField.setPromptText("e.g. 03 123 456 or +961 3 123 456");
        pinField.setId("pinField");
        pinField.setPromptText("6 digits");
        confirmPinField.setId("confirmPinField");
        confirmPinField.setPromptText("6 digits");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setAlignment(Pos.CENTER);
        form.addRow(0, new Label("First name"), firstNameField);
        form.addRow(1, new Label("Last name"), lastNameField);
        form.addRow(2, new Label("Phone number"), phoneField);
        form.addRow(3, new Label("PIN"), pinField);
        form.addRow(4, new Label("Confirm PIN"), confirmPinField);

        createButton.setOnAction(event -> register());
        Button back = Screens.linkButton("Back to Sign In", "backToSignInButton");
        back.setOnAction(event -> navigator.showSignIn("", ""));

        getChildren().addAll(Screens.appTitle(), Screens.title("Personal Sign Up"), form, message, createButton, back);
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
                (Registration registration) -> navigator.showSignIn(phone.strip(),
                        "Account created. Sign in with your phone number and PIN."),
                error -> {
                    pinField.clear();
                    confirmPinField.clear();
                    Screens.showError(message, error);
                    createButton.setDisable(false);
                });
    }
}
