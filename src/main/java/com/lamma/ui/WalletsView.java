package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.SharedWallet;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Wallets: the personal wallet, then the shared wallets the user is a member of.
 * Both lists are loaded in the background and only for the signed-in user.
 */
final class WalletsView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label message = Screens.message("walletsMessage");
    private final VBox sharedList = new VBox(10);
    private final Button newShared = Screens.linkButton("New shared wallet", "newSharedWalletButton");

    WalletsView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720; -fx-alignment: top-left;");

        Label shared = Screens.sectionTitle("Shared wallets");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Screens.withIcon(newShared, Icon.ADD);
        newShared.setOnAction(event -> navigator.showCreateSharedWallet());
        newShared.setVisible(false); // shown once the list has loaded and is not empty
        HBox sharedHeader = new HBox(shared, spacer, newShared);
        sharedHeader.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(sharedHeader, new Insets(12, 0, 0, 0));
        sharedList.setId("sharedWalletList");

        getChildren().addAll(Screens.title("Wallets"), Screens.sectionTitle("Your wallets"), personalWalletTile(),
                message, sharedHeader, sharedList);
        loadPersonal();
        loadShared();
    }

    private Button personalWalletTile() {
        Label name = new Label("Personal Wallet");
        name.getStyleClass().add("card-title");
        Label kind = Screens.caption("Personal · USD");
        kind.getStyleClass().add("hint");
        balance.setId("walletsBalanceLabel");
        balance.getStyleClass().add("card-title");
        Button tile = tile("personalWalletTile", Icon.WALLET, name, kind, balance, null);
        tile.setOnAction(event -> navigator.showWalletDetail());
        return tile;
    }

    private Button sharedWalletTile(SharedWallet.Summary w) {
        Label name = new Label(w.name());
        name.getStyleClass().add("card-title");
        String about = w.purpose() != null ? w.purpose()
                : w.description() != null ? w.description() : "Shared wallet · " + w.myRole().displayName();
        Label subtitle = Screens.caption(about);
        subtitle.getStyleClass().add("hint");
        Label amount = new Label(Money.formatUsd(w.balance()));
        amount.getStyleClass().add("card-title");
        amount.setId("sharedWalletBalance-" + w.walletId());
        Label target = w.targetAmount() == null ? null
                : Screens.caption("of " + Money.formatUsd(w.targetAmount()) + " target");
        if (target != null) {
            target.getStyleClass().add("hint");
        }
        Button tile = tile("sharedWallet-" + w.walletId(), Icon.GROUP, name, subtitle, amount, target);
        tile.setOnAction(event -> navigator.showSharedWalletDetail(w.walletId()));
        return tile;
    }

    /** A wallet row: icon, name and subtitle, amount (never truncated), chevron. */
    private static Button tile(String id, Icon icon, Label name, Label subtitle, Label amount, Label amountCaption) {
        name.setMinWidth(Region.USE_PREF_SIZE);
        amount.setMinWidth(Region.USE_PREF_SIZE);
        VBox texts = new VBox(2, name, subtitle);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        VBox money = amountCaption == null ? new VBox(amount) : new VBox(2, amount, amountCaption);
        money.setAlignment(Pos.CENTER_RIGHT);
        money.setMinWidth(Region.USE_PREF_SIZE);
        HBox content = new HBox(10, Screens.iconChip(icon, false), texts, money, Icons.icon(Icon.CHEVRON_RIGHT, 18));
        content.setAlignment(Pos.CENTER_LEFT);

        Button tile = new Button();
        tile.setId(id);
        tile.setGraphic(content);
        tile.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        tile.getStyleClass().add("wallet-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        content.prefWidthProperty().bind(tile.widthProperty().subtract(40));
        return tile;
    }

    private void loadPersonal() {
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

    private void loadShared() {
        Label loading = Screens.hint("Loading shared wallets…");
        sharedList.getChildren().setAll(loading);
        long userId = user.userId(); // only wallets this user is a member of
        BackgroundWork.run(() -> navigator.sharedWalletService().listForMember(userId),
                (List<SharedWallet.Summary> wallets) -> {
                    if (stillCurrent()) {
                        showShared(wallets);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    Label problem = Screens.message("sharedWalletsMessage");
                    Screens.showError(problem, error);
                    Button retry = new Button("Retry");
                    retry.setId("retrySharedWalletsButton");
                    retry.setOnAction(event -> loadShared());
                    sharedList.getChildren().setAll(problem, retry);
                });
    }

    private void showShared(List<SharedWallet.Summary> wallets) {
        newShared.setVisible(!wallets.isEmpty());
        if (wallets.isEmpty()) {
            sharedList.getChildren().setAll(emptyState());
            return;
        }
        sharedList.getChildren().clear();
        for (SharedWallet.Summary w : wallets) {
            sharedList.getChildren().add(sharedWalletTile(w));
        }
    }

    private Node emptyState() {
        Label text = Screens.hint("Shared wallets let a group save and keep track of money together, "
                + "for a trip, a gift or shared bills. You'll be the Owner of wallets you create.");
        text.setId("noSharedWalletsLabel");
        Button create = Screens.withIcon(Screens.primaryButton("Create Shared Wallet", "createSharedWalletButton"), Icon.ADD);
        create.setDefaultButton(false);
        create.setOnAction(event -> navigator.showCreateSharedWallet());
        VBox box = new VBox(12, Screens.iconChip(Icon.GROUP, false), text, create);
        box.getStyleClass().add("card");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
