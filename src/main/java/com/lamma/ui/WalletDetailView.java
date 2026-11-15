package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletTransaction;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** One wallet: balance, actions and its transaction history (newest first). */
final class WalletDetailView extends VBox {

    /** How many transactions the history loads. */
    static final int HISTORY_LIMIT = 100;

    private record Detail(PersonalWallet wallet, List<WalletTransaction> transactions) {
    }

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label status = Screens.message("historyStatus");
    private final Button retry = new Button("Retry");
    private final VBox history = new VBox();

    WalletDetailView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("dashboard-content");
        setMaxWidth(760);

        Label heading = new Label("Personal Wallet");
        heading.getStyleClass().add("card-title");
        balance.setId("detailBalanceLabel");
        balance.getStyleClass().add("balance");
        VBox hero = new VBox(6, heading, balance, Screens.caption("Available balance · USD · simulated money"));
        hero.getStyleClass().add("hero-card");

        Button send = Screens.withIcon(new Button("Send Money"), Icon.SEND);
        send.setId("detailSendButton");
        send.getStyleClass().add("action-button");
        send.setOnAction(event -> navigator.showSendMoney());
        Button add = Screens.withIcon(new Button("Add Money"), Icon.ADD);
        add.setId("detailAddButton");
        add.getStyleClass().add("secondary-action-button");
        add.setOnAction(event -> navigator.showReceive());
        for (Button b : new Button[] {send, add}) {
            b.setMaxWidth(Double.MAX_VALUE);
            b.setPrefWidth(0);
            HBox.setHgrow(b, Priority.ALWAYS);
        }

        history.setId("historyList");
        ScrollPane scroll = new ScrollPane(history);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox historyCard = new VBox(8, Screens.sectionTitle("Transaction history"), status, retry, scroll);
        historyCard.getStyleClass().add("card");
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        retry.setId("retryHistoryButton");
        retry.setOnAction(event -> load());
        getChildren().addAll(hero, new HBox(12, send, add), historyCard);
        load();
    }

    private void load() {
        retry.setVisible(false);
        retry.setManaged(false);
        status.setManaged(true);
        Screens.showInfo(status, "Loading…");
        long userId = user.userId(); // from the session
        BackgroundWork.run(() -> new Detail(navigator.walletService().loadPersonalWallet(userId),
                        navigator.walletService().loadRecentTransactions(userId, HISTORY_LIMIT)),
                (Detail d) -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    balance.setText(Money.formatUsd(d.wallet().balance()));
                    status.setText("");
                    status.setManaged(false);
                    ActivityRows.fill(history, d.transactions(), "No transactions yet.");
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(status, error);
                        retry.setVisible(true);
                        retry.setManaged(true);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
