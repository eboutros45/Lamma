package com.lamma.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Sign Up: choose Personal (available) or Business (Coming Soon - creates nothing). */
final class SignUpChoiceView extends VBox {

    SignUpChoiceView(AppNavigator navigator) {
        getStyleClass().add("screen");

        Button personal = new Button("Personal");
        personal.setId("personalButton");
        personal.getStyleClass().add("choice-button");
        personal.setOnAction(event -> navigator.showPersonalSignUp());

        Button business = new Button("Business\nComing Soon");
        business.setId("businessButton");
        business.getStyleClass().add("choice-button");

        Label message = Screens.message("signUpChoiceMessage");
        // Business registration is not available in Alpha 1: this only shows a notice.
        business.setOnAction(event -> Screens.showInfo(message,
                "Business accounts are coming soon. For now you can create a Personal account."));

        HBox choices = new HBox(16, personal, business);
        choices.setAlignment(Pos.CENTER);

        Button back = Screens.linkButton("Already have an account? Sign In", "backToSignInButton");
        back.setOnAction(event -> navigator.showSignIn("", ""));

        getChildren().addAll(Screens.appTitle(), Screens.title("Sign Up"),
                Screens.hint("What kind of account would you like to create?"), choices, message, back);
    }
}
