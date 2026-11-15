package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletTransaction;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Personal wallet dashboard for the signed-in user: balance, Receive Money, and recent activity.
 *
 * <p>The balance and activity are loaded from MySQL in the background. When the result arrives
 * it is only shown if this screen is still on display <em>and</em> the same sign-in is still
 * active, so a slow load can never show one user's wallet after Sign Out or an account switch.
 */
final class DashboardView extends BorderPane {

    /** How many recent transactions the dashboard shows. */
    static final int RECENT_ACTIVITY_LIMIT = 10;

    private record DashboardData(PersonalWallet wallet, List<WalletTransaction> transactions) {
    }

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label walletStatus = Screens.message("walletStatus");
    private final Button retry = new Button("Retry");
    private final VBox activityList = new VBox(6);

    DashboardView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("dashboard");

        setTop(topBar());
        VBox content = new VBox(16, walletCard(), actions(), recentActivity());
        content.getStyleClass().add("dashboard-content");
        setCenter(content);

        load();
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
        retry.setOnAction(event -> load());

        VBox card = new VBox(4, heading, balance, caption, walletStatus, retry);
        card.getStyleClass().add("card");
        return card;
    }

    /** Receive Money and Payment Methods work; Send Money is a read-only preview (a label, not a button). */
    private HBox actions() {
        Button receive = new Button("Receive Money");
        receive.setId("receiveMoneyButton");
        receive.getStyleClass().add("action-button");
        receive.setMaxWidth(Double.MAX_VALUE);
        receive.setOnAction(event -> navigator.showReceive());
        HBox.setHgrow(receive, Priority.ALWAYS);

        Label sendTitle = new Label("Send Money");
        sendTitle.getStyleClass().add("preview-title");
        Label badge = new Label("Coming Soon");
        badge.getStyleClass().add("badge");
        VBox send = new VBox(2, sendTitle, badge);
        send.setId("sendMoneyPreview");
        send.setAlignment(Pos.CENTER);
        send.getStyleClass().add("preview-tile");
        send.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(send, Priority.ALWAYS);

        Button cards = new Button("Payment Methods");
        cards.setId("paymentMethodsButton");
        cards.getStyleClass().add("secondary-action-button");
        cards.setMaxWidth(Double.MAX_VALUE);
        cards.setOnAction(event -> navigator.showPaymentMethods(""));
        HBox.setHgrow(cards, Priority.ALWAYS);

        // Equal widths: each action gets a third of the row instead of its natural text width.
        for (Region action : new Region[] {receive, cards, send}) {
            action.setPrefWidth(0);
            action.setMinHeight(44);
        }
        HBox row = new HBox(10, receive, cards, send);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private VBox recentActivity() {
        Label heading = new Label("Recent Activity");
        heading.getStyleClass().add("section-title");
        activityList.setId("activityList");
        ScrollPane scroll = new ScrollPane(activityList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox section = new VBox(8, heading, scroll);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    private void load() {
        retry.setVisible(false);
        retry.setManaged(false);
        balance.setText("—");
        activityList.getChildren().clear();
        Screens.showInfo(walletStatus, "Loading your wallet…");
        long userId = user.userId(); // from the session, never from user input
        BackgroundWork.run(() -> new DashboardData(
                        navigator.walletService().loadPersonalWallet(userId),
                        navigator.walletService().loadRecentTransactions(userId, RECENT_ACTIVITY_LIMIT)),
                (DashboardData data) -> {
                    if (!stillCurrent()) {
                        return; // signed out or switched account while loading: discard
                    }
                    balance.setText(Money.formatUsd(data.wallet().balance()));
                    walletStatus.setText("");
                    showActivity(data.transactions());
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

    private void showActivity(List<WalletTransaction> transactions) {
        activityList.getChildren().clear();
        if (transactions.isEmpty()) {
            Label empty = Screens.hint("No activity yet. Use Receive Money to add funds.");
            empty.setId("noActivityLabel");
            activityList.getChildren().add(empty);
            return;
        }
        for (WalletTransaction t : transactions) {
            activityList.getChildren().add(activityRow(t));
        }
    }

    private static HBox activityRow(WalletTransaction t) {
        Label type = new Label(TransactionLabels.type(t));
        type.getStyleClass().add("activity-type");
        Label when = Screens.hint(TransactionLabels.details(t));
        VBox left = new VBox(2, type, when);

        Label amount = new Label(TransactionLabels.signedAmount(t));
        amount.getStyleClass().add(t.isCredit() ? "amount-credit" : "amount-debit");
        Label status = Screens.hint(TransactionLabels.status(t));
        VBox right = new VBox(2, amount, status);
        right.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(left, spacer, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("activity-row");
        return row;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
