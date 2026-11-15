package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.WalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Receive Money and the cash top-up screens, driven through the real JavaFX views with fake
 * services (no MySQL). Needs a display like the other JavaFX tests.
 */
class CashTopUpFlowTest {

    private final FakeBank bank = new FakeBank();
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
        navigator = onFx(() -> new AppNavigator(new Stage(), null, null, bank.wallets, bank.topUps));
    }

    @Test
    void receiveAndTopUpScreensRequireASession() throws Exception {
        onFx(() -> {
            navigator.showReceive();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        onFx(() -> {
            navigator.showCashTopUp();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void completeCashTopUpUpdatesBalanceAndActivity() throws Exception {
        signInAndOpenDashboard();
        assertNotNull(lookup("#noActivityLabel"));

        click("#receiveMoneyButton");
        assertInstanceOf(ReceiveView.class, onFx(navigator::currentScreen));
        click("#cashTopUpButton");
        assertInstanceOf(CashTopUpView.class, onFx(navigator::currentScreen));

        type("#amountField", "abc");
        click("#continueButton");
        assertTrue(text("#topUpMessage").contains("numbers only"), text("#topUpMessage"));
        type("#amountField", "0");
        click("#continueButton");
        assertTrue(text("#topUpMessage").contains("at least $0.01"), text("#topUpMessage"));
        type("#amountField", "20000");
        click("#continueButton");
        assertTrue(text("#topUpMessage").contains("at most $10,000.00"), text("#topUpMessage"));
        assertEquals(0, bank.calls.size(), "nothing submitted for invalid input");

        type("#amountField", "25.5");
        click("#continueButton");
        assertEquals("$25.50", text("#reviewAmountLabel"));
        assertEquals(0, bank.calls.size(), "review alone submits nothing");

        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);
        assertEquals("New balance: $25.50", text("#newBalanceLabel"));
        assertNull(lookup("#confirmTopUpButton"), "no confirm button after success");
        assertEquals(1, bank.calls.size());
        assertEquals(1L, bank.calls.get(0).userId);

        click("#backToDashboardButton");
        waitUntil(() -> "$25.50".equals(text("#balanceLabel")));
        VBox activity = (VBox) lookup("#activityList");
        assertEquals(1, onFx(() -> activity.getChildren().size()));
    }

    @Test
    void doubleClickingConfirmSubmitsOnce() throws Exception {
        bank.block = new CountDownLatch(1);
        openReview("10");

        click("#confirmTopUpButton");
        click("#confirmTopUpButton"); // disabled while processing: ignored
        click("#confirmTopUpButton");
        bank.block.countDown();
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);

        assertEquals(1, bank.calls.size());
    }

    @Test
    void retryAfterAnErrorReusesTheSameRequestId() throws Exception {
        bank.failNext = true;
        openReview("10");

        click("#confirmTopUpButton");
        waitUntil(() -> text("#topUpMessage").contains("could not be completed"));
        Node confirm = lookup("#confirmTopUpButton");
        assertFalse(onFx(confirm::isDisabled), "user can retry");

        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);

        assertEquals(2, bank.calls.size());
        assertEquals(bank.calls.get(0).requestId, bank.calls.get(1).requestId);
    }

    @Test
    void editingTheAmountStartsANewRequest() throws Exception {
        bank.failNext = true;
        openReview("10");
        click("#confirmTopUpButton");
        waitUntil(() -> text("#topUpMessage").contains("could not be completed"));

        click("#editAmountButton");
        TextInputControl amountField = (TextInputControl) lookup("#amountField");
        assertEquals("10.00", onFx(amountField::getText));
        type("#amountField", "20");
        click("#continueButton");
        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);

        assertEquals(new BigDecimal("20.00"), bank.calls.get(1).amount);
        assertNotEquals(bank.calls.get(0).requestId, bank.calls.get(1).requestId);
    }

    @Test
    void resultArrivingAfterSignOutIsNotShown() throws Exception {
        bank.block = new CountDownLatch(1);
        openReview("10");
        click("#confirmTopUpButton");

        onFx(() -> {
            navigator.signOut();
            return null;
        });
        bank.block.countDown();
        Thread.sleep(500);
        onFx(() -> null);

        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertFalse(onFx(() -> navigator.session().isSignedIn()));
    }

    @Test
    void sendMoneyIsStillNotClickable() throws Exception {
        signInAndOpenDashboard();
        assertFalse(lookup("#sendMoneyPreview") instanceof ButtonBase, "Send Money must not be a button");
        click("#receiveMoneyButton");
        // Alpha 2 P02 enabled Credit Card Top-Up; it is now a working button.
        assertInstanceOf(ButtonBase.class, lookup("#cardTopUpButton"));
    }

    // ---- helpers ----

    private void signInAndOpenDashboard() throws Exception {
        onFx(() -> {
            navigator.signedIn(new AuthenticatedUser(1, "Lina", "Haddad", "9613123456"));
            return null;
        });
        waitUntil(() -> "$0.00".equals(text("#balanceLabel")));
    }

    private void openReview(String amount) throws Exception {
        signInAndOpenDashboard();
        click("#receiveMoneyButton");
        click("#cashTopUpButton");
        type("#amountField", amount);
        click("#continueButton");
        assertNotNull(lookup("#reviewAmountLabel"));
    }

    private Node lookup(String id) {
        try {
            return onFx(() -> {
                // The window is never shown, so build control skins (e.g. ScrollPane content) explicitly.
                navigator.currentScreen().applyCss();
                return navigator.currentScreen().lookup(id);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    private void type(String id, String value) throws Exception {
        Node node = lookup(id);
        onFx(() -> {
            ((TextInputControl) node).setText(value);
            return null;
        });
    }

    private void click(String id) throws Exception {
        Node node = lookup(id);
        onFx(() -> {
            ((ButtonBase) node).fire();
            return null;
        });
    }

    private String text(String id) {
        Node node = lookup(id);
        try {
            return onFx(() -> node instanceof Label label ? label.getText() : null);
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

    /** In-memory stand-in for the wallet and top-up services of one user. */
    private static final class FakeBank {
        record Call(long userId, BigDecimal amount, String requestId) {
        }

        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<WalletTransaction> history = new CopyOnWriteArrayList<>();
        volatile BigDecimal balance = new BigDecimal("0.00");
        volatile boolean failNext;
        volatile CountDownLatch block;

        final WalletService wallets = new WalletService(null) {
            @Override
            public PersonalWallet loadPersonalWallet(long userId) {
                return new PersonalWallet(101, userId, balance);
            }

            @Override
            public List<WalletTransaction> loadRecentTransactions(long userId, int limit) {
                return List.copyOf(history);
            }
        };

        final TopUpService topUps = new TopUpService(null) {
            @Override
            public TopUpResult cashTopUp(long userId, BigDecimal amount, String requestId) throws DatabaseException {
                calls.add(new Call(userId, amount, requestId));
                if (block != null) {
                    try {
                        block.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failNext) {
                    failNext = false;
                    throw new DatabaseException("The top-up could not be completed because of a database problem.");
                }
                balance = balance.add(amount);
                WalletTransaction t = new WalletTransaction(history.size() + 1, 101,
                        WalletTransaction.TYPE_CASH_TOP_UP, WalletTransaction.CREDIT, amount, balance,
                        WalletTransaction.STATUS_COMPLETED, LocalDateTime.now());
                history.add(0, t);
                return new TopUpResult(t, false);
            }
        };
    }
}
