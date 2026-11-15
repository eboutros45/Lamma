package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Owner invites a registered LAMMA user by phone number (they must accept). */
final class InviteMemberView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final TextField phone = new TextField();
    private final Label message = Screens.message("inviteMessage");
    private final Button send = Screens.primaryButton("Send Invitation", "sendInvitationButton");

    InviteMemberView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");

        phone.setId("invitePhoneField");
        phone.setPromptText("e.g. 70 123 456 or +961 70 123 456");
        VBox field = Screens.field("Phone number", phone);
        field.setMaxWidth(420);
        send.setOnAction(event -> send());
        Screens.wideButton(send).setMaxWidth(420);
        Button done = Screens.linkButton("Back to wallet", "backToWalletButton");
        done.setOnAction(event -> navigator.showSharedWalletDetail(walletId));

        getChildren().addAll(Screens.title("Invite a member"),
                Screens.hint("They need a LAMMA account. They'll see the invitation under Wallets and join as a "
                        + "Contributor if they accept."),
                field, message, send, done);
        Platform.runLater(phone::requestFocus);
    }

    private void send() {
        String number = phone.getText();
        send.setDisable(true);
        Screens.showInfo(message, "Sending…");
        long ownerId = user.userId(); // permission is checked by the service, not the screen
        BackgroundWork.run(() -> navigator.membershipService().inviteByPhone(ownerId, walletId, number),
                (String inviteeName) -> {
                    if (stillCurrent()) {
                        Screens.showSuccess(message, "Invitation sent to " + inviteeName + ".");
                        phone.clear();
                        send.setDisable(false);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        send.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
