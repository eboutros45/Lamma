package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.ExpenseService.Collection;
import com.lamma.wallet.ExpenseService.ExpenseDetails;
import com.lamma.wallet.ExpenseService.ExpenseSummary;
import com.lamma.wallet.ExpenseService.RequestRecord;
import com.lamma.wallet.ExpenseService.Share;
import com.lamma.wallet.ExpenseService.ShareState;
import com.lamma.wallet.Money;
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
 * One expense: total, how it is collected, each participant's share and status, the signed-in
 * user's own Pay / Decline, the creator's (or Owner's) Revise and Cancel, and the payment history.
 * Reloads from the database after every action.
 */
final class ExpenseDetailView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long expenseId;
    private final long walletId;
    private final Label message = Screens.message("expenseMessage");

    ExpenseDetailView(AppNavigator navigator, AuthenticatedUser user, long expenseId, long walletId, String notice) {
        this.navigator = navigator;
        this.user = user;
        this.expenseId = expenseId;
        this.walletId = walletId;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720;");
        message.managedProperty().bind(message.textProperty().isNotEmpty()); // no gap when there's no message
        message.visibleProperty().bind(message.textProperty().isNotEmpty());
        load(notice);
    }

    private void load(String notice) {
        if (getChildren().isEmpty()) {
            getChildren().setAll(Screens.hint("Loading expense…"));
        }
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.expenseService().details(userId, expenseId),
                (ExpenseDetails d) -> {
                    if (stillCurrent()) {
                        show(d);
                        if (notice != null && !notice.isEmpty()) {
                            Screens.showSuccess(message, notice);
                        }
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        getChildren().setAll(message);
                    }
                });
    }

    private void show(ExpenseDetails d) {
        ExpenseSummary s = d.summary();
        message.setText("");
        Label title = Screens.title(s.title());
        title.setId("expenseTitle");
        Label total = new Label(Money.formatUsd(s.total()));
        total.setId("expenseTotal");
        total.getStyleClass().add("balance");
        Label status = new Label(s.isCancelled() ? "Cancelled" : s.isSettled() ? "Settled" : "Open");
        status.setId("expenseStatus");
        status.getStyleClass().addAll("badge", "on-hero");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(new Label(d.walletName()), spacer, status);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox hero = new VBox(6, top, total, Screens.caption((s.split() == com.lamma.wallet.ExpenseService.SplitMethod.EQUAL
                ? "Split equally" : "Custom split") + " · created by " + s.createdByName()));
        hero.getStyleClass().add("hero-card");
        hero.setMaxWidth(Double.MAX_VALUE);

        getChildren().setAll(title, hero, collection(d), message);
        if (d.notes() != null) {
            Label notes = Screens.hint(d.notes());
            notes.setId("expenseNotes");
            getChildren().add(card(Screens.sectionTitle("Notes"), notes));
        }
        getChildren().add(shares(d));
        if (d.canManage() && !s.isCancelled() && hasUnresolved(d)) {
            getChildren().add(manageActions(d));
        }
        getChildren().add(history(d));
        Button back = Screens.linkButton("Back to wallet", "backToWalletFromExpense");
        back.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        getChildren().add(back);
    }

    /** How the money is collected, collected/outstanding, and (reimbursements) who paid outside LAMMA. */
    private VBox collection(ExpenseDetails d) {
        ExpenseSummary s = d.summary();
        VBox box = card(Screens.sectionTitle(s.collection() == Collection.SHARED_WALLET
                ? "Collected into the shared wallet" : "Reimbursing " + s.paidByName()));
        if (s.collection() == Collection.REIMBURSEMENT) {
            Label external = Screens.hint(s.paidByName() + " paid the full " + Money.formatUsd(s.total())
                    + " outside LAMMA (not a LAMMA transaction). Their own share of " + Money.formatUsd(s.covered())
                    + " is covered; the others pay them back from their Personal Wallets.");
            external.setId("externalPaymentNote");
            box.getChildren().add(external);
        } else {
            box.getChildren().add(Screens.hint("Each participant pays their share from their Personal Wallet into "
                    + "the shared wallet. Creating the expense doesn't spend the shared balance."));
        }
        BigDecimal collectible = s.total().subtract(s.covered());
        double progress = collectible.signum() == 0 ? 1
                : s.collected().divide(collectible, 4, RoundingMode.DOWN).min(BigDecimal.ONE).doubleValue();
        ProgressBar bar = new ProgressBar(progress);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label amounts = Screens.hint("Collected " + Money.formatUsd(s.collected()) + " · Outstanding "
                + Money.formatUsd(s.outstanding()) + (s.covered().signum() > 0
                        ? " · Covered by " + s.paidByName() + " " + Money.formatUsd(s.covered()) : ""));
        amounts.setId("expenseAmounts");
        box.getChildren().addAll(bar, amounts);
        return box;
    }

    private VBox shares(ExpenseDetails d) {
        VBox list = new VBox();
        list.setId("expenseShares");
        String payTo = d.summary().collection() == Collection.SHARED_WALLET
                ? "“" + d.walletName() + "”" : d.summary().paidByName();
        for (Share share : d.shares()) {
            boolean me = share.userId() == user.userId();
            Label name = new Label(share.name() + (me ? " (you)" : "") + (share.stillMember() ? "" : " · no longer a member"));
            name.getStyleClass().add("activity-type");
            name.setWrapText(true);
            name.setMinWidth(0);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label amount = new Label(Money.formatUsd(share.amount()));
            amount.setMinWidth(Region.USE_PREF_SIZE);
            Label state = new Label(label(share.state()));
            state.setId("shareState-" + share.userId());
            state.getStyleClass().add("badge");
            state.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(10, name, amount, state);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(6, row);
            box.getStyleClass().add("activity-row");
            if (me && share.state() == ShareState.PENDING && share.openRequestId() != null) {
                box.getChildren().add(PaymentActions.build(navigator, user, share.openRequestId(), share.amount(), payTo,
                        done -> {
                            if (stillCurrent()) {
                                load(done);
                            }
                        },
                        error -> {
                            if (stillCurrent()) {
                                load(null);
                                Screens.showError(message, error);
                            }
                        }));
            }
            list.getChildren().add(box);
        }
        return card(Screens.sectionTitle("Participants"), list);
    }

    private static String label(ShareState state) {
        return switch (state) {
            case PAID -> "Paid";
            case PENDING -> "Pending";
            case DECLINED -> "Declined";
            case CANCELLED -> "Cancelled";
            case COVERED -> "Covered by payer";
            case NOTHING_OWED -> "Nothing owed";
        };
    }

    private static boolean hasUnresolved(ExpenseDetails d) {
        return d.shares().stream().anyMatch(s -> (s.state() == ShareState.PENDING || s.state() == ShareState.DECLINED)
                && s.amount().signum() > 0);
    }

    /** Revise unpaid shares, or cancel the expense (with confirmation). */
    private VBox manageActions(ExpenseDetails d) {
        Button revise = new Button("Revise Unpaid Shares");
        revise.setId("reviseExpenseButton");
        revise.setOnAction(event -> navigator.showReviseExpense(expenseId, walletId));
        Button cancel = new Button("Cancel Expense");
        cancel.setId("cancelExpenseButton");
        cancel.getStyleClass().add("outline-danger-button");
        HBox buttons = new HBox(8, Screens.keepWidth(revise), Screens.keepWidth(cancel));
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox area = new VBox(8, buttons); // a confirmation question goes above its buttons
        long userId = user.userId();
        cancel.setOnAction(event -> {
            Label question = new Label("Cancel this expense? Unpaid requests are closed. Payments already made stay "
                    + "where they are.");
            question.setWrapText(true);
            Button yes = new Button("Cancel Expense");
            yes.setId("confirmCancelExpense");
            yes.getStyleClass().add("danger-button");
            Button no = Screens.linkButton("Keep it", "keepExpenseButton");
            no.setOnAction(e -> area.getChildren().setAll(buttons));
            yes.setOnAction(e -> {
                yes.setDisable(true);
                BackgroundWork.run(() -> {
                            navigator.expenseService().cancel(userId, expenseId);
                            return null;
                        },
                        ignored -> {
                            if (stillCurrent()) {
                                load("The expense was cancelled.");
                            }
                        },
                        error -> {
                            if (stillCurrent()) {
                                load(null);
                                Screens.showError(message, error);
                            }
                        });
            });
            HBox confirm = new HBox(8, Screens.keepWidth(yes), Screens.keepWidth(no));
            confirm.setAlignment(Pos.CENTER_LEFT);
            area.getChildren().setAll(question, confirm);
        });
        Label hint = Screens.hint("Declined shares are never moved to others automatically. Revise them yourself, "
                + "or cancel the expense.");
        return card(Screens.sectionTitle("Manage"), hint, area);
    }

    /** Every request, newest first: who, how much, what happened, and the transfer reference when paid. */
    private VBox history(ExpenseDetails d) {
        VBox list = new VBox();
        list.setId("expenseHistory");
        for (int i = d.requests().size() - 1; i >= 0; i--) {
            RequestRecord r = d.requests().get(i);
            String what = switch (r.status()) {
                case "PAID" -> "Paid by LAMMA transfer · Ref " + reference(r.transferId());
                case "PENDING" -> "Waiting for " + first(r.payerName());
                case "DECLINED" -> "Declined by " + first(r.payerName());
                default -> r.replacesRequestId() == null && d.summary().isCancelled() ? "Cancelled with the expense"
                        : "Replaced or cancelled";
            };
            if (r.replacesRequestId() != null) {
                what += " · revised request";
            }
            Label line = new Label(r.payerName() + " · " + Money.formatUsd(r.amount()));
            line.getStyleClass().add("activity-type");
            Label detail = Screens.caption(what + " · " + TransactionLabels.dateTime(
                    r.respondedAt() != null ? r.respondedAt() : r.createdAt()));
            detail.getStyleClass().add("hint");
            detail.setWrapText(true);
            VBox row = new VBox(2, line, detail);
            row.getStyleClass().add("activity-row");
            list.getChildren().add(row);
        }
        return card(Screens.sectionTitle("Payment history"), list);
    }

    private static String reference(String transferId) {
        return TransactionLabels.reference(transferId); // the same reference as in the wallet histories
    }

    private static String first(String name) {
        return name.split(" ")[0];
    }

    private static VBox card(javafx.scene.Node... children) {
        VBox card = new VBox(6, children);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
