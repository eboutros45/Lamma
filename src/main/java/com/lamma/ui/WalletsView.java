package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Wallets: the user's wallets as a list of tiles. Today that is the personal wallet only;
 * shared wallets will be added to the same list later. Nothing here pretends shared wallets
 * already exist: the note below the list is plain text, not a button.
 */
final class WalletsView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label message = Screens.message("walletsMessage");

    WalletsView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720; -fx-alignment: top-left;");

        Label yours = Screens.sectionTitle("Your wallets");
        Label shared = Screens.sectionTitle("Shared wallets");
        VBox.setMargin(shared, new javafx.geometry.Insets(12, 0, 0, 0));
        Label sharedNote = Screens.hint("Shared wallets you create or join will be listed here in a future release.");
        sharedNote.setId("sharedWalletsNote");

        getChildren().addAll(Screens.title("Wallets"), yours, personalWalletTile(), message, shared, sharedNote);
        load();
    }

    private Button personalWalletTile() {
        Label name = new Label("Personal Wallet");
        name.getStyleClass().add("card-title");
        Label kind = Screens.caption("Personal · USD");
        kind.getStyleClass().add("hint");
        VBox texts = new VBox(2, name, kind);

        balance.setId("walletsBalanceLabel");
        balance.getStyleClass().add("card-title");
        // Money and the wallet name are never cut off; the description line takes any squeeze.
        balance.setMinWidth(Region.USE_PREF_SIZE);
        name.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox content = new HBox(10, Screens.iconChip(Icon.WALLET, false), texts, spacer, balance,
                Icons.icon(Icon.CHEVRON_RIGHT, 18));
        content.setAlignment(Pos.CENTER_LEFT);

        Button tile = new Button();
        tile.setId("personalWalletTile");
        tile.setGraphic(content);
        tile.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        tile.getStyleClass().add("wallet-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        content.prefWidthProperty().bind(tile.widthProperty().subtract(40));
        tile.setOnAction(event -> navigator.showWalletDetail());
        return tile;
    }

    private void load() {
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> navigator.walletService().loadPersonalWallet(userId),
                (PersonalWallet wallet) -> {
                    if (stillCurrent()) {
                        balance.setText(Money.formatUsd(wallet.balance()));
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
