package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.WalletService;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two-step Sign In and the Sign Up form through the real screens and real MySQL (P05B).
 * Needs a display AND the LAMMA_TEST_DB_* settings; skipped otherwise.
 */
class TwoStepSignInFlowTest {

    private Database database;
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
        registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        AuthenticationService auth = new AuthenticationService(database, hasher);
        navigator = onFx(() -> new AppNavigator(new Stage(), registration, auth, new WalletService(database)));
    }

    @Test
    void phoneFirstThenPinWithWrongPinFeedbackForgotPinAndAnotherNumber() throws Exception {
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertNull(lookup("#pinField"), "step 1 asks for the phone number only");
        type("#phoneField", "12ab");
        click("#continueButton");
        waitUntil(() -> !text("#signInMessage").isEmpty() && !text("#signInMessage").equals("Checking…"));
        assertFalse(visible("#unknownNumberActions"), "an invalid number isn't an unknown account");

        type("#phoneField", "03 999 999");
        click("#continueButton");
        waitUntil(() -> visible("#unknownNumberActions"));
        assertEquals(AuthenticationService.NO_ACCOUNT_MESSAGE, text("#signInMessage"));
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen), "never sent to sign-up automatically");
        click("#tryAgainButton");
        assertFalse(visible("#unknownNumberActions"));
        assertTrue(visible("#continueButton"));

        // An existing account: the PIN screen, with the number masked.
        type("#phoneField", "+961 70 123 456");
        click("#continueButton");
        waitUntil(() -> lookup("#pinField") != null);
        assertInstanceOf(PinEntryView.class, onFx(navigator::currentScreen));
        assertEquals("Signing in as +961 •• ••• 456", text("#maskedPhoneLabel"));
        waitUntil(() -> focused("#pinField"));

        type("#pinField", "999999"); // six digits: submitted automatically
        waitUntil(() -> text("#pinMessage").startsWith("Incorrect PIN."));
        assertEquals(AuthenticationService.wrongPinMessage(4), text("#pinMessage"));
        assertEquals("", field("#pinField"), "a rejected PIN is cleared");

        click("#forgotPinButton");
        assertTrue(visible("#forgotPinPanel"));
        assertEquals(PinEntryView.FORGOT_PIN_TEXT, text("#forgotPinText"));

        click("#differentNumberButton");
        waitUntil(() -> lookup("#continueButton") != null);
        assertEquals("+961 70 123 456", field("#phoneField"), "the number is kept to edit");

        type("#phoneField", "03 123 456");
        click("#continueButton");
        waitUntil(() -> lookup("#pinField") != null);
        type("#pinField", "111111");
        waitUntil(() -> onFx(navigator::currentScreen) instanceof DashboardView);
        assertEquals("Lina", onFx(() -> navigator.session().currentUser().orElseThrow().firstName()));

        // Sign out, then another account: nothing of Lina's sign-in is left.
        onFx(() -> {
            navigator.signOut();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertEquals("", field("#phoneField"));
        assertFalse(onFx(() -> navigator.session().isSignedIn()));
        type("#phoneField", "70 123 456");
        click("#continueButton");
        waitUntil(() -> lookup("#pinField") != null);
        assertEquals("", field("#pinField"), "a new PIN screen starts empty");
        type("#pinField", "222222");
        waitUntil(() -> onFx(navigator::currentScreen) instanceof DashboardView);
        assertEquals("Rami", onFx(() -> navigator.session().currentUser().orElseThrow().firstName()));
    }

    @Test
    void fiveWrongPinsPauseSignInEvenForTheRightPin() throws Exception {
        type("#phoneField", "03 123 456");
        click("#continueButton");
        waitUntil(() -> lookup("#pinField") != null);
        for (int i = 4; i >= 1; i--) {
            type("#pinField", "000000");
            int left = i;
            waitUntil(() -> text("#pinMessage").equals(AuthenticationService.wrongPinMessage(left)));
        }
        type("#pinField", "000000");
        waitUntil(() -> text("#pinMessage").equals(AuthenticationService.LOCKED_MESSAGE));
        type("#pinField", "111111");
        waitUntil(() -> text("#pinMessage").equals(AuthenticationService.LOCKED_MESSAGE) && field("#pinField").isEmpty());
        assertInstanceOf(PinEntryView.class, onFx(navigator::currentScreen), "still locked");
        assertFalse(onFx(() -> navigator.session().isSignedIn()));
    }

    @Test
    void createAccountKeepsTheNumberChecksEachFieldAndEndsOnThePinStep() throws Exception {
        type("#phoneField", "71 555 666");
        click("#continueButton");
        waitUntil(() -> visible("#unknownNumberActions"));
        click("#createAccountForNumberButton");
        click("#personalButton");
        waitUntil(() -> lookup("#firstNameField") != null);
        assertEquals("71 555 666", field("#phoneField"), "the number typed at Sign In is kept");

        click("#createAccountButton");
        assertEquals("First name is required.", text("#firstNameError"));
        assertEquals("Last name is required.", text("#lastNameError"));
        assertEquals("Enter 6 digits.", text("#pinError"));
        type("#firstNameField", "Nour");
        type("#lastNameField", "Saad");
        type("#pinField", "135792");
        type("#confirmPinField", "135793");
        click("#createAccountButton");
        assertEquals("The PINs don't match.", text("#confirmPinError"));
        assertFalse(visible("#firstNameError"), "fixed fields lose their error");
        assertEquals("0", value("SELECT COUNT(*) FROM users WHERE phone_number = '96171555666'"), "nothing sent yet");

        type("#pinField", "135792");
        type("#confirmPinField", "135792");
        click("#createAccountButton");
        waitUntil(() -> onFx(navigator::currentScreen) instanceof PinEntryView);
        assertEquals("Account created. Enter your new PIN to sign in.", text("#pinMessage"));
        assertEquals("Signing in as +961 •• ••• 666", text("#maskedPhoneLabel"));
        type("#pinField", "135792");
        waitUntil(() -> onFx(navigator::currentScreen) instanceof DashboardView);
        assertEquals("Nour", onFx(() -> navigator.session().currentUser().orElseThrow().firstName()));
        assertTrue(value("SELECT pin_hash FROM users WHERE phone_number = '96171555666'").startsWith("pbkdf2-sha256$"),
                "stored only as a hash");

        // After a "restart" (new services), the new account signs in with its six-digit PIN.
        DatabaseConfig c = database.config();
        AuthenticationService fresh = new AuthenticationService(new Database(new DatabaseConfig(c.url(), c.user(),
                c.password())), new PinHasher(1_000));
        assertEquals("96171555666", fresh.findAccount("71 555 666"));
        assertEquals("Nour", fresh.signIn("71 555 666", "135792").firstName());
    }

    // ---------- helpers ----------

    private Node lookup(String id) {
        try {
            return onFx(() -> {
                Parent root = navigator.windowRoot();
                root.applyCss();
                root.layout();
                return root.lookup(id);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean visible(String id) {
        Node n = lookup(id);
        try {
            return n != null && onFx(() -> n.isVisible() && n.isManaged());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean focused(String id) {
        Node n = lookup(id);
        try {
            // The test window isn't shown, so check which node owns the focus in the scene.
            return n != null && onFx(() -> n.getScene() != null && n.getScene().getFocusOwner() == n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void type(String id, String value) throws Exception {
        Node n = lookup(id);
        onFx(() -> {
            ((TextInputControl) n).setText(value);
            return null;
        });
    }

    private String field(String id) {
        Node n = lookup(id);
        try {
            return onFx(() -> ((TextInputControl) n).getText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void click(String id) throws Exception {
        Node n = lookup(id);
        onFx(() -> {
            ((ButtonBase) n).fire();
            return null;
        });
    }

    private String text(String id) {
        Node n = lookup(id);
        try {
            return n == null ? "" : onFx(() -> n instanceof Labeled l && l.getText() != null ? l.getText() : "");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String value(String sql) throws Exception {
        try (java.sql.Connection conn = database.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private interface Check {
        boolean get() throws Exception;
    }

    private static void waitUntil(Check condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 5 seconds");
    }
}
