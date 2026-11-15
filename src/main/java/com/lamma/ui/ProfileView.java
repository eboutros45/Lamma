package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.ui.Icons.Icon;
import com.lamma.ui.ThemeSettings.Mode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Profile: account details, appearance (System / Light / Dark) and Sign Out. */
final class ProfileView extends VBox {

    ProfileView(AppNavigator navigator, AuthenticatedUser user) {
        getStyleClass().add("screen");

        Label name = new Label(user.firstName() + " " + user.lastName());
        name.setId("profileNameLabel");
        name.getStyleClass().add("screen-title");
        Label phone = Screens.hint(PhoneNumbers.format(user.phoneNumber()));
        phone.setId("profilePhoneLabel");
        phone.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE); // centred under the name
        Label kind = new Label("Personal account");
        kind.getStyleClass().add("badge");
        VBox identity = new VBox(8, Screens.avatar(user.firstName(), user.lastName(), true), name, phone, kind);
        identity.setAlignment(Pos.CENTER);

        // Appearance: a three-way segmented choice. Changing it applies immediately and is saved.
        ThemeSettings theme = navigator.themeSettings();
        ToggleGroup group = new ToggleGroup();
        HBox segments = new HBox();
        segments.getStyleClass().add("segmented");
        segments.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        for (Mode mode : Mode.values()) {
            String label = switch (mode) {
                case SYSTEM -> "System";
                case LIGHT -> "Light";
                case DARK -> "Dark";
            };
            ToggleButton option = new ToggleButton(label);
            option.setId("theme" + label);
            option.setToggleGroup(group);
            option.setSelected(theme.mode() == mode);
            option.setOnAction(event -> {
                option.setSelected(true); // one option is always selected
                theme.setMode(mode);
            });
            segments.getChildren().add(option);
        }
        VBox appearance = new VBox(10, Screens.sectionTitle("Appearance"), segments,
                Screens.hint("System follows your computer's light or dark setting. "
                        + "Your choice is saved on this computer."));
        appearance.getStyleClass().add("card");

        VBox about = new VBox(6, Screens.sectionTitle("About"),
                Screens.hint("LAMMA Alpha 2. All money in LAMMA is simulated; no real payments are made."));
        about.getStyleClass().add("card");

        Button signOut = Screens.withIcon(new Button("Sign Out"), Icon.LOGOUT);
        signOut.setId("profileSignOutButton");
        signOut.getStyleClass().add("outline-danger-button");
        signOut.setMaxWidth(Double.MAX_VALUE);
        signOut.setOnAction(event -> navigator.signOut());

        for (VBox card : new VBox[] {appearance, about}) {
            card.setMaxWidth(Double.MAX_VALUE);
        }
        getChildren().addAll(identity, appearance, about, signOut);
    }
}
