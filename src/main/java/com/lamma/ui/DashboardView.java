package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.Money;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletTransaction;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Home: welcome header, the personal-wallet balance, Send Money / Add Money, and the most
 * recent activity with a link to the full history.
 *
 * <p>Narrow: one column. Wide: balance and actions on the left, activity on the right.
 *
 * <p>The balance and activity are loaded from MySQL in the background. When the result arrives
 * it is only shown if this screen is still on display <em>and</em> the same sign-in is still
 * active, so a slow load can never show one user's wallet after Sign Out or an account switch.
 */
final class DashboardView extends VBox {

    /** How many recent transactions Home shows; the wallet history shows more. */
    static final int RECENT_ACTIVITY_LIMIT = 6;
    /** Two columns at this content width or wider (with a small dead band, like the shell). */
    private static final double TWO_COLUMNS_AT = 760;
    private static final double ONE_COLUMN_BELOW = 720;

    private record DashboardData(PersonalWallet wallet, List<WalletTransaction> transactions) {
    }

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label balance = new Label("—");
    private final Label walletStatus = Screens.message("walletStatus");
    private final Button retry = new Button("Retry");
    private final VBox activityList = new VBox();
    private final VBox walletColumn;
    private final VBox activityCard;
    private final HBox columns = new HBox(24);
    private Boolean twoColumns;

    DashboardView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().addAll("dashboard", "dashboard-content");

        walletColumn = new VBox(16, heroCard(), actions(), walletStatus, retry);
        activityCard = activitySection();
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().addAll(header(), columns);

        widthProperty().addListener((obs, old, width) -> arrange(width.doubleValue()));
        arrange(AppNavigator.DEFAULT_WIDTH);
        load();
    }

    private HBox header() {
        Label greeting = Screens.caption("Welcome back");
        greeting.getStyleClass().add("hint");
        Label name = new Label(user.firstName() + " " + user.lastName());
        name.setId("userNameLabel");
        name.getStyleClass().add("user-name");
        VBox texts = new VBox(0, greeting, name);

        Button signOut = new Button();
        signOut.setId("signOutButton");
        signOut.setGraphic(Icons.icon(Icon.LOGOUT, 20));
        signOut.getStyleClass().add("icon-button");
        signOut.setTooltip(new Tooltip("Sign out"));
        signOut.setOnAction(event -> navigator.signOut());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, Screens.avatar(user.firstName(), user.lastName(), false), texts, spacer, signOut);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox heroCard() {
        Label heading = new Label("Personal Wallet");
        heading.setId("walletHeading");
        heading.getStyleClass().add("card-title");
        Label currency = new Label("USD");
        currency.getStyleClass().addAll("badge", "on-hero");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(heading, spacer, currency);
        top.setAlignment(Pos.CENTER_LEFT);

        balance.setId("balanceLabel");
        balance.getStyleClass().add("balance");
        Label caption = Screens.caption("Available balance · simulated money");

        VBox card = new VBox(6, top, balance, caption);
        card.getStyleClass().add("hero-card");
        return card;
    }

    private HBox actions() {
        Button send = Screens.withIcon(new Button("Send Money"), Icon.SEND);
        send.setId("sendMoneyButton");
        send.getStyleClass().add("action-button");
        send.setMaxWidth(Double.MAX_VALUE);
        send.setOnAction(event -> navigator.showSendMoney());

        Button add = Screens.withIcon(new Button("Add Money"), Icon.ADD);
        add.setId("receiveMoneyButton");
        add.getStyleClass().add("secondary-action-button");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> navigator.showReceive());

        HBox.setHgrow(send, Priority.ALWAYS);
        HBox.setHgrow(add, Priority.ALWAYS);
        send.setPrefWidth(0);
        add.setPrefWidth(0);
        return new HBox(12, send, add);
    }

    private VBox activitySection() {
        Label heading = Screens.sectionTitle("Recent activity");
        Button viewAll = Screens.linkButton("View all", "viewAllActivityButton");
        viewAll.setOnAction(event -> navigator.showWalletDetail());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(heading, spacer, viewAll);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        activityList.setId("activityList");
        ScrollPane scroll = new ScrollPane(activityList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox section = new VBox(8, titleRow, scroll);
        section.getStyleClass().add("card");
        return section;
    }

    /** One column when narrow, two when wide. Only rebuilds when crossing a threshold. */
    private void arrange(double width) {
        boolean wide = twoColumns == null ? width >= TWO_COLUMNS_AT
                : twoColumns ? width >= ONE_COLUMN_BELOW : width >= TWO_COLUMNS_AT;
        if (twoColumns != null && wide == twoColumns) {
            return;
        }
        twoColumns = wide;
        getChildren().remove(walletColumn);
        getChildren().remove(activityCard);
        columns.getChildren().clear();
        if (wide) {
            walletColumn.setPrefWidth(360);
            walletColumn.setMinWidth(340);
            // Small preferred width: the activity card takes whatever space is left, so the
            // wallet column keeps its width and its buttons are never truncated.
            activityCard.setPrefWidth(300);
            HBox.setHgrow(activityCard, Priority.ALWAYS);
            columns.getChildren().addAll(walletColumn, activityCard);
            if (!getChildren().contains(columns)) {
                getChildren().add(columns);
            }
        } else {
            getChildren().remove(columns);
            VBox.setVgrow(activityCard, Priority.ALWAYS);
            getChildren().addAll(walletColumn, activityCard);
        }
    }

    private void load() {
        retry.setId("retryWalletButton");
        retry.setOnAction(event -> load());
        retry.setVisible(false);
        retry.setManaged(false);
        balance.setText("—");
        activityList.getChildren().clear();
        walletStatus.setManaged(true);
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
                    walletStatus.setManaged(false);
                    ActivityRows.fill(activityList, data.transactions(),
                            "No activity yet. Use Add Money to top up your wallet.");
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    walletStatus.setManaged(true);
                    Screens.showError(walletStatus, error);
                    retry.setVisible(true);
                    retry.setManaged(true);
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
