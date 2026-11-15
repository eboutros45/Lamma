package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWalletFundsService.Overview;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.WalletTransaction;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A shared wallet's full transaction history, newest first, for any current member. Each
 * logical operation appears once: the shared wallet's own side of the transfer.
 */
final class SharedWalletHistoryView extends VBox {

    /** How many transactions the history loads (same as the personal wallet history). */
    static final int HISTORY_LIMIT = WalletDetailView.HISTORY_LIMIT;

    private record Detail(Overview overview, List<WalletTransaction> transactions) {
    }

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final Label heading = new Label("Shared wallet");
    private final Label balance = new Label("—");
    private final Label status = Screens.message("sharedHistoryStatus");
    private final Button retry = new Button("Retry");
    private final VBox history = new VBox();

    SharedWalletHistoryView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("dashboard-content");
        setMaxWidth(760);

        heading.setId("sharedHistoryWalletName");
        heading.getStyleClass().add("card-title");
        balance.setId("sharedHistoryBalance");
        balance.getStyleClass().add("balance");
        VBox hero = new VBox(6, heading, balance, Screens.caption("Shared wallet balance · USD · simulated money"));
        hero.getStyleClass().add("hero-card");

        history.setId("sharedHistoryList");
        ScrollPane scroll = new ScrollPane(history);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox historyCard = new VBox(8, Screens.sectionTitle("Transaction history"),
                Screens.hint("Money in (+) and out (−) of this shared wallet. Every member sees the same history."),
                status, retry, scroll);
        historyCard.getStyleClass().add("card");
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        retry.setId("retrySharedHistoryButton");
        retry.setOnAction(event -> load());
        getChildren().addAll(hero, historyCard);
        load();
    }

    private void load() {
        retry.setVisible(false);
        retry.setManaged(false);
        status.setManaged(true);
        Screens.showInfo(status, "Loading…");
        long userId = user.userId(); // membership is checked for the signed-in user
        BackgroundWork.run(() -> new Detail(navigator.fundsService().overview(userId, walletId),
                        navigator.fundsService().history(userId, walletId, HISTORY_LIMIT)),
                (Detail d) -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    heading.setText(d.overview().walletName());
                    balance.setText(Money.formatUsd(d.overview().sharedBalance()));
                    status.setText("");
                    status.setManaged(false);
                    ActivityRows.fill(history, d.transactions(),
                            "No transactions yet. Contributions, withdrawals and payments will appear here.");
                },
                error -> {
                    if (stillCurrent()) {
                        history.getChildren().clear();
                        Screens.showError(status, error);
                        boolean noAccess = SharedWalletService.NOT_AVAILABLE_MESSAGE.equals(error);
                        retry.setVisible(!noAccess);
                        retry.setManaged(!noAccess);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
