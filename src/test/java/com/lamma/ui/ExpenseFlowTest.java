package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.cards.PaymentCardService;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.ExpenseService;
import com.lamma.wallet.MemberRole;
import com.lamma.wallet.SharedWalletFundsService;
import com.lamma.wallet.SharedWalletMembershipService;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Shared expenses through the real screens and real MySQL, with three members (P04C): a pooled
 * expense paid from the Wallets screen and from the expense screen, a decline revised by the
 * creator, and a custom reimbursement paid back to the member who paid the bill.
 * Needs a display AND the LAMMA_TEST_DB_* settings; skipped otherwise.
 */
class ExpenseFlowTest {

    private Database database;
    private SharedWalletService shared;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private AuthenticatedUser lina;
    private AuthenticatedUser rami;
    private AuthenticatedUser nour;
    private long trip;
    private AppNavigator navigator;

    private static boolean displayAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("win")
                || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    @BeforeAll
    static void startJavaFx() {
        if (!displayAvailable()) {
            return;
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // started by another test class
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(displayAvailable(), "JavaFX UI tests need a display (macOS/Windows, or DISPLAY on Linux)");
        database = TestDatabase.freshSchema(); // skips the test without LAMMA_TEST_DB_*
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = user(registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId(), "Lina", "Haddad");
        rami = user(registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId(), "Rami", "Khoury");
        nour = user(registration.registerPersonal("Nour", "Saad", "71 555 666", "333333", "333333").userId(), "Nour", "Saad");
        TopUpService topUps = new TopUpService(database);
        for (AuthenticatedUser u : new AuthenticatedUser[] {lina, rami, nour}) {
            topUps.cashTopUp(u.userId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
        }
        shared = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        trip = shared.create(lina.userId(), SharedWalletService.validate("Beirut Trip", null, null, null));
        for (AuthenticatedUser u : new AuthenticatedUser[] {rami, nour}) {
            members.inviteByPhone(lina.userId(), trip, "+" + value("SELECT phone_number FROM users WHERE user_id = " + u.userId()));
            members.acceptInvitation(u.userId(), members.incomingInvitations(u.userId()).get(0).invitationId());
        }
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, new WalletService(database),
                    new TopUpService(database), new PaymentCardService(database), new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(members);
            n.useFundsService(funds);
            n.useExpenseService(new ExpenseService(database));
            n.useGiftService(new com.lamma.wallet.GiftDeliveryService(database));
            return n;
        });
    }

