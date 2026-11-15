package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWallet;
import com.lamma.wallet.SharedWalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One shared wallet: name, balance, your role, target progress, purpose and description,
 * and the members with their roles. Read-only in this phase: there are deliberately no
 * contribution, transfer, invitation or expense buttons.
 */
final class SharedWalletDetailView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;

    SharedWalletDetailView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720;");
        load();
    }

    private void load() {
        Label loading = Screens.hint("Loading shared wallet…");
        loading.setId("sharedWalletLoading");
        getChildren().setAll(loading);
        long userId = user.userId(); // access is checked against the signed-in user's membership
        BackgroundWork.run(() -> navigator.sharedWalletService().details(userId, walletId),
                (SharedWallet.Details details) -> {
                    if (stillCurrent()) {
                        show(details);
                    }
                },
                error -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    Label problem = Screens.message("sharedWalletMessage");
                    Screens.showError(problem, error);
                    getChildren().setAll(problem);
                    if (!SharedWalletService.NOT_AVAILABLE_MESSAGE.equals(error)) {
                        Button retry = new Button("Retry");
                        retry.setId("retrySharedWalletButton");
                        retry.setOnAction(event -> load());
                        getChildren().add(retry);
                    }
                });
    }

    private void show(SharedWallet.Details details) {
        SharedWallet.Summary w = details.wallet();

        Label name = new Label(w.name());
        name.setId("sharedWalletName");
        name.getStyleClass().add("card-title");
        Label role = new Label("You: " + w.myRole().displayName());
        role.setId("myRoleBadge");
        role.getStyleClass().addAll("badge", "on-hero");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(name, spacer, role);
        top.setAlignment(Pos.CENTER_LEFT);
        Label balance = new Label(Money.formatUsd(w.balance()));
        balance.setId("sharedWalletBalance");
        balance.getStyleClass().add("balance");
        VBox hero = new VBox(6, top, balance, Screens.caption("Shared wallet balance · USD · simulated money"));
        hero.getStyleClass().add("hero-card");
        hero.setMaxWidth(Double.MAX_VALUE);
        getChildren().setAll(hero);

        if (w.targetAmount() != null) {
            double progress = w.balance().divide(w.targetAmount(), 4, RoundingMode.DOWN).min(BigDecimal.ONE).doubleValue();
            ProgressBar bar = new ProgressBar(progress);
            bar.setId("targetProgress");
            bar.setMaxWidth(Double.MAX_VALUE);
            Label text = Screens.hint(Money.formatUsd(w.balance()) + " of " + Money.formatUsd(w.targetAmount())
                    + " (" + Math.round(progress * 100) + "%)");
            text.setId("targetLabel");
            VBox target = new VBox(8, Screens.sectionTitle("Target"), bar, text);
            target.getStyleClass().add("card");
            target.setMaxWidth(Double.MAX_VALUE);
            getChildren().add(target);
        }

        if (w.purpose() != null || w.description() != null) {
            VBox about = new VBox(6, Screens.sectionTitle("About"));
            if (w.purpose() != null) {
                Label purpose = new Label(w.purpose());
                purpose.setId("sharedWalletPurpose");
                purpose.getStyleClass().add("card-title");
                purpose.setWrapText(true);
                about.getChildren().add(purpose);
            }
            if (w.description() != null) {
                Label description = Screens.hint(w.description());
                description.setId("sharedWalletDescription");
                about.getChildren().add(description);
            }
            about.getStyleClass().add("card");
            about.setMaxWidth(Double.MAX_VALUE);
            getChildren().add(about);
        }

        VBox members = new VBox();
        members.setId("membersList");
        for (SharedWallet.Member m : details.members()) {
            members.getChildren().add(memberRow(m));
        }
        Label count = Screens.caption(details.members().size() == 1 ? "1 member" : details.members().size() + " members");
        count.getStyleClass().add("hint");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox membersHeader = new HBox(Screens.sectionTitle("Members"), gap, count);
        membersHeader.setAlignment(Pos.CENTER_LEFT);
        VBox membersCard = new VBox(4, membersHeader, members);
        membersCard.getStyleClass().add("card");
        membersCard.setMaxWidth(Double.MAX_VALUE);

        // Plain text, not buttons: none of these actions exist yet.
        Label next = Screens.hint("Inviting members, contributions and shared expenses will be added in later updates.");
        next.setId("sharedWalletComingNext");
        getChildren().addAll(membersCard, next);
        AppShell.fadeIn(this);
    }

    private HBox memberRow(SharedWallet.Member m) {
        Label name = new Label(m.fullName() + (m.userId() == user.userId() ? " (you)" : ""));
        name.getStyleClass().add("activity-type");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label role = new Label(m.role().displayName());
        role.setId("memberRole-" + m.userId());
        role.getStyleClass().add("badge");
        HBox row = new HBox(12, Screens.avatar(m.firstName(), m.lastName(), false), name, spacer, role);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("activity-row");
        return row;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
