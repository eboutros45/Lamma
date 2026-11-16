package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.wallet.ExpenseService.Payment;
import com.lamma.wallet.Money;
import java.math.BigDecimal;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Pay / Decline for one of the signed-in user's payment requests. Pay asks for confirmation
 * ("Pay $10.00 from your Personal Wallet to …?") before anything moves; the service re-checks
 * everything when it runs. Used on the Wallets screen and on the expense's screen.
 */
final class PaymentActions {

    private PaymentActions() {
    }

    /**
     * @param payTo     where the money goes, as the user should read it ("“Beirut Trip”", "Lina Haddad")
     * @param onDone    called with a success message after paying or declining
     * @param onError   called with the error message
     */
    static VBox build(AppNavigator navigator, AuthenticatedUser user, long requestId, BigDecimal amount, String payTo,
                      Consumer<String> onDone, Consumer<String> onError) {
        VBox area = new VBox(8); // the question goes above its buttons, so it stays readable on narrow windows
        showButtons(area, navigator, user, requestId, amount, payTo, onDone, onError);
        return area;
    }

    private static void showButtons(VBox area, AppNavigator navigator, AuthenticatedUser user, long requestId,
                                    BigDecimal amount, String payTo, Consumer<String> onDone, Consumer<String> onError) {
        Button pay = Screens.primaryButton("Pay " + Money.formatUsd(amount), "payRequest-" + requestId);
        pay.setDefaultButton(false);
        Button decline = new Button("Decline");
        decline.setId("declineRequest-" + requestId);
        long userId = user.userId();
        decline.setOnAction(event -> run(area, () -> {
            navigator.expenseService().decline(userId, requestId);
            return "You declined the request. The expense's creator can revise it.";
        }, onDone, onError, pay, decline));
        pay.setOnAction(event -> {
            Label question = new Label("Pay " + Money.formatUsd(amount) + " from your Personal Wallet to " + payTo + "?");
            question.setWrapText(true);
            Button yes = Screens.primaryButton("Confirm Payment", "confirmPayment");
            yes.setDefaultButton(false);
            Button no = Screens.linkButton("Cancel", "cancelPayment");
            no.setOnAction(e -> showButtons(area, navigator, user, requestId, amount, payTo, onDone, onError));
            yes.setOnAction(e -> run(area, () -> {
                Payment p = navigator.expenseService().pay(userId, requestId);
                return p.alreadyPaid() ? "This request was already paid; nothing more was taken."
                        : "Paid " + Money.formatUsd(p.amount()) + " to " + p.paidToName() + ". Your balance is now "
                                + Money.formatUsd(p.myBalanceAfter()) + ".";
            }, onDone, onError, yes, no));
            area.getChildren().setAll(question, row(yes, no));
        });
        area.getChildren().setAll(row(pay, decline));
    }

    private static HBox row(Button... buttons) {
        HBox row = new HBox(8);
        for (Button b : buttons) {
            row.getChildren().add(Screens.keepWidth(b));
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static void run(VBox area, java.util.concurrent.Callable<String> work, Consumer<String> onDone,
                            Consumer<String> onError, Node... disable) {
        for (Node n : disable) {
            n.setDisable(true); // a double click can't pay twice (and the service would refuse anyway)
        }
        BackgroundWork.run(work, onDone::accept, error -> {
            for (Node n : disable) {
                n.setDisable(false);
            }
            onError.accept(error);
        });
    }
}
