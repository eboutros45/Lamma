package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Minimal signed-in confirmation with Sign Out. Placeholder only: the personal wallet
 * dashboard is built in P03.
 */
final class SignedInView extends VBox {

    SignedInView(AppNavigator navigator, AuthenticatedUser user) {
        getStyleClass().add("screen");

        Label welcome = Screens.title("Welcome, " + user.firstName() + " " + user.lastName());
        welcome.setId("welcomeLabel");
        Label phone = Screens.hint("Signed in with +" + user.phoneNumber());
        Label placeholder = Screens.hint("You are signed in. Your wallet dashboard is coming in the next update.");

        Button signOut = Screens.primaryButton("Sign Out", "signOutButton");
        signOut.setOnAction(event -> navigator.signOut());

        getChildren().addAll(Screens.appTitle(), welcome, phone, placeholder, signOut);
    }
}
