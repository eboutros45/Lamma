package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.MemberRole;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWallet;
import com.lamma.wallet.SharedWalletMembershipService.JoinRequest;
import com.lamma.wallet.SharedWalletMembershipService.OutgoingInvitation;
import com.lamma.wallet.ExpenseService;
import com.lamma.wallet.GiftDeliveryService;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.Callable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One shared wallet: balance, target, about, and members.
 *
 * <p>Everyone sees the members and their roles. The Owner also gets Invite Member, the QR
 * invitation, join requests, pending invitations, role changes and removal. Other members can
 * leave. Owner tools are hidden from members, but the service checks every action against the
 * database anyway. After each action the screen reloads from the database.
 *
 * <p>Expenses (P04C): every member sees the wallet's expenses and can add one.
 *
 * <p>Money: every member can Contribute; the Owner and Spenders can also Withdraw and Send
 * Money. Buttons a member may not use are not shown, and the service re-checks the role in the
 * database for every operation. The latest activity is shown, with "View all" for the history.
 */
final class SharedWalletDetailView extends VBox {

    /** Everything this screen shows; the Owner-only lists are empty for other members. */
    private record Data(SharedWallet.Details details, List<JoinRequest> requests, List<OutgoingInvitation> invitations,
                        List<WalletTransaction> recent, List<ExpenseService.ExpenseSummary> expenses,
                        GiftDeliveryService.Delivery delivery) {
    }

    /** How many transactions the details screen shows; "View all" shows the full history. */
    static final int RECENT_LIMIT = 5;

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final Label actionMessage = Screens.message("memberActionMessage");

