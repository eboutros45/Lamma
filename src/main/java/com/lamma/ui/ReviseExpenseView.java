package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.wallet.Amounts;
import com.lamma.wallet.ExpenseService.Collection;
import com.lamma.wallet.ExpenseService.ExpenseDetails;
import com.lamma.wallet.ExpenseService.Share;
import com.lamma.wallet.ExpenseService.ShareState;
import com.lamma.wallet.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The creator (or Owner) re-allocates an expense's unpaid shares, e.g. after a decline. Paid shares
 * are shown but can't be changed; the new amounts must add up to the same unpaid total, so the
 * expense stays consistent. For a reimbursement, the member who paid can absorb part of it.
 */
final class ReviseExpenseView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final long expenseId;
    private final long walletId;
    private final Map<Long, TextField> fields = new LinkedHashMap<>();
    private final Label sum = Screens.hint("");
    private final Label message = Screens.message("reviseMessage");
    private BigDecimal unpaid = BigDecimal.ZERO;

    ReviseExpenseView(AppNavigator navigator, AuthenticatedUser user, long expenseId, long walletId) {
        this.navigator = navigator;
        this.user = user;
        this.expenseId = expenseId;
        this.walletId = walletId;
        getStyleClass().add("screen");
        getChildren().setAll(Screens.hint("Loading expense…"));
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.expenseService().details(userId, expenseId),
                (ExpenseDetails d) -> {
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

    private void build(ExpenseDetails d) {
        boolean reimbursement = d.summary().collection() == Collection.REIMBURSEMENT;
        VBox rows = new VBox(8);
        VBox paid = new VBox(4);
        boolean payerListed = false;
        for (Share s : d.shares()) {
            if (s.state() == ShareState.PAID) {
                paid.getChildren().add(Screens.hint(s.name() + " · " + Money.formatUsd(s.amount()) + " · paid (can't change)"));
                continue;
            }
            boolean covered = s.state() == ShareState.COVERED;
            payerListed |= covered;
            String note = covered ? "paid the bill · their own share"
                    : !s.stillMember() ? "no longer a member · set to $0.00"
                    : s.state() == ShareState.DECLINED ? "declined" : s.state() == ShareState.PENDING ? "pending" : "nothing owed";
            rows.getChildren().add(row(s.userId(), s.name(), note, s.amount()));
        }
        if (reimbursement && !payerListed && d.paidByUserId() != null) {
            rows.getChildren().add(row(d.paidByUserId(), d.summary().paidByName(), "paid the bill · can absorb part",
                    BigDecimal.ZERO.setScale(2)));
        }
        Button save = Screens.primaryButton("Save and Send Revised Requests", "saveRevisionButton");
        save.setOnAction(event -> submit(save));
        Button cancel = Screens.linkButton("Cancel", "cancelRevisionButton");
        cancel.setOnAction(event -> navigator.showExpense(expenseId, walletId, ""));
        sum.setId("revisionSum");
        VBox card = new VBox(8, Screens.sectionTitle("Unpaid shares"), rows, sum);
        card.getStyleClass().add("card");
        card.setMaxWidth(460);
        getChildren().setAll(Screens.title("Revise “" + d.summary().title() + "”"),
                Screens.hint("Give each unpaid share a new amount ($0.00 is allowed). They must add up to the same "
                        + "unpaid total. Changed shares get a new request; the old one is closed and kept in the history."),
                card);
        if (!paid.getChildren().isEmpty()) {
            VBox paidCard = new VBox(4, Screens.sectionTitle("Already paid"), paid);
            paidCard.getStyleClass().add("card");
            paidCard.setMaxWidth(460);
            getChildren().add(paidCard);
        }
        getChildren().addAll(message, save, cancel);
        updateSum();
    }

    private HBox row(long userId, String name, String note, BigDecimal amount) {
        Label who = new Label(name);
        who.getStyleClass().add("activity-type");
        Label detail = Screens.caption(note);
        detail.getStyleClass().add("hint");
        VBox texts = new VBox(2, who, detail);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        TextField field = new TextField(amount.toPlainString());
        field.setId("reviseShare-" + userId);
        field.setPrefColumnCount(7);
        field.textProperty().addListener((obs, was, now) -> updateSum());
        fields.put(userId, field);
        unpaid = unpaid.add(amount);
        HBox row = new HBox(10, texts, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void updateSum() {
        try {
            BigDecimal total = BigDecimal.ZERO;
            for (TextField f : fields.values()) {
                total = total.add(parse(f.getText()));
            }
            sum.setText("New shares add up to " + Money.formatUsd(total) + " of " + Money.formatUsd(unpaid) + " unpaid.");
        } catch (ValidationException e) {
            sum.setText("Unpaid total to share: " + Money.formatUsd(unpaid) + ".");
        }
    }

    private static BigDecimal parse(String text) throws ValidationException {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.equals("0") || t.matches("0\\.0{1,2}") || t.equals("$0") || t.equals("$0.00")) {
            return BigDecimal.ZERO.setScale(2);
        }
        return Amounts.parseUsd(t);
    }

    private void submit(Button save) {
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        try {
            for (Map.Entry<Long, TextField> e : fields.entrySet()) {
                amounts.put(e.getKey(), parse(e.getValue().getText()));
            }
        } catch (ValidationException e) {
            Screens.showError(message, e.getMessage());
            return;
        }
        save.setDisable(true);
        Screens.showInfo(message, "Saving…");
        long userId = user.userId();
        BackgroundWork.run(() -> {
                    navigator.expenseService().revise(userId, expenseId, amounts);
                    return null;
                },
                ignored -> {
                    if (stillCurrent()) {
                        navigator.showExpense(expenseId, walletId, "Shares revised. Changed shares were sent new requests.");
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                        save.setDisable(false);
                    }
                });
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
