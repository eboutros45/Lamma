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
import com.lamma.wallet.GiftDeliveryService;
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
import java.util.List;
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
 * Send Gift through the real screens and real MySQL (P05): blocked while the balance is zero or an
 * expense is unsettled, the review, a double-clicked confirm delivering once, and the completed,
 * read-only wallet seen by the Owner, a member and the recipient.
 * Needs a display AND the LAMMA_TEST_DB_* settings; skipped otherwise.
 */
class GiftDeliveryFlowTest {

    private Database database;
    private SharedWalletService shared;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private ExpenseService expenses;
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
        registration.registerPersonal("Sami", "Aoun", "76 999 000", "444444", "444444");
        TopUpService topUps = new TopUpService(database);
        for (AuthenticatedUser u : new AuthenticatedUser[] {lina, rami, nour}) {
            topUps.cashTopUp(u.userId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
        }
        shared = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        expenses = new ExpenseService(database);
        trip = shared.create(lina.userId(), SharedWalletService.validate("Nour's Graduation", null, null, "300")
                .asGiftFor(nour.userId()));
        members.inviteByPhone(lina.userId(), trip, "70 123 456");
        members.acceptInvitation(rami.userId(), members.incomingInvitations(rami.userId()).get(0).invitationId());
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), null, null, new WalletService(database),
                    new TopUpService(database), new PaymentCardService(database), new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(members);
            n.useFundsService(funds);
            n.useExpenseService(expenses);
            n.useGiftService(new GiftDeliveryService(database));
            return n;
        });
    }

    @Test
    void sendGiftFromReviewToCompletedReadOnlyWallet() throws Exception {
        // A Contributor never sees Send Gift.
        signIn(rami);
        openWallet();
        assertNull(lookup("#sendGiftCard"));

        // The Owner: nothing to deliver yet.
        switchTo(lina);
        openWallet();
        assertTrue(textUnder("#sendGiftCard").contains("Gift for Nour Saad"));
        click("#sendGiftButton");
        waitUntil(() -> lookup("#giftBlockedReason") != null);
        assertTrue(text("#giftBlockedReason").startsWith("There's nothing to deliver yet"));
        assertNull(lookup("#confirmGiftDeliveryButton"));

        // Money comes in, but an expense is still unsettled: blocked, with the reason.
        funds.contribute(rami.userId(), trip, new BigDecimal("25"), UUID.randomUUID().toString());
        funds.contribute(lina.userId(), trip, new BigDecimal("15"), UUID.randomUUID().toString());
        long cake = expenses.create(lina.userId(), trip, new ExpenseService.NewExpense("Cake", new BigDecimal("10"), null,
                List.of(lina.userId(), rami.userId()), ExpenseService.SplitMethod.EQUAL, null,
                ExpenseService.Collection.REIMBURSEMENT, lina.userId()));
        members.inviteByPhone(lina.userId(), trip, "76 999 000");
        onFx(() -> {
            navigator.showSendGift(trip);
            return null;
        });
        waitUntil(() -> lookup("#giftBlockedReason") != null);
        assertTrue(text("#giftBlockedReason").contains("Cake"));
        assertNull(lookup("#confirmGiftDeliveryButton"));
        expenses.cancel(lina.userId(), cake);

        // The review, then a double-clicked confirm.
        onFx(() -> {
            navigator.showSendGift(trip);
            return null;
        });
        waitUntil(() -> lookup("#confirmGiftDeliveryButton") != null);
        assertEquals("Nour's Graduation", text("#giftWalletName"));
        assertEquals("Nour Saad", text("#giftRecipientFullName"));
        assertEquals("+961 71 555 666", text("#giftRecipientPhone"));
        assertEquals("$40.00", text("#giftCurrentBalance"));
        assertEquals("$40.00", text("#giftDeliveryAmount"));
        assertEquals("$0.00", text("#giftBalanceAfter"));
        assertEquals("$300.00 (information only)", text("#giftTarget"));
        assertTrue(text("#giftClosingNote").startsWith("Pending invitations (1)"));
        Node confirm = lookup("#confirmGiftDeliveryButton");
        assertEquals("Send $40.00 to Nour Saad", onFx(() -> ((Labeled) confirm).getText()));
        onFx(() -> {
            ((ButtonBase) confirm).fire();
            ((ButtonBase) confirm).fire();
            return null;
        });
        waitUntil(() -> lookup("#giftDeliveredTitle") != null);
        assertEquals("140.00", value("SELECT balance FROM wallets WHERE owner_user_id = " + nour.userId()), "delivered once");

        // The completed wallet: delivered banner, everything read-only.
        click("#giftDeliveredDoneButton");
        waitUntil(() -> lookup("#giftDeliveredBanner") != null);
        assertEquals("Delivered", text("#myRoleBadge"));
        assertEquals("$0.00", text("#sharedWalletBalance"));
        assertTrue(text("#giftDeliveredText").startsWith("$40.00 was sent to Nour Saad's Personal Wallet on "));
        assertTrue(text("#giftDeliveredText").endsWith("(Ref GIFT-" + trip + ")."), text("#giftDeliveredText"));
        for (String id : new String[] {"#contributeButton", "#withdrawButton", "#sharedSendButton", "#sendGiftCard",
                "#inviteMemberButton", "#shareQrButton", "#addExpenseButton", "#leaveWalletButton"}) {
            assertNull(lookup(id), id + " must not be offered on a completed gift");
        }
        assertNull(findByIdPrefix("roleChoice-"));
        assertNull(findByIdPrefix("removeMember-"));
        assertTrue(textUnder("#sharedActivityList").contains("|Gift delivered|To Nour Saad"));
        assertTrue(textUnder("#expenseList").contains("Cake"), "expenses stay visible");
        nav("#navWallets");
        waitUntil(() -> lookup("#completedWalletList") != null);
        assertTrue(textUnder("#completedWalletList").contains("Delivered to Nour Saad · read-only"));

        // A member sees it read-only too, and can't move money even by opening a screen directly.
        switchTo(rami);
        openWallet();
        assertNull(lookup("#leaveWalletButton"));
        assertNull(lookup("#contributeButton"));
        onFx(() -> {
            navigator.showSharedTransfer(trip, SharedTransferView.Mode.CONTRIBUTE);
            return null;
        });
        waitUntil(() -> screenText().contains("This gift has been delivered."));
        assertNull(lookup("#sharedAmountField"));

        // The recipient got an ordinary, traceable incoming transaction.
        switchTo(nour);
        onFx(() -> {
            navigator.showWalletDetail();
            return null;
        });
        waitUntil(() -> screenText().contains("Gift received"));
        assertTrue(screenText().contains("|Gift received|From Nour's Graduation · sent by Lina Haddad"));
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
