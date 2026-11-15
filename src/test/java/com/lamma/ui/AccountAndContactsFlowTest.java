package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AccountService;
import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationException;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Account settings and the contact book through the real screens and real MySQL: edit name,
 * change phone number and PIN, import and manage contacts, and contact suggestions in the
 * recipient fields. Needs a display AND the LAMMA_TEST_DB_* settings; skipped otherwise.
 */
class AccountAndContactsFlowTest {

    private Database database;
    private AuthenticationService auth;
    private AuthenticatedUser lina;
    private long trip;
    private AppNavigator navigator;

    @TempDir
    Path tempDir;

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
        registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        auth = new AuthenticationService(database, hasher);
        SharedWalletService shared = new SharedWalletService(database);
        trip = shared.create(lina.userId(), SharedWalletService.validate("Beirut Trip", null, null, null));
        navigator = onFx(() -> {
            AppNavigator n = new AppNavigator(new Stage(), registration, auth, new WalletService(database),
                    new TopUpService(database), new PaymentCardService(database), new TransferService(database));
            n.useSharedWalletService(shared);
            n.useMembershipService(new SharedWalletMembershipService(database));
            n.useFundsService(new SharedWalletFundsService(database));
            n.useAccountService(new AccountService(database, hasher));
            n.useContactService(new ContactService(database));
            return n;
        });
        signIn(lina);
    }

    @Test
    void editNameChangePhoneAndChangePin() throws Exception {
        nav("#navProfile");
        waitUntil(() -> lookup("#editNameButton") != null);
        click("#editNameButton");
        waitUntil(() -> lookup("#firstNameField") != null);
        type("#firstNameField", "Lina-Maria");
        click("#saveNameButton");
        waitUntil(() -> "Your name was updated.".equals(text("#profileMessage")));
        assertEquals("Lina-Maria Haddad", text("#profileNameLabel"));
        assertEquals("Lina-Maria", onFx(() -> navigator.session().currentUser().orElseThrow().firstName()),
                "the same sign-in continues with the new name");
        nav("#navHome");
        waitUntil(() -> "Lina-Maria Haddad".equals(text("#userNameLabel")));

        nav("#navProfile");
        waitUntil(() -> lookup("#changePhoneButton") != null);
        click("#changePhoneButton");
        waitUntil(() -> lookup("#newPhoneField") != null);
        assertEquals("Current number: +961 3 123 456", text("#currentPhoneLabel"));
        type("#newPhoneField", "70 123 456");
        type("#currentPinField", "111111");
        click("#savePhoneButton");
        waitUntil(() -> "Another LAMMA account already uses this phone number.".equals(text("#accountMessage")));
        assertEquals("", fieldText("#currentPinField"), "the PIN is cleared after a failure");
        type("#newPhoneField", "71 555 666");
        type("#currentPinField", "999999");
        click("#savePhoneButton");
        waitUntil(() -> "Your current PIN is incorrect.".equals(text("#accountMessage")));
        type("#currentPinField", "111111");
        click("#savePhoneButton");
        waitUntil(() -> text("#profileMessage").startsWith("Your phone number is now +961 71 555 666."));
        assertEquals("+961 71 555 666", text("#profilePhoneLabel"));

        click("#changePinButton");
        waitUntil(() -> lookup("#newPinField") != null);
        type("#currentPinField", "111111");
        type("#newPinField", "246802");
        type("#confirmPinField", "246803");
        click("#savePinButton");
        waitUntil(() -> "The new PIN and its confirmation do not match.".equals(text("#accountMessage")));
        type("#currentPinField", "111111");
        type("#newPinField", "246802");
        type("#confirmPinField", "246802");
        click("#savePinButton");
        waitUntil(() -> text("#profileMessage").startsWith("Your PIN was changed."));
        assertFalse(screenText().contains("246802"), "PINs are never displayed");
        assertEquals(lina.userId(), auth.signIn("71 555 666", "246802").userId());
        assertThrows(AuthenticationException.class, () -> auth.signIn("71 555 666", "111111"));
    }

    @Test
    void importManageAndPickContactsInRecipientFields() throws Exception {
        Path file = tempDir.resolve("friends.csv");
        Files.writeString(file, "Name,Phone\nRami Khoury,70 123 456\nNour Saad,71 555 666\nBroken,12ab\n");
        nav("#navProfile");
        waitUntil(() -> lookup("#manageContactsButton") != null);
        click("#manageContactsButton");
        waitUntil(() -> lookup("#noContactsLabel") != null);
        ContactsView view = (ContactsView) onFx(navigator::currentScreen);
        onFx(() -> {
            view.importPath(file);
            return null;
        });
        waitUntil(() -> text("#contactsMessage").startsWith("2 contacts imported"));
        assertTrue(text("#contactsMessage").contains("1 could not be imported"));
        assertTrue(textUnder("#contactImportReport").contains("Line 4 (Broken)"));
        waitUntil(() -> textUnder("#contactList").contains("Nour Saad"));
        type("#contactSearchField", "ram");
        assertTrue(textUnder("#contactList").contains("Rami Khoury"));
        assertFalse(textUnder("#contactList").contains("Nour Saad"), "search filters the list");
        type("#contactSearchField", "");
        onFx(() -> {
            view.importPath(file);
            return null;
        });
        waitUntil(() -> text("#contactsMessage").startsWith("0 contacts imported, 2 already saved"));

        // Personal Send Money: typing part of a name suggests the contact; choosing it fills the
        // number, which is then looked up as usual.
        onFx(() -> {
            navigator.showSendMoney();
            return null;
        });
        waitUntil(() -> lookup("#recipientPhoneField") != null);
        type("#recipientPhoneField", "Ram");
        waitUntil(() -> textUnder("#recipientPhoneFieldSuggestions").contains("Rami Khoury · +961 70 123 456"));
        clickFirst("contactSuggestion-");
        assertEquals("+961 70 123 456", fieldText("#recipientPhoneField"));
        assertFalse(lookup("#recipientPhoneFieldSuggestions").isVisible(), "the list closes after choosing");
        click("#findRecipientButton");
        waitUntil(() -> "Rami Khoury".equals(text("#recipientNameLabel")));

        // A contact who has no LAMMA account is still refused by the normal lookup.
        onFx(() -> {
            navigator.showInviteMember(trip);
            return null;
        });
        waitUntil(() -> lookup("#invitePhoneField") != null);
        type("#invitePhoneField", "nour");
        waitUntil(() -> textUnder("#invitePhoneFieldSuggestions").contains("Nour Saad"));
        clickFirst("contactSuggestion-");
        click("#sendInvitationButton");
        waitUntil(() -> text("#inviteMessage").startsWith("No LAMMA account"));

        // Shared wallet Send Money has the same suggestions.
        onFx(() -> {
            navigator.showSharedTransfer(trip, SharedTransferView.Mode.SEND);
            return null;
        });
        waitUntil(() -> lookup("#sharedRecipientPhoneField") != null);
        type("#sharedRecipientPhoneField", "70 12");
        waitUntil(() -> textUnder("#sharedRecipientPhoneFieldSuggestions").contains("Rami Khoury"));

        // Delete a contact (with confirmation).
        onFx(() -> {
            navigator.showContacts();
            return null;
        });
        waitUntil(() -> textUnder("#contactList").contains("Nour Saad"));
        clickFirst("deleteContact-");
        click("#confirmDeleteContact");
        waitUntil(() -> text("#contactsMessage").endsWith("was deleted."));
        assertEquals(1, new ContactService(database).list(lina.userId()).size());
    }

    private String fieldText(String id) throws Exception {
        Node node = lookup(id);
        assertNotNull(node, "no node " + id);
        return onFx(() -> ((TextInputControl) node).getText());
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
