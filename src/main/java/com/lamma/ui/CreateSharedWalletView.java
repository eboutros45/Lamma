package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.SharedWalletService.NewSharedWallet;
import javafx.application.Platform;
import com.lamma.auth.PhoneNumbers;
import com.lamma.wallet.TransferService.Recipient;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * New shared wallet: name (required), purpose, description and target (optional), and an
 * optional Gift Wallet switch. With the switch on, a registered LAMMA user must be chosen as the
 * gift recipient (by phone number, with contact suggestions). Off, the form is as before.
 */
final class CreateSharedWalletView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final TextField name = new TextField();
    private final TextField purpose = new TextField();
    private final TextArea description = new TextArea();
    private final TextField target = new TextField();
    private final Label message = Screens.message("createSharedWalletMessage");
    private final Button create = Screens.primaryButton("Create Shared Wallet", "createSharedWalletSubmit");
    private final CheckBox gift = new CheckBox("This is a gift wallet");
    private final TextField giftPhone = new TextField();
    private final Label giftMessage = Screens.message("giftRecipientMessage");
    private final Label giftChosen = new Label();
    /** The recipient found for the number currently in the field, or null. */
    private Recipient giftRecipient;

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
        form.getChildren().add(giftSection());

        create.setOnAction(event -> submit());
        Screens.wideButton(create).setMaxWidth(440);
        Button cancel = Screens.linkButton("Cancel", "cancelCreateSharedWalletButton");
        cancel.setOnAction(event -> navigator.showWallets());

        getChildren().addAll(Screens.title("New shared wallet"),
                Screens.hint("Starts with a $0.00 balance, and you'll be its Owner."),
                form, message, create, cancel);
        Platform.runLater(name::requestFocus);
    }

    /** The switch, and (when on) the recipient's phone number, Find, and who was found. */
    private VBox giftSection() {
        gift.setId("giftToggle");
        giftPhone.setId("giftRecipientPhoneField");
        giftPhone.setPromptText("Recipient's phone number");
        Button find = new Button("Find");
        find.setId("findGiftRecipientButton");
        giftChosen.setId("giftRecipientName");
        giftChosen.getStyleClass().add("card-title");
        HBox lookup = new HBox(8, giftPhone, Screens.keepWidth(find));
        HBox.setHgrow(giftPhone, Priority.ALWAYS);
        VBox details = new VBox(6, Screens.hint("Choose the registered LAMMA user this wallet is a gift for. "
                + "It's shown to members only. It doesn't make them a member or send them anything."),
                lookup, ContactSuggestions.attach(giftPhone, navigator, user), giftMessage, giftChosen);
        details.setId("giftRecipientSection");
        Runnable showDetails = () -> {
            details.setVisible(gift.isSelected());
            details.setManaged(gift.isSelected());
        };
        showDetails.run();
        gift.selectedProperty().addListener((obs, was, on) -> showDetails.run());
        giftPhone.textProperty().addListener((obs, before, now) -> {
            if (giftRecipient != null) { // a different number: look it up again
                giftRecipient = null;
                giftChosen.setText("");
            }
        });
        find.setOnAction(event -> {
            String asked = giftPhone.getText();
            find.setDisable(true);
            Screens.showInfo(giftMessage, "Looking up…");
            long userId = user.userId();
            BackgroundWork.run(() -> navigator.sharedWalletService().findGiftRecipient(userId, asked),
                    (Recipient r) -> {
                        find.setDisable(false);
                        if (!stillCurrent() || !asked.equals(giftPhone.getText())) {
                            return;
                        }
                        giftRecipient = r;
                        giftMessage.setText("");
                        giftChosen.setText("Gift for " + r.fullName() + " · " + PhoneNumbers.format(r.phoneNumber()));
                    },
                    error -> {
                        find.setDisable(false);
                        if (stillCurrent() && asked.equals(giftPhone.getText())) {
                            Screens.showError(giftMessage, error);
                        }
                    });
        });
        return new VBox(8, gift, details);
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
        if (gift.isSelected()) {
            if (giftRecipient == null) {
                Screens.showError(message, "Choose the gift recipient: enter their phone number and press Find.");
                return;
            }
            input = input.asGiftFor(giftRecipient.userId());
        }
        NewSharedWallet confirmed = input;
        create.setDisable(true); // no second wallet from a double click
        Screens.showInfo(message, "Creating…");
        long userId = user.userId(); // the creator is always the signed-in user
        BackgroundWork.run(() -> navigator.sharedWalletService().create(userId, confirmed),
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
