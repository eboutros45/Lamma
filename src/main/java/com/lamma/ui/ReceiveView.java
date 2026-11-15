package com.lamma.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Receive Money: Cash Top-Up (available) or Credit Card Top-Up (Coming Soon, not clickable). */
final class ReceiveView extends VBox {

    ReceiveView(AppNavigator navigator) {
        getStyleClass().add("screen");

        Button cash = new Button("Cash Top-Up");
        cash.setId("cashTopUpButton");
        cash.getStyleClass().add("choice-button");
        cash.setOnAction(event -> navigator.showCashTopUp());

        Label card = new Label("Credit Card Top-Up\nComing Soon");
        card.setId("cardTopUpPreview");
        card.getStyleClass().add("choice-unavailable");

        HBox choices = new HBox(16, cash, card);
        choices.setAlignment(Pos.CENTER);

        Button back = Screens.linkButton("Back to Dashboard", "backToDashboardButton");
        back.setOnAction(event -> navigator.showDashboard());

        getChildren().addAll(Screens.appTitle(), Screens.title("Receive Money"),
                Screens.hint("How would you like to add money to your Personal Wallet?"), choices,
                Screens.hint("All funds in LAMMA are simulated. No real payment is made."), back);
    }
}
