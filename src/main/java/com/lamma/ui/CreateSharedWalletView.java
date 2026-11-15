package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.SharedWalletService.NewSharedWallet;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** New shared wallet: name (required), purpose, description and target (optional). */
final class CreateSharedWalletView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final TextField name = new TextField();
    private final TextField purpose = new TextField();
    private final TextArea description = new TextArea();
    private final TextField target = new TextField();
    private final Label message = Screens.message("createSharedWalletMessage");
    private final Button create = Screens.primaryButton("Create Shared Wallet", "createSharedWalletSubmit");

    CreateSharedWalletView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");

        name.setId("sharedNameField");
        name.setPromptText("e.g. Beirut Trip");
        purpose.setId("sharedPurposeField");
        purpose.setPromptText("Optional, e.g. Summer trip with friends");
        description.setId("sharedDescriptionField");
        description.setPromptText("Optional");
        description.setPrefRowCount(3);
        description.setWrapText(true);
        target.setId("sharedTargetField");
        target.setPromptText("Optional, e.g. 1500.00");

        VBox form = new VBox(12,
                Screens.field("Wallet name", name),
                Screens.field("Purpose", purpose),
                Screens.field("Description", description),
                Screens.field("Target amount (USD)", target));
        form.setMaxWidth(440);

        create.setOnAction(event -> submit());
        Screens.wideButton(create).setMaxWidth(440);
        Button cancel = Screens.linkButton("Cancel", "cancelCreateSharedWalletButton");
        cancel.setOnAction(event -> navigator.showWallets());

        getChildren().addAll(Screens.title("New shared wallet"),
                Screens.hint("Starts with a $0.00 balance, and you'll be its Owner."),
                form, message, create, cancel);
        Platform.runLater(name::requestFocus);
    }

    private void submit() {
        NewSharedWallet input;
        try {
            input = SharedWalletService.validate(name.getText(), purpose.getText(), description.getText(),
                    target.getText());
        } catch (ValidationException e) {
            Screens.showError(message, e.getMessage());
            return;
        }
        create.setDisable(true); // no second wallet from a double click
        Screens.showInfo(message, "Creating…");
        long userId = user.userId(); // the creator is always the signed-in user
        BackgroundWork.run(() -> navigator.sharedWalletService().create(userId, input),
                (Long walletId) -> {
                    if (stillCurrent()) {
                        navigator.showSharedWalletDetail(walletId);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        create.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
