package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Sign Up: choose Personal (available) or Business (Coming Soon - creates nothing). */
final class SignUpChoiceView extends VBox {

    SignUpChoiceView(AppNavigator navigator) {
        getStyleClass().add("auth-card");

        Button personal = Screens.optionTile(Icon.PERSON, "Personal",
                "A wallet for yourself", "personalButton");
        personal.getStyleClass().add("choice-button");
        personal.setOnAction(event -> navigator.showPersonalSignUp());

        Button business = Screens.optionTile(Icon.BUSINESS, "Business · Coming soon",
                "Business accounts are not available yet", "businessButton");
        business.getStyleClass().add("choice-button");

        Label message = Screens.message("signUpChoiceMessage");
        // Business registration is not available in this version: this only shows a notice.
        business.setOnAction(event -> Screens.showInfo(message,
                "Business accounts are coming soon. For now you can create a Personal account."));

        Button back = Screens.linkButton("Already have an account? Sign In", "backToSignInButton");
        back.setOnAction(event -> navigator.showSignIn("", ""));

        getChildren().addAll(Screens.appTitle(), Screens.title("Create your account"),
                Screens.hint("What kind of account would you like to create?"), personal, business, message, back);
    }
}