    SharedWalletDetailView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720;");
        load(null);
    }

    private void load(String notice) {
        if (getChildren().isEmpty()) {
            Label loading = Screens.hint("Loading shared wallet…");
            loading.setId("sharedWalletLoading");
            getChildren().setAll(loading);
        }
        long userId = user.userId(); // access is checked against the signed-in user's membership
        BackgroundWork.run(() -> {
                    SharedWallet.Details details = navigator.sharedWalletService().details(userId, walletId);
                    List<WalletTransaction> recent = navigator.fundsService().history(userId, walletId, RECENT_LIMIT);
                    List<ExpenseService.ExpenseSummary> expenses = navigator.expenseService().list(userId, walletId);
                    if (details.wallet().completed()) { // a delivered gift: read-only, no Owner lists
                        return new Data(details, List.of(), List.of(), recent, expenses,
                                navigator.giftService().deliveryFor(userId, walletId));
                    }
                    if (details.wallet().myRole() != MemberRole.OWNER) {
                        return new Data(details, List.of(), List.of(), recent, expenses, null);
                    }
                    return new Data(details,
                            navigator.membershipService().pendingJoinRequests(userId, walletId),
                            navigator.membershipService().pendingInvitations(userId, walletId), recent, expenses, null);
                },
                (Data data) -> {
                    if (stillCurrent()) {
                        show(data);
                        if (notice != null) {
                            Screens.showSuccess(actionMessage, notice);
                        }
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
                        retry.setOnAction(event -> load(null));
                        getChildren().add(retry);
                    }
                });
    }

    private void show(Data data) {
        SharedWallet.Summary w = data.details().wallet();
        actionMessage.setText("");
        if (w.completed()) {
            showCompleted(data);
            return;
        }
        boolean owner = w.myRole() == MemberRole.OWNER;
        getChildren().setAll(hero(w), moneyActions(w.myRole()));
        if (w.isGift() && owner) {
            getChildren().add(sendGiftCard(w));
        }

        if (w.targetAmount() != null) {
            getChildren().add(target(w));
        }
        if (w.purpose() != null || w.description() != null) {
            getChildren().add(about(w));
        }
        if (owner) {
            Button invite = Screens.withIcon(Screens.primaryButton("Invite Member", "inviteMemberButton"), Icon.ADD);
            invite.setDefaultButton(false);
            invite.setOnAction(event -> navigator.showInviteMember(walletId));
            Button qr = new Button("QR Invitation");
            qr.setId("shareQrButton");
            qr.getStyleClass().add("secondary-action-button");
            qr.setOnAction(event -> navigator.showQrInvite(walletId));
            HBox actions = new HBox(10, invite, qr);
            getChildren().add(actions);
        }
        getChildren().add(actionMessage);
        if (owner && !data.requests().isEmpty()) {
            getChildren().add(joinRequests(data.requests()));
        }
        getChildren().add(expenses(data.expenses(), false));
        getChildren().add(recentActivity(data.recent()));
        getChildren().add(members(data.details(), owner));
        if (owner && !data.invitations().isEmpty()) {
            getChildren().add(pendingInvitations(data.invitations()));
        }
        if (!owner) {
            getChildren().add(leaveArea());
        }

    }

    /**
     * A delivered gift: what was delivered, then everything read-only (target, about, expenses,
     * activity, members). No money, Owner or membership actions.
     */
    private void showCompleted(Data data) {
        SharedWallet.Summary w = data.details().wallet();
        getChildren().setAll(hero(w));
        GiftDeliveryService.Delivery d = data.delivery();
        Label title = Screens.sectionTitle("Gift delivered");
        Label text = Screens.hint(d == null ? "This gift has been delivered."
                : Money.formatUsd(d.amount()) + " was sent to " + d.recipientName() + "'s Personal Wallet on "
                        + TransactionLabels.dateTime(d.deliveredAt()) + " by " + d.deliveredByName()
                        + " (Ref " + TransactionLabels.reference(d.transferId()) + ").");
        text.setId("giftDeliveredText");
        Label readOnly = Screens.hint("This gift is complete. The wallet is kept read-only for its history: "
                + "no more contributions, withdrawals, payments, expenses or invitations.");
        VBox banner = card(title, text, readOnly);
        banner.setId("giftDeliveredBanner");
        banner.getStyleClass().add("completed-banner");
        getChildren().add(banner); // (no target card: the balance is $0.00 after delivery, the banner says what was sent)
        if (w.purpose() != null || w.description() != null) {
            getChildren().add(about(w));
        }
        getChildren().addAll(expenses(data.expenses(), true), recentActivity(data.recent()),
                members(data.details(), false));
    }

    /** Owner of an active gift: who it's for, and Send Gift. */
    private VBox sendGiftCard(SharedWallet.Summary w) {
        Button send = Screens.withIcon(Screens.primaryButton("Send Gift", "sendGiftButton"), Icon.SEND);
        send.setDefaultButton(false);
        send.setOnAction(event -> navigator.showSendGift(walletId));
        Label text = Screens.hint("When you're ready, send the whole balance to " + w.giftRecipientName()
                + ". The wallet then becomes a completed, read-only gift. Reaching the target isn't required.");
        VBox card = card(Screens.sectionTitle("Gift for " + w.giftRecipientName()), text, Screens.keepWidth(send));
        card.setId("sendGiftCard");
        return card;
    }

    // ---------- sections ----------

    /**
     * Contribute for everyone; Withdraw and Send Money only for the Owner and Spenders. One row
     * when there is room; on narrow windows Contribute gets its own line so no label is cut.
     */
    private javafx.scene.layout.GridPane moneyActions(MemberRole role) {
        Button contribute = Screens.withIcon(new Button("Contribute"), Icon.ADD);
        contribute.setId("contributeButton");
        contribute.getStyleClass().add("action-button");
        contribute.setOnAction(event -> navigator.showSharedTransfer(walletId, SharedTransferView.Mode.CONTRIBUTE));
        List<Button> buttons = new java.util.ArrayList<>(List.of(contribute));
        if (role.canSendOut()) {
            Button withdraw = Screens.withIcon(new Button("Withdraw"), Icon.RECEIVED);
            withdraw.setId("withdrawButton");
            withdraw.getStyleClass().add("secondary-action-button");
            withdraw.setOnAction(event -> navigator.showSharedTransfer(walletId, SharedTransferView.Mode.WITHDRAW));
            Button send = Screens.withIcon(new Button("Send Money"), Icon.SEND);
            send.setId("sharedSendButton");
            send.getStyleClass().add("secondary-action-button");
            send.setOnAction(event -> navigator.showSharedTransfer(walletId, SharedTransferView.Mode.SEND));
            buttons.add(withdraw);
            buttons.add(send);
        }
        // A grid whose cells change with the width: the buttons are never moved to another
        // parent (re-parenting icon buttons makes JavaFX log CSS warnings).
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setId("moneyActions");
        grid.setHgap(10);
        grid.setVgap(10);
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setPrefWidth(0);
            grid.add(b, i, 0);
        }
        java.util.function.Consumer<Boolean> arrange = narrow -> {
            grid.getColumnConstraints().clear();
            int columns = buttons.size() == 1 ? 1 : narrow ? 2 : 3;
            for (int c = 0; c < columns; c++) {
                javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
                cc.setPercentWidth(100.0 / columns);
                cc.setHgrow(Priority.ALWAYS);
                grid.getColumnConstraints().add(cc);
            }
            if (buttons.size() > 1) {
                javafx.scene.layout.GridPane.setConstraints(buttons.get(0), 0, 0, narrow ? 2 : 1, 1);
                javafx.scene.layout.GridPane.setConstraints(buttons.get(1), narrow ? 0 : 1, narrow ? 1 : 0);
                javafx.scene.layout.GridPane.setConstraints(buttons.get(2), narrow ? 1 : 2, narrow ? 1 : 0);
            }
            grid.setUserData(narrow);
        };
        arrange.accept(false);
        grid.widthProperty().addListener((obs, before, now) -> {
            boolean narrow = now.doubleValue() < 520;
            if (!Boolean.valueOf(narrow).equals(grid.getUserData())) {
                arrange.accept(narrow);
            }
        });
        return grid;
    }

    /** The wallet's expenses (newest first), each opening its own screen, and Add Expense. */
    private VBox expenses(List<ExpenseService.ExpenseSummary> expenses, boolean readOnly) {
        Button add = Screens.withIcon(new Button("Add Expense"), Icon.ADD);
        add.setId("addExpenseButton");
        add.getStyleClass().add("secondary-action-button");
        add.setOnAction(event -> navigator.showCreateExpense(walletId));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox title = readOnly ? new HBox(Screens.sectionTitle("Expenses"))
                : new HBox(Screens.sectionTitle("Expenses"), spacer, Screens.keepWidth(add));
        title.setAlignment(Pos.CENTER_LEFT);
        VBox list = new VBox();
        list.setId("expenseList");
        if (expenses.isEmpty()) {
            list.getChildren().add(Screens.hint(readOnly ? "No expenses."
                    : "No expenses yet. Add one to split a bill between members."));
        }
        for (ExpenseService.ExpenseSummary e : expenses) {
            Label name = new Label(e.title());
            name.getStyleClass().add("activity-type");
            String how = e.collection() == ExpenseService.Collection.SHARED_WALLET ? "Into the wallet"
                    : "Reimburse " + e.paidByName();
            String state = e.isCancelled() ? "Cancelled" : e.isSettled() ? "Settled"
                    : Money.formatUsd(e.outstanding()) + " outstanding";
            Label detail = Screens.caption(how + " · " + state);
            detail.getStyleClass().add("hint");
            VBox texts = new VBox(2, name, detail);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Label amount = new Label(Money.formatUsd(e.total()));
            amount.setMinWidth(Region.USE_PREF_SIZE);
            HBox content = new HBox(12, texts, amount);
            content.setAlignment(Pos.CENTER_LEFT);
            Button open = new Button();
            open.setId("expense-" + e.expenseId());
            open.setGraphic(content);
            open.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            open.getStyleClass().add("option-tile");
            open.setMaxWidth(Double.MAX_VALUE);
            content.prefWidthProperty().bind(open.widthProperty().subtract(34));
            open.setOnAction(event -> navigator.showExpense(e.expenseId(), walletId, ""));
            list.getChildren().add(open);
        }
        return card(title, list);
    }

    private VBox recentActivity(List<WalletTransaction> recent) {
        VBox list = new VBox();
        list.setId("sharedActivityList");
        ActivityRows.fill(list, recent, "No activity yet. Contributions, withdrawals and payments will appear here.");
        Button viewAll = Screens.linkButton("View all", "viewSharedHistoryButton");
        viewAll.setOnAction(event -> navigator.showSharedWalletHistory(walletId));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox title = new HBox(Screens.sectionTitle("Recent activity"), spacer, viewAll);
        title.setAlignment(Pos.CENTER_LEFT);
        return card(title, list);
    }

    private VBox hero(SharedWallet.Summary w) {
        Label name = new Label(w.name());
        name.setId("sharedWalletName");
        name.getStyleClass().add("card-title");
        Label role = new Label(w.completed() ? "Delivered" : "You: " + w.myRole().displayName());
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
        if (w.isGift()) {
            Label giftFor = new Label("Gift for " + w.giftRecipientName());
            giftFor.setId("giftRecipientLabel");
            giftFor.getStyleClass().addAll("badge", "on-hero");
            hero.getChildren().add(1, giftFor);
        }
        hero.getStyleClass().add("hero-card");
        hero.setMaxWidth(Double.MAX_VALUE);
        return hero;
    }

    private VBox target(SharedWallet.Summary w) {
        double progress = w.balance().divide(w.targetAmount(), 4, RoundingMode.DOWN).min(BigDecimal.ONE).doubleValue();
        ProgressBar bar = new ProgressBar(progress);
        bar.setId("targetProgress");
        bar.setMaxWidth(Double.MAX_VALUE);
        Label text = Screens.hint(Money.formatUsd(w.balance()) + " of " + Money.formatUsd(w.targetAmount())
                + " (" + Math.round(progress * 100) + "%)");
        text.setId("targetLabel");
        return card(Screens.sectionTitle("Target"), bar, text);
    }

    private VBox about(SharedWallet.Summary w) {
        VBox about = card(Screens.sectionTitle("About"));
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
        return about;
    }

    private VBox members(SharedWallet.Details details, boolean owner) {
        VBox list = new VBox();
        list.setId("membersList");
        for (SharedWallet.Member m : details.members()) {
            list.getChildren().add(memberRow(m, owner));
        }
        int n = details.members().size();
        return card(header("Members", n == 1 ? "1 member" : n + " members"), list);
    }

    private VBox memberRow(SharedWallet.Member m, boolean viewerIsOwner) {
        boolean me = m.userId() == user.userId();
        Label name = new Label(m.fullName() + (me ? " (you)" : ""));
        name.getStyleClass().add("activity-type");
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        // The role: a fixed badge, except the Owner's view of other members, which gets a dropdown.
        boolean editable = viewerIsOwner && m.role() != MemberRole.OWNER;
        Node role;
        ComboBox<MemberRole> choice = null;
        if (editable) {
            choice = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                    MemberRole.CONTRIBUTOR, MemberRole.SPENDER));
            choice.setId("roleChoice-" + m.userId());
            choice.setValue(m.role());
            choice.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(MemberRole r) {
                    return r == null ? "" : r.displayName();
                }

                @Override
                public MemberRole fromString(String text) {
                    return null; // not editable as text
                }
            });
            choice.setMinWidth(Region.USE_PREF_SIZE);
            choice.setAccessibleText("Role of " + m.fullName());
            role = choice;
        } else {
            Label badge = new Label(m.role().displayName());
            badge.setId("memberRole-" + m.userId());
            badge.getStyleClass().add("badge");
            role = badge;
        }
        HBox row = new HBox(12, Screens.avatar(m.firstName(), m.lastName(), false), name, role);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, row);
        box.getStyleClass().add("activity-row");

        if (editable) {
            Button remove = new Button("Remove");
            remove.setId("removeMember-" + m.userId());
            HBox actions = new HBox(8, Screens.keepWidth(remove));
            actions.setAlignment(Pos.CENTER_RIGHT);
            box.getChildren().add(actions);
            long ownerId = user.userId();
            ComboBox<MemberRole> roles = choice;
            roles.valueProperty().addListener((obs, before, chosen) -> {
                if (chosen == null || chosen == m.role()) {
                    return; // back to the saved role (e.g. after Cancel): nothing to confirm
                }
                String question = chosen == MemberRole.SPENDER
                        ? "Make " + m.firstName() + " a Spender? Spenders will also be able to send money out of this wallet."
                        : "Make " + m.firstName() + " a Contributor? Contributors can add money but not send it out.";
                roles.setDisable(true);
                confirm(actions, question, "Make " + chosen.displayName(), false, () -> {
                    navigator.membershipService().changeRole(ownerId, walletId, m.userId(), chosen);
                    return m.firstName() + " is now a " + chosen.displayName() + ".";
                }, () -> {
                    roles.setDisable(false);
                    roles.setValue(m.role()); // cancelled: show the saved role again
                });
            });
            remove.setOnAction(event -> confirm(actions, "Remove " + m.fullName()
                    + " from this wallet? They'll lose access right away.", "Remove", true, () -> {
                navigator.membershipService().removeMember(ownerId, walletId, m.userId());
                return m.fullName() + " was removed.";
            }));
        }
        return box;
    }

    private VBox joinRequests(List<JoinRequest> requests) {
        VBox list = new VBox();
        list.setId("joinRequestsList");
        long ownerId = user.userId();
        for (JoinRequest r : requests) {
            Label who = new Label(r.requesterName());
            who.getStyleClass().add("activity-type");
            Label phone = Screens.caption(r.requesterPhone() + " · asked with the QR invitation");
            phone.getStyleClass().add("hint");
            VBox texts = new VBox(2, who, phone);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Button approve = Screens.primaryButton("Approve", "approveRequest-" + r.requestId());
            approve.setDefaultButton(false);
            Button reject = new Button("Reject");
            reject.setId("rejectRequest-" + r.requestId());
            approve.setOnAction(event -> act(approve, () -> {
                navigator.membershipService().approveJoinRequest(ownerId, r.requestId());
                return r.requesterName() + " joined as a Contributor.";
            }));
            reject.setOnAction(event -> act(reject, () -> {
                navigator.membershipService().rejectJoinRequest(ownerId, r.requestId());
                return r.requesterName() + "'s request was rejected.";
            }));
            HBox row = new HBox(10, texts, Screens.keepWidth(approve), Screens.keepWidth(reject));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("activity-row");
            list.getChildren().add(row);
        }
        return card(header("Join requests", requests.size() + " waiting"), list);
    }

    private VBox pendingInvitations(List<OutgoingInvitation> invitations) {
        VBox list = new VBox();
        list.setId("pendingInvitationsList");
        long ownerId = user.userId();
        for (OutgoingInvitation i : invitations) {
            Label who = new Label(i.inviteeName());
            who.getStyleClass().add("activity-type");
            Label phone = Screens.caption(i.inviteePhone() + " · invitation pending");
            phone.getStyleClass().add("hint");
            VBox texts = new VBox(2, who, phone);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Button cancel = new Button("Cancel invitation");
            cancel.setId("cancelInvitation-" + i.invitationId());
            cancel.setOnAction(event -> act(cancel, () -> {
                navigator.membershipService().cancelInvitation(ownerId, i.invitationId());
                return "Invitation to " + i.inviteeName() + " cancelled.";
            }));
            HBox row = new HBox(10, texts, Screens.keepWidth(cancel));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("activity-row");
            list.getChildren().add(row);
        }
        return card(header("Pending invitations", String.valueOf(invitations.size())), list);
    }

    private VBox leaveArea() {
        Button leave = Screens.withIcon(new Button("Leave wallet"), Icon.LOGOUT);
        leave.setId("leaveWalletButton");
        leave.getStyleClass().add("outline-danger-button");
        HBox area = new HBox(leave);
        area.setAlignment(Pos.CENTER_LEFT);
        long userId = user.userId();
        leave.setOnAction(event -> confirm(area, "Leave this wallet? You'll lose access until you're invited again.",
                "Leave", true, () -> {
                    navigator.membershipService().leave(userId, walletId);
                    return null;
                }));
        return new VBox(area);
    }

    // ---------- actions ----------

    /**
     * Replaces the action buttons with a confirmation (question, confirm, Cancel). The action
     * then runs in the background; the screen reloads from the database afterwards.
     */
    private void confirm(HBox area, String question, String confirmText, boolean danger, Callable<String> action) {
        confirm(area, question, confirmText, danger, action, () -> { });
    }

    private void confirm(HBox area, String question, String confirmText, boolean danger, Callable<String> action,
                         Runnable onCancel) {
        Node[] previous = area.getChildren().toArray(new Node[0]);
        Label text = new Label(question);
        text.setWrapText(true);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button yes = new Button(confirmText);
        yes.setId("confirmMemberAction");
        yes.getStyleClass().add(danger ? "danger-button" : "primary-button");
        Button no = Screens.linkButton("Cancel", "cancelMemberAction");
        no.setOnAction(event -> {
            area.getChildren().setAll(previous);
            onCancel.run();
        });
        yes.setOnAction(event -> act(yes, action));
        area.getChildren().setAll(text, Screens.keepWidth(yes), Screens.keepWidth(no));
    }

    /** Runs a membership change; null result means "I left": go back to Wallets. */
    private void act(Button button, Callable<String> action) {
        button.setDisable(true);
        Screens.showInfo(actionMessage, "Saving…");
        BackgroundWork.run(action,
                (String done) -> {
                    if (!stillCurrent()) {
                        return;
                    }
                    if (done == null) {
                        navigator.showWallets(); // left the wallet: no access any more
                    } else {
                        load(done);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        load(null); // show the current database state, then the reason
                        Screens.showError(actionMessage, error);
                    }
                });
    }

    // ---------- small builders ----------

    private static VBox card(Node... children) {
        VBox card = new VBox(6, children);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static HBox header(String title, String count) {
        Label caption = Screens.caption(count);
        caption.getStyleClass().add("hint");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(Screens.sectionTitle(title), gap, caption);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
