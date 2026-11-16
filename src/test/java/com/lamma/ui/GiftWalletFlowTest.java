package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.cards.PaymentCardService;
import com.lamma.contacts.ContactService;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.SharedWalletFundsService;
import com.lamma.wallet.SharedWalletMembershipService;
import com.lamma.wallet.SharedWalletService;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
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
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Creating a gift wallet through the real screens and real MySQL (P04B): the switch reveals the
 * recipient field, the recipient must be found (here from a contact), and the details screen
 * shows who the gift is for. Needs a display AND the LAMMA_TEST_DB_* settings.
 */
class GiftWalletFlowTest {

    private Database database;
    private AuthenticatedUser lina;
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
        PinHasher hasher = new PinHasher(1_000);
        RegistrationService registration = new RegistrationService(database, hasher);
        lina = new AuthenticatedUser(registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111")
                .userId(), "Lina", "Haddad", "9613123456");
        registration.registerPersonal("Nour", "Saad", "71 555 666", "333333", "333333");
        new ContactService(database).importFile(lina.userId(), "c.csv", "Nour Saad,71 555 666\n");
        SharedWalletService shared = new SharedWalletService(database);
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), registration, new AuthenticationService(database, hasher),
                    new WalletService(database), new TopUpService(database), new PaymentCardService(database),
                    new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(new SharedWalletMembershipService(database));
            n.useFundsService(new SharedWalletFundsService(database));
            n.useExpenseService(new com.lamma.wallet.ExpenseService(database));
            n.useGiftService(new com.lamma.wallet.GiftDeliveryService(database));
            n.useContactService(new ContactService(database));
            return n;
        });
        signIn(lina);
    }

    @Test
    void createAGiftWalletForAContact() throws Exception {
        onFx(() -> {
            navigator.showCreateSharedWallet();
            return null;
        });
        waitUntil(() -> lookup("#giftToggle") != null);
        assertFalse(lookup("#giftRecipientSection").isVisible(), "off: the form is as before");
        type("#sharedNameField", "Nour's Birthday");
        click("#giftToggle");
        assertTrue(lookup("#giftRecipientSection").isVisible());
        click("#createSharedWalletSubmit");
        assertEquals("Choose the gift recipient: enter their phone number and press Find.",
                text("#createSharedWalletMessage"));

        type("#giftRecipientPhoneField", "03 123 456");
        click("#findGiftRecipientButton");
        waitUntil(() -> text("#giftRecipientMessage").startsWith("You can't be the gift recipient"));
        type("#giftRecipientPhoneField", "nour");
        waitUntil(() -> textUnder("#giftRecipientPhoneFieldSuggestions").contains("Nour Saad"));
        clickFirst("contactSuggestion-");
        click("#findGiftRecipientButton");
        waitUntil(() -> text("#giftRecipientName").startsWith("Gift for Nour Saad"));
        click("#createSharedWalletSubmit");
        waitUntil(() -> lookup("#giftRecipientLabel") != null);
        assertEquals("Gift for Nour Saad", text("#giftRecipientLabel"));
        assertEquals("You: Owner", text("#myRoleBadge"));
        assertTrue(lookup("#withdrawButton") != null, "the Owner's money actions are unchanged");

        nav("#navWallets");
        waitUntil(() -> textUnder(null).contains("Gift for Nour Saad"));
        assertEquals("1", value("SELECT COUNT(*) FROM shared_wallets WHERE gift_recipient_id IS NOT NULL"));
    }

    @Test
    void switchingTheToggleOffCreatesAnOrdinaryWallet() throws Exception {
        onFx(() -> {
            navigator.showCreateSharedWallet();
            return null;
        });
        waitUntil(() -> lookup("#giftToggle") != null);
        type("#sharedNameField", "Flat");
        click("#giftToggle");
        click("#giftToggle");
        click("#createSharedWalletSubmit");
        waitUntil(() -> lookup("#sharedWalletName") != null);
        assertTrue(lookup("#giftRecipientLabel") == null);
        assertEquals("0", value("SELECT COUNT(*) FROM shared_wallets WHERE gift_recipient_id IS NOT NULL"));
    }

    private String value(String sql) throws Exception {
        try (java.sql.Connection conn = database.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
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
