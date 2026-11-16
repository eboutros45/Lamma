package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.ExpenseService;
import com.lamma.wallet.ExpenseService.Collection;
import com.lamma.wallet.ExpenseService.NewExpense;
import com.lamma.wallet.ExpenseService.SplitMethod;
import com.lamma.wallet.Money;
import com.lamma.wallet.SharedWallet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * New shared expense: title, total, notes, participants (current members), Equal or Custom split,
 * and how it is collected (into the shared wallet, or reimbursing the member who paid). Shows each
 * participant's share before creating. Creating only sends payment requests; nobody is charged.
 */
final class CreateExpenseView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long walletId;
    private final TextField title = new TextField();
    private final TextField total = new TextField();
    private final TextArea notes = new TextArea();
    private final Map<Long, CheckBox> participants = new LinkedHashMap<>();
    private final Map<Long, TextField> customAmounts = new LinkedHashMap<>();
    private final Map<Long, String> names = new LinkedHashMap<>();
    private final RadioButton equal = new RadioButton("Equally");
    private final RadioButton custom = new RadioButton("Custom amounts");
    private final RadioButton intoWallet = new RadioButton("Into the shared wallet");
    private final RadioButton reimburse = new RadioButton("Reimburse the member who paid");
    private final ComboBox<Long> payer = new ComboBox<>();
    private final Label preview = Screens.hint("");
    private final Label message = Screens.message("createExpenseMessage");
    private final Button create = Screens.primaryButton("Create Expense", "createExpenseButton");
    private final VBox customBox = new VBox(6);

    CreateExpenseView(AppNavigator navigator, AuthenticatedUser user, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.walletId = walletId;
        getStyleClass().add("screen");
        getChildren().setAll(Screens.hint("Loading members…"));
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.sharedWalletService().details(userId, walletId),
                (SharedWallet.Details d) -> {
                    if (stillCurrent()) {
                        build(d);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        getChildren().setAll(message);
                    }
                });
    }

    private void build(SharedWallet.Details d) {
        title.setId("expenseTitleField");
        title.setPromptText("e.g. Hotel");
        total.setId("expenseTotalField");
        total.setPromptText("e.g. 90.00");
        notes.setId("expenseNotesField");
        notes.setPromptText("Optional");
        notes.setPrefRowCount(2);
        notes.setWrapText(true);

        VBox people = new VBox(6);
        for (SharedWallet.Member m : d.members()) {
            names.put(m.userId(), m.fullName() + (m.userId() == user.userId() ? " (you)" : ""));
            CheckBox box = new CheckBox(names.get(m.userId()));
            box.setId("participant-" + m.userId());
            box.setSelected(true);
            box.selectedProperty().addListener((obs, was, now) -> refresh());
            participants.put(m.userId(), box);
            people.getChildren().add(box);
            TextField amount = new TextField();
            amount.setId("customShare-" + m.userId());
            amount.setPromptText("0.00");
            amount.setPrefColumnCount(7);
            amount.textProperty().addListener((obs, was, now) -> updatePreview()); // keep focus while typing
            customAmounts.put(m.userId(), amount);
        }

        ToggleGroup split = new ToggleGroup();
        equal.setId("splitEqual");
        custom.setId("splitCustom");
        equal.setToggleGroup(split);
        custom.setToggleGroup(split);
        equal.setSelected(true);
        split.selectedToggleProperty().addListener((obs, was, now) -> refresh());

        ToggleGroup collection = new ToggleGroup();
        intoWallet.setId("collectIntoWallet");
        reimburse.setId("collectReimburse");
        intoWallet.setToggleGroup(collection);
        reimburse.setToggleGroup(collection);
        intoWallet.setSelected(true);
        payer.setId("payerChoice");
        payer.getItems().setAll(names.keySet());
        payer.setConverter(new StringConverter<>() {
            @Override
            public String toString(Long id) {
                return id == null ? "" : names.get(id);
            }

            @Override
            public Long fromString(String text) {
                return null;
            }
        });
        payer.setPromptText("Who paid the bill?");
        VBox payerBox = new VBox(4, Screens.hint("They paid outside LAMMA; their own share counts as covered, "
                + "and the others pay them back."), payer);
        collection.selectedToggleProperty().addListener((obs, was, now) -> {
            payerBox.setVisible(reimburse.isSelected());
            payerBox.setManaged(reimburse.isSelected());
            refresh();
        });
        payerBox.setVisible(false);
        payerBox.setManaged(false);
        payer.valueProperty().addListener((obs, was, now) -> updatePreview());
        total.textProperty().addListener((obs, was, now) -> updatePreview());
        preview.setId("splitPreview");

        VBox form = new VBox(12,
                Screens.field("Title", title),
                Screens.field("Total (USD)", total),
                Screens.field("Notes", notes),
                section("Who shares it", people),
                section("Split", new VBox(6, equal, custom, customBox)),
                section("How it's collected", new VBox(6, intoWallet, reimburse, payerBox)),
                preview);
        form.setMaxWidth(460);
        create.setOnAction(event -> submit());
        Screens.wideButton(create).setMaxWidth(460);
        Button cancel = Screens.linkButton("Cancel", "cancelCreateExpense");
        cancel.setOnAction(event -> navigator.showSharedWalletDetail(walletId));
        getChildren().setAll(Screens.title("New expense"),
                Screens.hint("Each participant gets a request for their share and pays or declines it themselves. "
                        + "Nobody is charged automatically."),
                form, message, create, cancel);
        refresh();
    }

    private static VBox section(String heading, javafx.scene.Node content) {
        Label label = Screens.caption(heading);
        return new VBox(6, label, content);
    }

    private List<Long> chosen() {
        List<Long> ids = new ArrayList<>();
        participants.forEach((id, box) -> {
            if (box.isSelected()) {
                ids.add(id);
            }
        });
        return ids;
    }

    /** Rebuilds the custom amount fields for the chosen participants, then the preview. */
    private void refresh() {
        customBox.getChildren().clear();
        if (custom.isSelected()) {
            for (long id : chosen()) {
                Label who = new Label(names.get(id));
                who.setMinWidth(0);
                HBox.setHgrow(who, Priority.ALWAYS);
                HBox row = new HBox(8, who, customAmounts.get(id));
                row.setAlignment(Pos.CENTER_LEFT);
                customBox.getChildren().add(row);
            }
        }
        updatePreview();
    }

    /** Everyone's share as it would be created (blank until the inputs are valid). */
    private void updatePreview() {
        try {
            NewExpense input = input();
            Map<Long, BigDecimal> shares = input.split() == SplitMethod.EQUAL
                    ? ExpenseService.equalSplit(input.total(), input.participants()) : input.customShares();
            StringBuilder text = new StringBuilder();
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
                sum = sum.add(e.getValue());
                boolean covered = input.collection() == Collection.REIMBURSEMENT && e.getKey().equals(input.paidByUserId());
                text.append(names.get(e.getKey())).append(": ").append(Money.formatUsd(e.getValue()))
                        .append(covered ? " (covered, paid the bill)" : "").append('\n');
            }
            if (input.split() == SplitMethod.CUSTOM) {
                text.append("Shares add up to ").append(Money.formatUsd(sum)).append(" of ")
                        .append(Money.formatUsd(input.total()));
            }
            preview.setText(text.toString().strip());
        } catch (ValidationException e) {
            preview.setText("");
        }
    }

    private NewExpense input() throws ValidationException {
        BigDecimal amount = Amounts.parseUsd(total.getText());
        Map<Long, BigDecimal> shares = null;
        if (custom.isSelected()) {
            shares = new LinkedHashMap<>();
            for (long id : chosen()) {
                shares.put(id, Amounts.parseUsd(customAmounts.get(id).getText()));
            }
        }
        return new NewExpense(title.getText(), amount, notes.getText(), chosen(),
                custom.isSelected() ? SplitMethod.CUSTOM : SplitMethod.EQUAL, shares,
                reimburse.isSelected() ? Collection.REIMBURSEMENT : Collection.SHARED_WALLET,
                reimburse.isSelected() ? payer.getValue() : null);
    }

    private void submit() {
        NewExpense input;
        try {
            input = input();
        } catch (ValidationException e) {
            Screens.showError(message, (title.getText().isBlank() ? "Expense title is required. " : "") + e.getMessage());
            return;
        }
        create.setDisable(true); // one expense per click
        Screens.showInfo(message, "Creating…");
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.expenseService().create(userId, walletId, input),
                (Long id) -> {
                    if (stillCurrent()) {
                        navigator.showExpense(id, walletId, "Expense created. Each participant was asked for their share.");
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
