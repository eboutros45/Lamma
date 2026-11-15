package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationException;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletService;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A slow Sign In or Sign Up result must not take effect after the user has navigated away
 * from that screen (e.g. it must never replace another user's session). Fake services, no
 * MySQL; needs a display like the other JavaFX tests.
 */
class AsyncNavigationRaceTest {

    private CountDownLatch releaseSlowCall;
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
        releaseSlowCall = new CountDownLatch(1);
        PinHasher hasher = new PinHasher(1_000);

        // PIN "111111" is slow (blocks until released) and signs in Lina; anything else signs in Rami at once.
        AuthenticationService auth = new AuthenticationService(null, hasher) {
            @Override
            public AuthenticatedUser signIn(String phone, String pin) throws AuthenticationException {
                if (pin.equals("111111")) {
                    await();
                    return new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
                }
                return new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");
            }
        };
        RegistrationService registration = new RegistrationService(null, hasher) {
            @Override
            public Registration registerPersonal(String f, String l, String phone, String pin, String confirm) {
                await();
                return new Registration(3, 3, "9613999999");
            }
        };
        WalletService wallets = new WalletService(null) {
            @Override
            public PersonalWallet loadPersonalWallet(long userId) throws DatabaseException {
                return new PersonalWallet(100 + userId, userId, new BigDecimal(userId == 1 ? "999.99" : "20.50"));
            }

            @Override
            public java.util.List<com.lamma.wallet.WalletTransaction> loadRecentTransactions(long userId, int limit) {
                return java.util.List.of();
            }
        };
        navigator = onFx(() -> new AppNavigator(new Stage(), registration, auth, wallets));
    }

    @Test
    void slowSignInFinishingAfterAnotherUserSignedInDoesNotReplaceTheirSession() throws Exception {
        type("#phoneField", "03 123 456");
        type("#pinField", "111111");
        click("#signInButton");                 // Lina's sign-in is now stuck

        click("#goToSignUpButton");             // user walks away...
        click("#backToSignInButton");           // ...and comes back to a new Sign In screen
        type("#phoneField", "70 123 456");
        type("#pinField", "222222");
        click("#signInButton");                 // Rami signs in
        waitUntil(() -> "$20.50".equals(text("#balanceLabel")));

        releaseSlowCall.countDown();            // Lina's old result arrives
        Thread.sleep(500);
        onFx(() -> null);

        assertEquals(2L, onFx(() -> navigator.session().currentUser().orElseThrow().userId()));
        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertEquals("Rami Khoury", text("#userNameLabel"));
        assertEquals("$20.50", text("#balanceLabel"));
    }

    @Test
    void slowRegistrationFinishingAfterUserMovedOnDoesNotNavigate() throws Exception {
        click("#goToSignUpButton");
        click("#personalButton");
        type("#firstNameField", "New");
        type("#lastNameField", "User");
        type("#phoneField", "03 999 999");
        type("#pinField", "123456");
        type("#confirmPinField", "123456");
        click("#createAccountButton");          // registration is now stuck

        click("#backToSignInButton");
        type("#phoneField", "70 123 456");
        type("#pinField", "222222");
        click("#signInButton");
        waitUntil(() -> "$20.50".equals(text("#balanceLabel")));

        releaseSlowCall.countDown();            // registration result arrives late
        Thread.sleep(500);
        onFx(() -> null);

        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertEquals("Rami Khoury", text("#userNameLabel"));
    }

    // ---- helpers ----

    private void await() {
        try {
            releaseSlowCall.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Node lookup(String id) {
        return navigator.currentScreen().lookup(id);
    }

    private void type(String id, String value) throws Exception {
        onFx(() -> {
            ((TextInputControl) lookup(id)).setText(value);
            return null;
        });
    }

    private void click(String id) throws Exception {
        onFx(() -> {
            ((Button) lookup(id)).fire();
            return null;
        });
    }

    private String text(String id) {
        try {
            return onFx(() -> lookup(id) instanceof Label label ? label.getText() : null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
