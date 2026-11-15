package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Personal wallet dashboard for the signed-in user.
 *
 * <p>The balance is loaded from MySQL in the background. When the result arrives it is only
 * shown if this screen is still on display <em>and</em> the same sign-in is still active,
 * so a slow load can never show one user's wallet after Sign Out or an account switch.
 */
final class DashboardView extends BorderPane {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label walletStatus = Screens.message("walletStatus");
    private final Button retry = new Button("Retry");

    DashboardView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("dashboard");

        setTop(topBar());
        VBox content = new VBox(20, walletCard(), comingSoonSection());
        content.getStyleClass().add("dashboard-content");
        setCenter(content);

        loadWallet();
    }

    private HBox topBar() {
        Label brand = new Label("LAMMA");
        brand.getStyleClass().add("brand");

        Label name = new Label(user.firstName() + " " + user.lastName());
        name.setId("userNameLabel");
        name.getStyleClass().add("user-name");

        Button signOut = new Button("Sign Out");
        signOut.setId("signOutButton");
        signOut.setOnAction(event -> navigator.signOut());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, brand, spacer, name, signOut);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private VBox walletCard() {
        Label heading = new Label("Personal Wallet");
        heading.setId("walletHeading");
        heading.getStyleClass().add("card-title");

        balance.setId("balanceLabel");
        balance.getStyleClass().add("balance");

        Label caption = Screens.hint("Available balance (simulated USD)");

        retry.setId("retryWalletButton");
        retry.setOnAction(event -> loadWallet());

        VBox card = new VBox(6, heading, balance, caption, walletStatus, retry);
        card.getStyleClass().add("card");
        return card;
    }

    /** Read-only previews: plain labels, not buttons, so nothing looks clickable. */
    private VBox comingSoonSection() {
        HBox tiles = new HBox(12,
                previewTile("Send Money", "Send funds to another LAMMA user."),
                previewTile("Receive Money", "Share your number to get paid."),
                previewTile("Recent Activity", "Your transactions will appear here."));
        Label heading = new Label("Coming soon");
        heading.getStyleClass().add("section-title");
        return new VBox(10, heading, tiles);
    }

    private static VBox previewTile(String title, String description) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("preview-title");
        Label badge = new Label("Coming Soon");
        badge.getStyleClass().add("badge");
        Label text = Screens.hint(description);
        VBox tile = new VBox(6, titleLabel, badge, text);
        tile.getStyleClass().add("preview-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tile, Priority.ALWAYS);
        return tile;
    }

    private void loadWallet() {
        retry.setVisible(false);
        retry.setManaged(false);
        balance.setText("—");
        Screens.showInfo(walletStatus, "Loading your wallet…");
        long userId = user.userId(); // from the session, never from user input
        BackgroundWork.run(() -> navigator.walletService().loadPersonalWallet(userId),
                (PersonalWallet wallet) -> {
                    if (!stillCurrent()) {
                        return; // signed out or switched account while loading: discard
                    }
                    balance.setText(Money.formatUsd(wallet.balance()));
                    walletStatus.setText("");
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    Screens.showError(walletStatus, error);
                    retry.setVisible(true);
                    retry.setManaged(true);
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
