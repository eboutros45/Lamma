package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.SharedWalletMembershipService.InvitationPreview;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Open a QR invitation by typing or pasting its code (camera scanning comes later):
 * check the code -> see which wallet -> Request to Join. The Owner must approve.
 */
final class JoinWalletView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final TextField code = new TextField();
    private final Label message = Screens.message("joinMessage");
    private final VBox preview = new VBox(6);

    JoinWalletView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");

        code.setId("inviteCodeField");
        code.setPromptText("lamma://join/… or the 24-character code");
        VBox field = Screens.field("Invitation code or link", code);
        field.setMaxWidth(440);
        Button check = Screens.primaryButton("Check invitation", "checkInviteCodeButton");
        check.setOnAction(event -> check(check));
        // Editing the code hides any earlier result.
        code.textProperty().addListener((obs, old, now) -> {
            preview.getChildren().clear();
            preview.getStyleClass().remove("card");
        });
        preview.setMaxWidth(440);

        getChildren().addAll(Screens.title("Join a shared wallet"),
                Screens.hint("Paste the invitation the Owner shared with you. Joining always needs the Owner's approval."),
                field, check, message, preview);
        Platform.runLater(code::requestFocus);
    }

    private void check(Button check) {
        String text = code.getText();
        check.setDisable(true);
        Screens.showInfo(message, "Checking…");
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.membershipService().previewInvitation(userId, text),
                (InvitationPreview p) -> {
                    check.setDisable(false);
                    if (!stillCurrent() || !text.equals(code.getText())) {
                        return;
                    }
                    message.setText("");
                    Label name = new Label(p.walletName());
                    name.setId("invitePreviewWallet");
                    name.getStyleClass().add("card-title");
                    Button request = Screens.primaryButton("Request to Join", "requestToJoinButton");
                    request.setOnAction(event -> request(request, text));
                    preview.getChildren().setAll(Screens.hint("Shared wallet"), name,
                            Screens.hint("Owner: " + p.ownerName()), request);
                    preview.getStyleClass().add("card");
                },
                error -> {
                    check.setDisable(false);
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                    }
                });
    }

    private void request(Button request, String text) {
        request.setDisable(true); // one request per click
        Screens.showInfo(message, "Sending request…");
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.membershipService().requestToJoin(userId, text),
                (String walletName) -> {
                    if (stillCurrent()) {
                        preview.getChildren().clear();
                        preview.getStyleClass().remove("card");
                        Screens.showSuccess(message, "Request sent. You'll see “" + walletName
                                + "” under Wallets once the Owner approves it.");
                        code.clear();
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        request.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
