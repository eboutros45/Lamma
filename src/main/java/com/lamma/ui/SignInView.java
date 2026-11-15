package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Common Sign In screen for every account type: phone number + masked six-digit PIN. */
final class SignInView extends VBox {

    private final AppNavigator navigator;
    private final TextField phoneField = new TextField();
    private final PasswordField pinField = new PasswordField();
    private final Label message = Screens.message("signInMessage");
    private final Button signInButton = Screens.primaryButton("Sign In", "signInButton");

    SignInView(AppNavigator navigator, String phone, String notice) {
        this.navigator = navigator;
        getStyleClass().add("auth-card");

        phoneField.setId("phoneField");
        phoneField.setPromptText("e.g. 03 123 456 or +961 3 123 456");
        phoneField.setText(phone);
        pinField.setId("pinField");
        pinField.setPromptText("6-digit PIN");

        VBox form = new VBox(12, Screens.field("Phone number", phoneField), Screens.field("PIN", pinField));

        if (!notice.isEmpty()) {
            Screens.showSuccess(message, notice);
        }

        signInButton.setOnAction(event -> signIn());
        Button signUp = Screens.linkButton("Don't have an account? Sign Up", "goToSignUpButton");
        signUp.setOnAction(event -> navigator.showSignUpChoice());

        Screens.wideButton(signInButton);
        getChildren().addAll(Screens.appTitle(), Screens.title("Welcome back"),
                Screens.hint("Sign in with your phone number and 6-digit PIN."), form, message, signInButton, signUp);

        // After registration the phone is pre-filled, so start typing in the PIN field.
        TextField firstField = phone.isEmpty() ? phoneField : pinField;
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(firstField::requestFocus);
            }
        });
    }

    private void signIn() {
        String phone = phoneField.getText();
        String pin = pinField.getText();
        pinField.clear();
        signInButton.setDisable(true);
        message.setText("Signing in…");
        BackgroundWork.run(() -> navigator.authenticationService().signIn(phone, pin),
                (AuthenticatedUser user) -> {
                    // Ignore a late result if the user has left this screen meanwhile; otherwise a
                    // slow sign-in could replace the session of someone who signed in since.
                    if (navigator.isShowing(this)) {
                        navigator.signedIn(user);
                    }
                },
                error -> {
                    Screens.showError(message, error);
                    signInButton.setDisable(false);
                });
    }
}
