package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.InvitationCodes;
import java.util.concurrent.Callable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Owner's QR invitation: show the code, copy it, replace it, or turn it off. Opening the
 * invitation only lets someone ask to join; the Owner approves every request.
 */
final class QrInviteView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final Label message = Screens.message("qrMessage");

    QrInviteView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");
        run(() -> navigator.membershipService().currentInviteCode(user.userId(), walletId), null);
    }

    /** Runs an action in the background and shows the resulting code (null = turned off). */
    private void run(Callable<String> action, String doneMessage) {
        getChildren().setAll(Screens.title("QR invitation"), Screens.hint("Loading…"));
        BackgroundWork.run(action,
                (String code) -> {
                    if (stillCurrent()) {
                        show(code);
                        if (doneMessage != null) {
                            Screens.showSuccess(message, doneMessage);
                        }
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        getChildren().setAll(Screens.title("QR invitation"), message);
                        Screens.showError(message, error);
                    }
                });
    }

    private void show(String code) {
        long ownerId = user.userId();
        Button back = Screens.linkButton("Back to wallet", "backToWalletButton");
        back.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        if (code == null) {
            Button enable = Screens.primaryButton("Turn on QR invitations", "enableQrButton");
            enable.setOnAction(event -> run(() -> navigator.membershipService().newInviteCode(ownerId, walletId),
                    "QR invitations are on."));
            getChildren().setAll(Screens.title("QR invitation"),
                    Screens.hint("QR invitations are off. Turn them on to get a code that people can open to ask to join."),
                    message, enable, back);
            return;
        }
        String payload = InvitationCodes.payload(code);
        ImageView qr = new ImageView();
        qr.setId("qrImage");
        try {
            qr.setImage(QrCodes.image(payload, 220));
        } catch (Exception e) {
            Screens.showError(message, "The QR code could not be drawn. The code below still works.");
        }
        StackPane qrFrame = new StackPane(qr);
        qrFrame.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-padding: 12;");
        qrFrame.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        TextField text = new TextField(payload);
        text.setId("inviteCodeText");
        text.setEditable(false);
        text.setPrefColumnCount(30); // show the whole link
        text.setMaxWidth(420);
        Button copy = new Button("Copy");
        copy.setId("copyInviteCodeButton");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(payload);
            Clipboard.getSystemClipboard().setContent(content);
            Screens.showSuccess(message, "Invitation copied. Share it with the person you want to add.");
        });
        HBox codeRow = new HBox(8, text, copy);
        codeRow.setAlignment(Pos.CENTER);

        Button regenerate = new Button("New code");
        regenerate.setId("regenerateQrButton");
        Button turnOff = new Button("Turn off");
        turnOff.setId("turnOffQrButton");
        HBox manage = new HBox(10, regenerate, turnOff);
        manage.setAlignment(Pos.CENTER);
        regenerate.setOnAction(event -> confirm(manage, "Create a new code? The current code will stop working.",
                "Create new code", () -> run(() -> navigator.membershipService().newInviteCode(ownerId, walletId),
                        "New code created. The old one no longer works.")));
        turnOff.setOnAction(event -> confirm(manage, "Turn off QR invitations? The current code will stop working.",
                "Turn off", () -> run(() -> {
                    navigator.membershipService().turnOffInviteCode(ownerId, walletId);
                    return null;
                }, "QR invitations are off.")));

        getChildren().setAll(Screens.title("QR invitation"),
                Screens.hint("Anyone who opens this invitation in LAMMA can ask to join. You approve each request, "
                        + "and approved people join as Contributors."),
                qrFrame, codeRow, message, manage, back);
    }

    /** Replaces the buttons with a confirmation: [action] [Cancel]. */
    private void confirm(HBox area, String question, String actionText, Runnable action) {
        javafx.scene.Node[] previous = area.getChildren().toArray(new javafx.scene.Node[0]);
        Label text = new Label(question);
        text.setWrapText(true);
        text.setMaxWidth(320);
        Button yes = new Button(actionText);
        yes.setId("confirmQrAction");
        yes.getStyleClass().add("danger-button");
        yes.setOnAction(event -> action.run());
        Button no = Screens.linkButton("Cancel", "cancelQrAction");
        no.setOnAction(event -> area.getChildren().setAll(previous));
        area.getChildren().setAll(text, Screens.keepWidth(yes), Screens.keepWidth(no));
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
