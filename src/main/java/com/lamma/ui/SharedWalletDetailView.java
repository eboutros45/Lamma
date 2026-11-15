package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.ui.Icons.Icon;
import com.lamma.wallet.MemberRole;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWallet;
import com.lamma.wallet.SharedWalletMembershipService.JoinRequest;
import com.lamma.wallet.SharedWalletMembershipService.OutgoingInvitation;
import com.lamma.wallet.SharedWalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.Callable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
 * <p>No money moves in or out of shared wallets yet, so there are no money buttons here.
 */
final class SharedWalletDetailView extends VBox {

    /** Everything this screen shows; the Owner-only lists are empty for other members. */
    private record Data(SharedWallet.Details details, List<JoinRequest> requests, List<OutgoingInvitation> invitations) {
    }

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
                    if (details.wallet().myRole() != MemberRole.OWNER) {
                        return new Data(details, List.of(), List.of());
                    }
                    return new Data(details,
                            navigator.membershipService().pendingJoinRequests(userId, walletId),
                            navigator.membershipService().pendingInvitations(userId, walletId));
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
        boolean owner = w.myRole() == MemberRole.OWNER;
        getChildren().setAll(hero(w));

        if (w.targetAmount() != null) {
            getChildren().add(target(w));
        }
        if (w.purpose() != null || w.description() != null) {
            getChildren().add(about(w));
        }
        actionMessage.setText("");
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
        getChildren().add(members(data.details(), owner));
        if (owner && !data.invitations().isEmpty()) {
            getChildren().add(pendingInvitations(data.invitations()));
        }
        if (!owner) {
            getChildren().add(leaveArea());
        }
        // Plain text, not buttons: these actions don't exist yet.
        Label next = Screens.hint("Contributions, spending and shared expenses will be added in later updates.");
        next.setId("sharedWalletComingNext");
        getChildren().add(next);
    }

    // ---------- sections ----------

    private VBox hero(SharedWallet.Summary w) {
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
        Label role = new Label(m.role().displayName());
        role.setId("memberRole-" + m.userId());
        role.getStyleClass().add("badge");
        HBox row = new HBox(12, Screens.avatar(m.firstName(), m.lastName(), false), name, role);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, row);
        box.getStyleClass().add("activity-row");

        if (viewerIsOwner && m.role() != MemberRole.OWNER) {
            MemberRole other = m.role() == MemberRole.SPENDER ? MemberRole.CONTRIBUTOR : MemberRole.SPENDER;
            Button changeRole = new Button("Make " + other.displayName());
            changeRole.setId("changeRole-" + m.userId());
            Button remove = new Button("Remove");
            remove.setId("removeMember-" + m.userId());
            HBox actions = new HBox(8, Screens.keepWidth(changeRole), Screens.keepWidth(remove));
            actions.setAlignment(Pos.CENTER_RIGHT);
            box.getChildren().add(actions);
            long ownerId = user.userId();
            String roleQuestion = other == MemberRole.SPENDER
                    ? "Make " + m.firstName() + " a Spender? Spenders will also be able to send money out of this wallet."
                    : "Make " + m.firstName() + " a Contributor? Contributors can add money but not send it out.";
            changeRole.setOnAction(event -> confirm(actions, roleQuestion, "Make " + other.displayName(), false, () -> {
                navigator.membershipService().changeRole(ownerId, walletId, m.userId(), other);
                return m.firstName() + " is now a " + other.displayName() + ".";
            }));
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
        Node[] previous = area.getChildren().toArray(new Node[0]);
        Label text = new Label(question);
        text.setWrapText(true);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button yes = new Button(confirmText);
        yes.setId("confirmMemberAction");
        yes.getStyleClass().add(danger ? "danger-button" : "primary-button");
        Button no = Screens.linkButton("Cancel", "cancelMemberAction");
        no.setOnAction(event -> area.getChildren().setAll(previous));
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
