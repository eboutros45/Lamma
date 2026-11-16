package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contributions, withdrawals, sending shared money and the histories, through the real screens
 * and real MySQL, from both sides. Lina is the Owner, Rami starts as a Contributor, Sami is a
 * registered user who is not a member. Needs a display AND the LAMMA_TEST_DB_* settings.
 */
class SharedWalletFundsFlowTest {

    private Database database;
    private SharedWalletService shared;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private AuthenticatedUser lina;
    private AuthenticatedUser rami;
    private AuthenticatedUser sami;
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
        sami = user(registration.registerPersonal("Sami", "Aoun", "76 999 000", "444444", "444444").userId(), "Sami", "Aoun");
        TopUpService topUps = new TopUpService(database);
        topUps.cashTopUp(lina.userId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
        topUps.cashTopUp(rami.userId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
        shared = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        trip = shared.create(lina.userId(), SharedWalletService.validate("Beirut Trip", "Summer", null, "900"));
        members.inviteByPhone(lina.userId(), trip, "70 123 456");
        members.acceptInvitation(rami.userId(), members.incomingInvitations(rami.userId()).get(0).invitationId());
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, new WalletService(database),
                    new TopUpService(database), new PaymentCardService(database), new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(members);
            n.useFundsService(funds);
            n.useExpenseService(new com.lamma.wallet.ExpenseService(database));
            n.useGiftService(new com.lamma.wallet.GiftDeliveryService(database));
            return n;
        });
    }

    @Test
    void contributeWithdrawSendAndHistoriesFromBothSides() throws Exception {
        // Lina (Owner) sees all three money actions and contributes $50.
        signIn(lina);
        openWallet();
        assertNotNull(lookup("#withdrawButton"));
        assertNotNull(lookup("#sharedSendButton"));
        assertTrue(screenText().contains("No activity yet."));
        click("#contributeButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        assertEquals("Your available balance: $100.00", text("#sharedAvailableLabel"));
        type("#sharedAmountField", "50");
        click("#sharedContinueButton");
        waitUntil(() -> lookup("#reviewSharedAmount") != null);
        assertEquals("$50.00", text("#reviewSharedAmount"));
        assertEquals("Your Personal Wallet", text("#reviewFromLabel"));
        assertEquals("\u201cBeirut Trip\u201d", text("#reviewToLabel"));
        doubleClick("#confirmSharedTransferButton"); // a double click must contribute once
        waitUntil(() -> lookup("#sharedTransferSuccessTitle") != null);
        assertEquals("Contribution complete", text("#sharedTransferSuccessTitle"));
        assertEquals("\u201cBeirut Trip\u201d balance: $50.00", text("#sharedBalanceAfterLabel"));
        assertEquals("Your Personal Wallet: $50.00", text("#myBalanceAfterLabel"));
        assertEquals("2", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind IS NOT NULL"));
        click("#sharedTransferDoneButton");
        waitUntil(() -> lookup("#sharedWalletName") != null);
        assertEquals("$50.00", text("#sharedWalletBalance"));
        assertTrue(text("#targetLabel").startsWith("$50.00 of $900.00"), "target progress uses the new balance");
        assertTrue(textUnder("#sharedActivityList").contains("|Contribution|From Lina Haddad"));

        // Rami (Contributor) can contribute but sees no Withdraw or Send Money.
        switchTo(rami);
        openWallet();
        assertNotNull(lookup("#contributeButton"));
        assertNull(lookup("#withdrawButton"));
        assertNull(lookup("#sharedSendButton"));
        contribute("20");
        assertEquals("$70.00", text("#sharedWalletBalance"));
        onFx(() -> {
            navigator.showSharedTransfer(trip, SharedTransferView.Mode.WITHDRAW); // even if opened directly
            return null;
        });
        waitUntil(() -> screenText().contains("Only the Owner or a Spender"));
        assertNull(lookup("#sharedAmountField"));
        onFx(() -> {
            navigator.showWalletDetail();
            return null;
        });
        waitUntil(() -> screenText().contains("To Beirut Trip"));
        assertTrue(screenText().contains("|Contribution|To Beirut Trip"), "personal history names the shared wallet");
        assertTrue(screenText().contains("|-$20.00|"));

        // Promoted to Spender: Rami withdraws, then sends shared money to Sami.
        members.changeRole(lina.userId(), trip, rami.userId(), MemberRole.SPENDER);
        openWallet();
        assertEquals("You: Spender", text("#myRoleBadge"));
        click("#withdrawButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        assertEquals("Available in \u201cBeirut Trip\u201d: $70.00", text("#sharedAvailableLabel"));
        type("#sharedAmountField", "100");
        click("#sharedContinueButton");
        assertEquals("Insufficient funds in \u201cBeirut Trip\u201d. Its balance is $70.00.", text("#sharedTransferMessage"));
        type("#sharedAmountField", "10");
        click("#sharedContinueButton");
        waitUntil(() -> lookup("#reviewSharedAmount") != null);
        assertEquals("Your Personal Wallet", text("#reviewToLabel"));
        click("#confirmSharedTransferButton");
        waitUntil(() -> lookup("#sharedTransferSuccessTitle") != null);
        assertEquals("Withdrawal complete", text("#sharedTransferSuccessTitle"));
        assertEquals("Your Personal Wallet: $90.00", text("#myBalanceAfterLabel"));
        click("#sharedTransferDoneButton");
        waitUntil(() -> lookup("#sharedSendButton") != null);

        click("#sharedSendButton");
        waitUntil(() -> lookup("#sharedRecipientPhoneField") != null);
        type("#sharedRecipientPhoneField", "03 999 999");
        click("#sharedFindRecipientButton");
        waitUntil(() -> text("#sharedTransferMessage").startsWith("No LAMMA account"));
        type("#sharedRecipientPhoneField", "+961 76 999 000");
        click("#sharedFindRecipientButton");
        waitUntil(() -> "Sami Aoun".equals(text("#sharedRecipientName")));
        click("#sharedRecipientContinueButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        type("#sharedAmountField", "15");
        click("#sharedContinueButton");
        waitUntil(() -> lookup("#reviewSharedAmount") != null);
        assertEquals("\u201cBeirut Trip\u201d", text("#reviewFromLabel"));
        assertEquals("Sami Aoun", text("#reviewToLabel"));
        click("#confirmSharedTransferButton");
        waitUntil(() -> lookup("#sharedTransferSuccessTitle") != null);
        assertEquals("Money sent", text("#sharedTransferSuccessTitle"));
        assertEquals("\u201cBeirut Trip\u201d sent $15.00 to Sami Aoun.", text("#sharedTransferSuccessDetail"));
        assertNull(lookup("#myBalanceAfterLabel"), "Rami's own wallet didn't change");
        click("#sharedTransferDoneButton");
        waitUntil(() -> lookup("#viewSharedHistoryButton") != null);
        assertEquals("$45.00", text("#sharedWalletBalance"));

        // Full history, as Lina: one row per operation, newest first, with who and what.
        switchTo(lina);
        openWallet();
        click("#viewSharedHistoryButton");
        waitUntil(() -> lookup("#sharedHistoryList") != null && ((VBox) lookup("#sharedHistoryList")).getChildren().size() == 4);
        String history = textUnder("#sharedHistoryList");
        int sent = history.indexOf("|Money Sent|To Sami Aoun \u00b7 by Rami Khoury");
        int withdrawn = history.indexOf("|Withdrawal|To Rami Khoury");
        int ramiIn = history.indexOf("|Contribution|From Rami Khoury");
        int linaIn = history.indexOf("|Contribution|From Lina Haddad");
        assertTrue(sent >= 0 && sent < withdrawn && withdrawn < ramiIn && ramiIn < linaIn, history);
        assertTrue(history.contains("|-$15.00|") && history.contains("|+$50.00|"));
        assertTrue(history.contains("Ref "), "a reference code is shown");
        assertEquals("$45.00", text("#sharedHistoryBalance"));

        // Sami's personal history shows who paid him.
        switchTo(sami);
        onFx(() -> {
            navigator.showWalletDetail();
            return null;
        });
        waitUntil(() -> screenText().contains("From Beirut Trip"));
        assertTrue(screenText().contains("|Money Received|From Beirut Trip \u00b7 sent by Rami Khoury"));
        assertEquals("200.00", value("SELECT SUM(balance) FROM wallets"), "no money created or lost");
    }

    @Test
    void outsidersAreRefusedAndAFailedConfirmMovesNothing() throws Exception {
        // Sami is not a member: no history, no contributing, even by opening the screens directly.
        signIn(sami);
        onFx(() -> {
            navigator.showSharedWalletHistory(trip);
            return null;
        });
        waitUntil(() -> screenText().contains("isn't available"));
        assertTrue(!lookup("#retrySharedHistoryButton").isVisible(), "no Retry: nothing to retry");
        onFx(() -> {
            navigator.showSharedTransfer(trip, SharedTransferView.Mode.CONTRIBUTE);
            return null;
        });
        waitUntil(() -> screenText().contains("isn't available"));
        assertNull(lookup("#sharedAmountField"));

        // Lina: invalid amounts are caught before the review.
        switchTo(lina);
        openWallet();
        click("#contributeButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        type("#sharedAmountField", "abc");
        click("#sharedContinueButton");
        assertTrue(text("#sharedTransferMessage").startsWith("Enter a dollar amount"));
        type("#sharedAmountField", "100.01");
        click("#sharedContinueButton");
        assertEquals("Insufficient funds. Your available balance is $100.00.", text("#sharedTransferMessage"));
        click("#cancelSharedTransferButton");
        waitUntil(() -> lookup("#sharedWalletName") != null);

        // The balance changes between review and confirm: the database refuses, nothing moves.
        funds.contribute(lina.userId(), trip, new BigDecimal("30"), UUID.randomUUID().toString());
        openWallet();
        click("#withdrawButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        type("#sharedAmountField", "30");
        click("#sharedContinueButton");
        waitUntil(() -> lookup("#confirmSharedTransferButton") != null);
        funds.withdraw(lina.userId(), trip, new BigDecimal("20"), UUID.randomUUID().toString()); // e.g. on another computer
        click("#confirmSharedTransferButton");
        waitUntil(() -> text("#sharedTransferMessage").startsWith("Insufficient funds in"));
        assertTrue(!((ButtonBase) lookup("#confirmSharedTransferButton")).isDisabled(), "can try again");
        assertEquals("10.00", value("SELECT balance FROM wallets WHERE wallet_id = " + trip));
        assertEquals("200.00", value("SELECT SUM(balance) FROM wallets"));
    }

    // ---------------- helpers ----------------

    private void contribute(String amount) throws Exception {
        click("#contributeButton");
        waitUntil(() -> lookup("#sharedAmountField") != null);
        type("#sharedAmountField", amount);
        click("#sharedContinueButton");
        waitUntil(() -> lookup("#confirmSharedTransferButton") != null);
        click("#confirmSharedTransferButton");
        waitUntil(() -> lookup("#sharedTransferDoneButton") != null);
        click("#sharedTransferDoneButton");
        waitUntil(() -> lookup("#sharedWalletName") != null);
    }

    private void doubleClick(String id) throws Exception {
        Node node = lookup(id);
        assertNotNull(node, "no node " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
            ((ButtonBase) node).fire();
            return null;
        });
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
