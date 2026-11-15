package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.WalletService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Session and navigation behaviour of the real JavaFX screens, with a fake wallet service
 * (no MySQL needed). The window is never shown, but JavaFX still needs a graphical
 * environment, so these tests are skipped on a headless Linux machine (no DISPLAY).
 */
class DashboardNavigationTest {

    private static final AuthenticatedUser LINA = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
    private static final AuthenticatedUser RAMI = new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");

    private FakeWalletService wallets;
    private AppNavigator navigator;

    private static boolean displayAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("win")
                || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    @BeforeAll
    static void startJavaFx() {
        if (!displayAvailable()) {
            return; // each test is then reported as skipped by setUp()
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // fine: started by an earlier test class
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(displayAvailable(), "JavaFX UI tests need a display (macOS/Windows, or DISPLAY on Linux)");
        wallets = new FakeWalletService();
        navigator = onFx(() -> new AppNavigator(new Stage(), null, null, wallets));
    }

    @Test
    void dashboardIsNotReachableWithoutSignIn() throws Exception {
        onFx(() -> {
            navigator.showDashboard();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void signInOpensDashboardWithUsersNameAndBalance() throws Exception {
        wallets.balances.put(1L, new BigDecimal("1234.50"));
        signIn(LINA);

        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertEquals("Lina Haddad", text("#userNameLabel"));
        assertEquals("Personal Wallet", text("#walletHeading"));
        waitUntil(() -> "$1,234.50".equals(text("#balanceLabel")));
        assertEquals(1L, wallets.lastRequestedUserId);
    }

    @Test
    void newUserSeesZeroBalance() throws Exception {
        wallets.balances.put(1L, new BigDecimal("0.00"));
        signIn(LINA);
        waitUntil(() -> "$0.00".equals(text("#balanceLabel")));
    }

    @Test
    void signOutClearsSessionAndBlocksTheOldDashboard() throws Exception {
        wallets.balances.put(1L, new BigDecimal("5.00"));
        signIn(LINA);
        waitUntil(() -> "$5.00".equals(text("#balanceLabel")));

        click("#signOutButton");
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertFalse(onFx(() -> navigator.session().isSignedIn()));

        onFx(() -> {
            navigator.showDashboard(); // e.g. any attempt to navigate "back"
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void switchingAccountsShowsTheNewAccountsData() throws Exception {
        wallets.balances.put(1L, new BigDecimal("10.00"));
        wallets.balances.put(2L, new BigDecimal("20.50"));

        signIn(LINA);
        waitUntil(() -> "$10.00".equals(text("#balanceLabel")));
        click("#signOutButton");

        signIn(RAMI);
        assertEquals("Rami Khoury", text("#userNameLabel"));
        waitUntil(() -> "$20.50".equals(text("#balanceLabel")));
    }

    @Test
    void slowLoadForPreviousUserNeverAppearsOnNextUsersDashboard() throws Exception {
        wallets.balances.put(1L, new BigDecimal("999.99"));
        wallets.balances.put(2L, new BigDecimal("20.50"));
        wallets.blockUser1 = new CountDownLatch(1);

        signIn(LINA);                 // Lina's wallet load is now stuck
        DashboardView linasDashboard = (DashboardView) onFx(navigator::currentScreen);
        click("#signOutButton");
        signIn(RAMI);
        waitUntil(() -> "$20.50".equals(text("#balanceLabel")));

        wallets.blockUser1.countDown(); // Lina's stale result arrives late
        Thread.sleep(500);
        onFx(() -> null);             // let any queued UI updates run

        // What is on screen belongs to Rami...
        assertEquals("Rami Khoury", text("#userNameLabel"));
        assertEquals("$20.50", text("#balanceLabel"));
        // ...and Lina's late result was discarded, not applied to her old (hidden) screen either.
        assertEquals("\u2014", onFx(() -> ((Label) linasDashboard.lookup("#balanceLabel")).getText()));
    }

    @Test
    void walletErrorIsShownWithRetryAndRecovers() throws Exception {
        wallets.failNext = true;
        wallets.balances.put(1L, new BigDecimal("3.00"));
        signIn(LINA);

        waitUntil(() -> text("#walletStatus").contains("could not be loaded"));
        assertEquals("—", text("#balanceLabel"));
        assertTrue(onFx(() -> lookup("#retryWalletButton").isVisible()));

        click("#retryWalletButton");
        waitUntil(() -> "$3.00".equals(text("#balanceLabel")));
        assertFalse(onFx(() -> lookup("#retryWalletButton").isVisible()));
    }

    // ---- helpers ----

    private void signIn(AuthenticatedUser user) throws Exception {
        // A fresh object per sign-in, as AuthenticationService returns.
        AuthenticatedUser fresh = new AuthenticatedUser(user.userId(), user.firstName(), user.lastName(),
                user.phoneNumber());
        onFx(() -> {
            navigator.signedIn(fresh);
            return null;
        });
    }

    private Node lookup(String id) {
        return navigator.currentScreen().lookup(id);
    }

    private String text(String id) {
        try {
            return onFx(() -> {
                Node node = lookup(id);
                return node == null ? null : ((Label) node).getText();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void click(String id) throws Exception {
        onFx(() -> {
            ((Button) lookup(id)).fire();
            return null;
        });
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

    /** Wallet service that returns preset balances and can be made slow or failing. */
    private static final class FakeWalletService extends WalletService {
        final Map<Long, BigDecimal> balances = new ConcurrentHashMap<>();
        volatile CountDownLatch blockUser1;
        volatile boolean failNext;
        volatile Long lastRequestedUserId;

        FakeWalletService() {
            super(null);
        }

        @Override
        public PersonalWallet loadPersonalWallet(long userId) throws DatabaseException {
            lastRequestedUserId = userId;
            if (failNext) {
                failNext = false;
                throw new DatabaseException("Your wallet could not be loaded because the database is unavailable.");
            }
            if (userId == 1 && blockUser1 != null) {
                try {
                    blockUser1.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            BigDecimal balance = balances.get(userId);
            if (balance == null) {
                throw new DatabaseException("We couldn't find your personal wallet.");
            }
            return new PersonalWallet(100 + userId, userId, balance);
        }

        @Override
        public java.util.List<com.lamma.wallet.WalletTransaction> loadRecentTransactions(long userId, int limit) {
            return java.util.List.of();
        }
    }
}