    @Test
    void pooledExpenseWithADeclineRevisedAndPaidFromBothScreens() throws Exception {
        signIn(lina);
        openWallet();
        assertTrue(textUnder("#expenseList").contains("No expenses yet."));
        click("#addExpenseButton");
        waitUntil(() -> lookup("#expenseTitleField") != null);
        click("#createExpenseButton");
        assertTrue(text("#createExpenseMessage").startsWith("Expense title is required."));
        type("#expenseTitleField", "Hotel");
        type("#expenseTotalField", "90");
        waitUntil(() -> text("#splitPreview").contains("Rami Khoury: $30.00"));
        assertTrue(text("#splitPreview").contains("Lina Haddad (you): $30.00"));
        click("#createExpenseButton");
        waitUntil(() -> lookup("#expenseTitle") != null);
        assertEquals("Hotel", text("#expenseTitle"));
        assertEquals("Expense created. Each participant was asked for their share.", text("#expenseMessage"));
        assertEquals("Collected $0.00 · Outstanding $90.00", text("#expenseAmounts"));
        assertEquals("Pending", text("#shareState-" + rami.userId()));
        assertEquals("0.00", value("SELECT balance FROM wallets WHERE wallet_id = " + trip), "nobody was charged");

        // Nour (a Contributor) is told on Home, then declines from the Wallets screen.
        switchTo(nour);
        waitUntil(() -> navigator.windowRoot().lookup("#waitingNotice") != null
                && navigator.windowRoot().lookup("#waitingNotice").isVisible());
        assertEquals("1 payment request is waiting for you · Open Wallets",
                onFx(() -> ((Labeled) navigator.windowRoot().lookup("#waitingNotice")).getText()));
        nav("#waitingNotice");
        waitUntil(() -> lookup("#paymentRequestsSection") != null);
        waitUntil(() -> textUnder("#paymentRequestsSection").contains("Payment requests (1)"));
        assertTrue(textUnder("#paymentRequestsSection").contains("Lina Haddad asks you for $30.00 for \u201cHotel\u201d in Beirut Trip"));
        clickFirst("declineRequest-");
        waitUntil(() -> text("#paymentRequestsMessage").startsWith("You declined the request."));

        // Lina sees the decline (not redistributed) and revises: Rami 45, Nour 15.
        switchTo(lina);
        openWallet();
        waitUntil(() -> textUnder("#expenseList").contains("Hotel"));
        clickFirst("expense-");
        waitUntil(() -> lookup("#shareState-" + nour.userId()) != null);
        assertEquals("Declined", text("#shareState-" + nour.userId()));
        assertEquals("Collected $0.00 · Outstanding $90.00", text("#expenseAmounts"));
        click("#reviseExpenseButton");
        waitUntil(() -> lookup("#reviseShare-" + nour.userId()) != null);
        type("#reviseShare-" + rami.userId(), "45");
        type("#reviseShare-" + nour.userId(), "10");
        click("#saveRevisionButton");
        waitUntil(() -> text("#reviseMessage").startsWith("The revised shares add up to $85.00"));
        type("#reviseShare-" + nour.userId(), "15");
        assertEquals("New shares add up to $90.00 of $90.00 unpaid.", text("#revisionSum"));
        click("#saveRevisionButton");
        waitUntil(() -> "Shares revised. Changed shares were sent new requests.".equals(text("#expenseMessage")));
        assertEquals("Pending", text("#shareState-" + nour.userId()));

        // Lina pays her own share on the expense screen (with confirmation).
        clickFirst("payRequest-");
        assertTrue(screenText().contains("Pay $30.00 from your Personal Wallet to \u201cBeirut Trip\u201d?"));
        click("#confirmPayment");
        waitUntil(() -> text("#expenseMessage").startsWith("Paid $30.00 to Beirut Trip."));
        assertEquals("Paid", text("#shareState-" + lina.userId()));

        // Rami and Nour pay from the Wallets screen; a double click pays once.
        for (AuthenticatedUser u : new AuthenticatedUser[] {rami, nour}) {
            switchTo(u);
            nav("#navWallets");
            waitUntil(() -> textUnder("#paymentRequestsSection").contains("Payment requests (1)"));
            clickFirst("payRequest-");
            Node confirm = lookup("#confirmPayment");
            onFx(() -> {
                ((ButtonBase) confirm).fire();
                ((ButtonBase) confirm).fire();
                return null;
            });
            waitUntil(() -> text("#paymentRequestsMessage").startsWith("Paid $"));
        }
        assertEquals("90.00", value("SELECT balance FROM wallets WHERE wallet_id = " + trip));
        assertEquals("55.00", value("SELECT balance FROM wallets WHERE owner_user_id = " + rami.userId()));
        assertEquals("3", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind = 'EXPENSE_PAYMENT'"
                + " AND transaction_type = 'TRANSFER_OUT'"));

