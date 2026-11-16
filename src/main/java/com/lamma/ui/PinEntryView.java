package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Sign In, step 2 of 2: the six-digit PIN for a phone number that has an account. The PIN is
 * submitted as soon as the sixth digit is entered (one request at a time). A wrong PIN clears the
 * boxes and says how many attempts are left; after five the account is paused for five minutes
 * (enforced by {@link com.lamma.auth.AuthenticationService}, not by this screen).
 *
 * <p>Forgot PIN explains honestly that LAMMA can't reset a PIN: it has no verified SMS or email
 * channel to prove the account is yours, and a reset without one would let anyone who knows the
 * number take the account over.
 */
final class PinEntryView extends VBox {

    static final String FORGOT_PIN_TEXT = "LAMMA can't reset a forgotten PIN yet. A safe reset needs a code sent "
            + "to you by SMS or email to prove the account is yours, and LAMMA doesn't have that service. "
            + "If you're still signed in on another computer, you can change your PIN there in Profile → Account. "
            + "Otherwise, try again later or create an account with a different phone number.";

    private final AppNavigator navigator;
    private final String phone;
    private final PinInput pin = new PinInput("pinField", "PIN");
    private final Label message = Screens.message("pinMessage");
    private final VBox forgotPanel = new VBox(8);
    private boolean busy;

    /** @param phone the normalized number found in step 1 */
    PinEntryView(AppNavigator navigator, String phone, String notice) {
        this.navigator = navigator;
        this.phone = phone;
        getStyleClass().addAll("auth-card", "auth-step");

        Label who = Screens.hint("Signing in as " + masked(phone));
        who.setId("maskedPhoneLabel");
        who.setAlignment(Pos.CENTER);
        who.setMaxWidth(Double.MAX_VALUE);
        pin.setOnComplete(this::submit);
        if (!notice.isEmpty()) {
            Screens.showSuccess(message, notice);
        }

        Label forgotText = Screens.hint(FORGOT_PIN_TEXT);
        forgotText.setId("forgotPinText");
        forgotPanel.getChildren().add(forgotText);
        forgotPanel.getStyleClass().add("card");
        forgotPanel.setId("forgotPinPanel");
        forgotPanel.setVisible(false);
        forgotPanel.setManaged(false);

        Button forgot = Screens.linkButton("Forgot PIN?", "forgotPinButton");
        forgot.setOnAction(event -> {
            boolean show = !forgotPanel.isVisible();
            forgotPanel.setVisible(show);
            forgotPanel.setManaged(show);
            pin.focusInput();
        });
        Button different = Screens.linkButton("Use a different number", "differentNumberButton");
        different.setOnAction(event -> {
            pin.clear();
            navigator.showSignIn(PhoneNumbers.format(phone), "");
        });
        HBox links = new HBox(16, forgot, different);
        links.setAlignment(Pos.CENTER);

        getChildren().addAll(Screens.appTitle(), Screens.title("Enter your PIN"), who, pin, message, forgotPanel, links);
        setAlignment(Pos.TOP_CENTER);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Platform.runLater(pin::focusInput);
            }
        });
    }

    private void submit(String enteredPin) {
        if (busy) {
            return; // one sign-in request at a time
        }
        busy = true;
        pin.setInputDisabled(true);
        Screens.showInfo(message, "Signing in…");
        BackgroundWork.run(() -> navigator.authenticationService().signIn(phone, enteredPin),
                (AuthenticatedUser user) -> {
                    busy = false;
                    pin.clear();
                    // Ignore a late result once the user has left this screen (e.g. used another number).
                    if (navigator.isShowing(this)) {
                        navigator.signedIn(user);
                    }
                },
                error -> {
                    busy = false;
                    pin.clear(); // never leave a rejected PIN in the boxes
                    pin.setInputDisabled(false);
                    if (navigator.isShowing(this)) {
                        Screens.showError(message, error);
                        pin.showError(true);
                        pin.focusInput();
                    }
                });
    }

    /** "+961 70 123 456" → "+961 •• ••• 456": the country code and last group stay visible. */
    static String masked(String normalizedPhone) {
        String[] groups = PhoneNumbers.format(normalizedPhone).split(" ");
        StringBuilder out = new StringBuilder(groups[0]);
        for (int i = 1; i < groups.length; i++) {
            out.append(' ').append(i == groups.length - 1 ? groups[i] : "•".repeat(groups[i].length()));
        }
        return out.toString();
    }
}
