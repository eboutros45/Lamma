package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/** Add Money: Cash Top-Up or Credit Card Top-Up (both simulated). */
final class ReceiveView extends VBox {

    ReceiveView(AppNavigator navigator) {
        getStyleClass().add("screen");

        Button cash = Screens.optionTile(Icon.CASH, "Cash top-up",
                "Add simulated cash to your Personal Wallet", "cashTopUpButton");
        cash.setOnAction(event -> navigator.showCashTopUp());
        Button card = Screens.optionTile(Icon.CARD, "Credit card top-up",
                "Use a saved card or a new one", "cardTopUpButton");
        card.setOnAction(event -> navigator.showCardTopUp());

        // The header already says "Add Money", so no separate title here.
        getChildren().addAll(
                Screens.hint("How would you like to add money to your Personal Wallet?"), cash, card,
                Screens.hint("All funds in LAMMA are simulated. No real payment is made."));
    }
}