        switchTo(lina);
        openWallet();
        waitUntil(() -> textUnder("#expenseList").contains("Settled"));
        assertTrue(textUnder("#sharedActivityList").contains("|Expense payment|From Nour Saad"));
        assertTrue(textUnder("#sharedActivityList").contains("for \u201cHotel\u201d"));
        clickFirst("expense-");
        waitUntil(() -> "Settled".equals(text("#expenseStatus")));
        assertTrue(textUnder("#expenseHistory").contains("Declined by Nour"));
        assertTrue(textUnder("#expenseHistory").contains("Paid by LAMMA transfer · Ref EXP-"));
        assertFalse(textUnder("#expenseHistory").contains("Ref EXPENSER"), "a unique reference per payment");
        assertTrue(lookup("#reviseExpenseButton") == null, "nothing left to revise");
        assertEquals("300.00", value("SELECT SUM(balance) FROM wallets"));
    }

    @Test
    void customReimbursementIsPaidBackToTheMemberWhoPaid() throws Exception {
        signIn(rami);
        openWallet();
        click("#addExpenseButton");
        waitUntil(() -> lookup("#expenseTitleField") != null);
        type("#expenseTitleField", "Dinner");
        type("#expenseTotalField", "50");
        type("#expenseNotesField", "Nour paid by card");
        click("#splitCustom");
        waitUntil(() -> lookup("#customShare-" + nour.userId()) != null);
        type("#customShare-" + lina.userId(), "20");
        type("#customShare-" + rami.userId(), "18");
        type("#customShare-" + nour.userId(), "11");
        click("#collectReimburse");
        Node payer = lookup("#payerChoice");
        onFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Long> c = (ComboBox<Long>) payer;
            c.setValue(nour.userId());
            return null;
        });
        assertTrue(text("#splitPreview").contains("Shares add up to $49.00 of $50.00"));
        click("#createExpenseButton");
        waitUntil(() -> text("#createExpenseMessage").startsWith("The shares add up to $49.00"));
        type("#customShare-" + nour.userId(), "12");
        assertTrue(text("#splitPreview").contains("Nour Saad: $12.00 (covered, paid the bill)"));
        click("#createExpenseButton");
        waitUntil(() -> lookup("#externalPaymentNote") != null);
        assertTrue(text("#externalPaymentNote").startsWith("Nour Saad paid the full $50.00 outside LAMMA (not a LAMMA transaction)."));
        assertEquals("Covered by payer", text("#shareState-" + nour.userId()));
        assertEquals("Collected $0.00 · Outstanding $38.00 · Covered by Nour Saad $12.00", text("#expenseAmounts"));
        clickFirst("payRequest-");
        assertTrue(screenText().contains("Pay $18.00 from your Personal Wallet to Nour Saad?"));
        click("#confirmPayment");
        waitUntil(() -> text("#expenseMessage").startsWith("Paid $18.00 to Nour Saad."));

        switchTo(lina);
        nav("#navWallets");
        waitUntil(() -> textUnder("#paymentRequestsSection").contains("pays back Nour Saad"));
        clickFirst("payRequest-");
        click("#confirmPayment");
        waitUntil(() -> text("#paymentRequestsMessage").startsWith("Paid $20.00 to Nour Saad."));
        assertEquals("138.00", value("SELECT balance FROM wallets WHERE owner_user_id = " + nour.userId()));
        assertEquals("0.00", value("SELECT balance FROM wallets WHERE wallet_id = " + trip), "nothing through the shared wallet");

        switchTo(nour);
        onFx(() -> {
            navigator.showWalletDetail();
            return null;
        });
        waitUntil(() -> screenText().contains("Reimbursement"));
        assertTrue(screenText().contains("|Reimbursement|From Lina Haddad"));
        assertTrue(screenText().contains("for \u201cDinner\u201d"));
        assertEquals("300.00", value("SELECT SUM(balance) FROM wallets"));
    }

    private String value(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static AuthenticatedUser user(long id, String first, String last) {
        return new AuthenticatedUser(id, first, last, "");
    }

    private void signIn(AuthenticatedUser u) throws Exception {
        onFx(() -> {
            navigator.signedIn(new AuthenticatedUser(u.userId(), u.firstName(), u.lastName(), u.phoneNumber()));
            return null;
        });
    }

    private void switchTo(AuthenticatedUser u) throws Exception {
        onFx(() -> {
            navigator.signOut();
            return null;
        });
        signIn(u);
    }

    private void openWallet() throws Exception {
        onFx(() -> {
            navigator.showSharedWalletDetail(trip);
            return null;
        });
        waitUntil(() -> lookup("#sharedWalletName") != null);
    }

    private void nav(String id) throws Exception {
        Node node = onFx(() -> navigator.windowRoot().lookup(id));
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private void buildSkins() {
        Parent root = navigator.windowRoot();
        for (int i = 0; i < 2; i++) {
            root.applyCss();
            root.layout();
        }
    }

    private Node lookup(String selector) {
        try {
            return onFx(() -> {
                buildSkins();
                return navigator.currentScreen().lookup(selector);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void click(String id) throws Exception {
        Node node = lookup(id);
        assertNotNull(node, "no node " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    /** Clicks the first button whose id starts with the prefix (ids end with a database id). */
    private void clickFirst(String idPrefix) throws Exception {
        Node node = findByIdPrefix(idPrefix);
        assertNotNull(node, "no node with id starting " + idPrefix);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private Node findByIdPrefix(String prefix) {
        try {
            return onFx(() -> {
                buildSkins();
                return find(navigator.currentScreen(), prefix);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Node find(Node node, String prefix) {
        if (node.getId() != null && node.getId().startsWith(prefix)) {
            return node;
        }
        if (node instanceof Labeled l && l.getGraphic() != null) {
            Node found = find(l.getGraphic(), prefix);
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node found = find(child, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void type(String id, String value) throws Exception {
        Node node = lookup(id);
        onFx(() -> {
            ((TextInputControl) node).setText(value);
            return null;
        });
    }

    private String text(String id) {
        Node node = lookup(id);
        try {
            return node == null ? "" : onFx(() -> node instanceof Label label ? label.getText() : "");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String screenText() {
        return textUnder(null);
    }

    private String textUnder(String id) {
        try {
            return onFx(() -> {
                buildSkins();
                Node node = id == null ? navigator.currentScreen() : navigator.currentScreen().lookup(id);
                StringBuilder sb = new StringBuilder("|");
                collect(node, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('|');
        }
        if (node instanceof Labeled l && l.getGraphic() != null) {
            collect(l.getGraphic(), sb);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void waitUntil(Supplier<Boolean> condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 5 seconds");
    }
}
