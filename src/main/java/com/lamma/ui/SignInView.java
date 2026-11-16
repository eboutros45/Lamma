package com.lamma.ui;

import com.lamma.auth.AuthenticationService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Sign In, step 1 of 2: the phone number only. Continue checks that an account uses this number
 * (the service validates the format and limits lookups), then opens the PIN screen. An unknown
 * number gets a clear message with Create Account (keeping the number) or Try Again; it is never
 * sent to sign-up automatically.
 */
final class SignInView extends VBox {

    private final AppNavigator navigator;
    private final TextField phoneField = new TextField();
    private final Label message = Screens.message("signInMessage");
    private final Button continueButton = Screens.primaryButton("Continue", "continueButton");
    private final HBox unknownActions = new HBox(10);

    SignInView(AppNavigator navigator, String phone, String notice) {
        this.navigator = navigator;
        getStyleClass().addAll("auth-card", "auth-step");

        phoneField.setId("phoneField");
        phoneField.setPromptText("e.g. 03 123 456 or +961 3 123 456");
        phoneField.setText(phone);
        phoneField.setAccessibleText("Phone number");
        Label countryHint = Screens.caption("Lebanese numbers can be typed as 03 123 456; other countries start with + and the country code.");
        countryHint.getStyleClass().add("hint");
        countryHint.setWrapText(true);
        VBox field = new VBox(6, Screens.field("Phone number", phoneField), countryHint);

        if (!notice.isEmpty()) {
            Screens.showSuccess(message, notice);
        }

        // Shown only after "no account uses this number".
        Button create = Screens.primaryButton("Create Account", "createAccountForNumberButton");
        create.setDefaultButton(false);
        create.setOnAction(event -> navigator.showSignUpChoice(phoneField.getText().strip()));
        Button tryAgain = new Button("Try Again");
        tryAgain.setId("tryAgainButton");
        tryAgain.setOnAction(event -> {
            showUnknownActions(false);
            message.setText("");
            phoneField.requestFocus();
            phoneField.selectAll();
        });
        unknownActions.getChildren().addAll(Screens.keepWidth(create), Screens.keepWidth(tryAgain));
        unknownActions.setAlignment(Pos.CENTER);
        unknownActions.setId("unknownNumberActions");
        showUnknownActions(false);
        phoneField.textProperty().addListener((obs, before, now) -> {
            if (unknownActions.isVisible()) {
                showUnknownActions(false);
                message.setText("");
            }
        });

        continueButton.setOnAction(event -> lookUp());
        Screens.wideButton(continueButton);
        Button signUp = Screens.linkButton("New to LAMMA? Create Account", "goToSignUpButton");
        signUp.setOnAction(event -> navigator.showSignUpChoice(phoneField.getText().strip()));

        getChildren().addAll(Screens.appTitle(), Screens.title("Welcome back"),
                Screens.hint("Enter your phone number to sign in. You'll enter your PIN next."),
                field, message, unknownActions, continueButton, signUp);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(() -> {
                    phoneField.requestFocus();
                    phoneField.positionCaret(phoneField.getLength());
                });
            }
        });
    }

    private void lookUp() {
        String phone = phoneField.getText();
        continueButton.setDisable(true); // one lookup at a time
        phoneField.setDisable(true);
        showUnknownActions(false);
        Screens.showInfo(message, "Checking…");
        BackgroundWork.run(() -> navigator.authenticationService().findAccount(phone),
                (String normalized) -> {
                    if (navigator.isShowing(this)) {
                        navigator.showPinEntry(normalized, "");
                    }
                },
                error -> {
                    continueButton.setDisable(false);
                    phoneField.setDisable(false);
                    if (!navigator.isShowing(this)) {
                        return;
                    }
                    Screens.showError(message, error);
                    showUnknownActions(AuthenticationService.NO_ACCOUNT_MESSAGE.equals(error));
                    phoneField.requestFocus();
                });
    }

    private void showUnknownActions(boolean show) {
        unknownActions.setVisible(show);
        unknownActions.setManaged(show);
        continueButton.setVisible(!show);
        continueButton.setManaged(!show);
    }
}
