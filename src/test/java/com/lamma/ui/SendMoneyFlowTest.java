package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.TransferService;
import com.lamma.wallet.WalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
 * Send Money screens, driven through the real JavaFX views with fake services (no MySQL).
 * Needs a display like the other JavaFX tests.
 */
class SendMoneyFlowTest {

    private static final AuthenticatedUser LINA = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
    private static final AuthenticatedUser RAMI = new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");

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
        bank.balances.put(1L, new BigDecimal("100.00"));
        navigator = onFx(() -> new AppNavigator(new Stage(), null, null, bank.wallets, null, null, bank.transfers));
    }

    @Test
    void sendMoneyRequiresASession() throws Exception {
        onFx(() -> {
            navigator.showSendMoney();
            return null;
        });
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
    }

    @Test
    void completeTransferShowsRecipientReviewSuccessAndDashboardActivity() throws Exception {
        openSendMoney(LINA);
        assertFalse(visible("#recipientCard"), "no recipient before lookup");

        type("#recipientPhoneField", "70 123 456");
        click("#findRecipientButton");
        waitUntil(() -> visible("#recipientCard"));
        assertEquals("Rami Khoury", text("#recipientNameLabel"));
        assertEquals("+961 70 123 456", text("#recipientPhoneLabel"));

        click("#recipientContinueButton");
        waitUntil(() -> "Available balance: $100.00".equals(text("#availableBalanceLabel")));
        assertTrue(text("#sendingToLabel").contains("Rami Khoury"));
        type("#amountField", "25");
        click("#continueButton");

        assertEquals("Rami Khoury", text("#reviewRecipientName"));
        assertEquals("+961 70 123 456", text("#reviewRecipientPhone"));
        assertEquals("$25.00", text("#reviewAmountLabel"));
        assertEquals("Your available balance: $100.00", text("#reviewBalanceLabel"));
        assertTrue(screenText().contains("simulated funds between LAMMA wallets"));
        assertEquals(0, bank.sends.size(), "opening the review deducts nothing");
        assertEquals(new BigDecimal("100.00"), bank.balances.get(1L));

        click("#confirmTransferButton");
        waitUntil(() -> lookup("#transferSuccessTitle") != null);
        assertEquals("Money sent", text("#transferSuccessTitle"));
        assertEquals("$25.00 was sent to Rami Khoury.", text("#transferSuccessDetail"));
        assertEquals("New balance: $75.00", text("#newBalanceLabel"));
        assertEquals(1, bank.sends.size());
        assertEquals(1L, bank.sends.get(0).from);
        assertEquals(2L, bank.sends.get(0).to);

        click("#backToDashboardButton");
        waitUntil(() -> "$75.00".equals(text("#balanceLabel")));
        String activity = textUnder("#activityList");
        assertTrue(activity.contains("Money Sent") && activity.contains("To Rami Khoury") && activity.contains("-$25.00"),
                activity);
    }

    @Test
    void recipientSeesMoneyReceivedFromSender() throws Exception {
        bank.history.put(2L, List.of(new WalletTransaction(9, 102, WalletTransaction.TYPE_TRANSFER_IN,
                WalletTransaction.CREDIT, new BigDecimal("25.00"), new BigDecimal("25.00"),
                WalletTransaction.STATUS_COMPLETED, LocalDateTime.of(2026, 11, 21, 9, 15), null, null,
                "t1", 101L, "Lina Haddad")));
        signIn(RAMI);
        waitUntil(() -> textUnder("#activityList").contains("Money Received"));
        String activity = textUnder("#activityList");
        assertTrue(activity.contains("From Lina Haddad") && activity.contains("+$25.00") && activity.contains("Completed"),
                activity);
    }

    @Test
    void lookupErrorsAreShownAndNoRecipientIsSelected() throws Exception {
        openSendMoney(LINA);
        for (String[] c : new String[][] {
                {"03 999 999", "No LAMMA account"},
                {"12ab", "Phone number may only contain"},
                {"", "Phone number is required"},
                {"03 123 456", "can't send money to yourself"}}) {
            type("#recipientPhoneField", c[0]);
            click("#findRecipientButton");
            waitUntil(() -> text("#sendMessage").contains(c[1]));
            assertFalse(visible("#recipientCard"), c[0]);
        }
    }

    @Test
    void changingThePhoneAfterLookupRequiresANewLookup() throws Exception {
        openSendMoney(LINA);
        type("#recipientPhoneField", "70 123 456");
        click("#findRecipientButton");
        waitUntil(() -> visible("#recipientCard"));

        type("#recipientPhoneField", "70 123 457");
        assertFalse(visible("#recipientCard"), "old recipient discarded when the number changes");
    }

    @Test
    void lookupAnswerForAnOldNumberIsIgnored() throws Exception {
        openSendMoney(LINA);
        bank.blockLookup = new CountDownLatch(1);
        type("#recipientPhoneField", "70 123 456");
        click("#findRecipientButton");
        type("#recipientPhoneField", "03 999 999"); // changed while the lookup is running
        bank.blockLookup.countDown();
        Thread.sleep(400);
        onFx(() -> null);
        assertFalse(visible("#recipientCard"));
    }

    @Test
    void amountValidationIncludingAvailableBalance() throws Exception {
        toAmountStep();
        for (String[] c : new String[][] {
                {"0", "at least $0.01"}, {"-5", "greater than zero"}, {"abc", "numbers only"},
                {"1.234", "2 decimal places"}, {"20000", "at most $10,000.00"},
                {"100.01", "Insufficient funds. Your available balance is $100.00."}}) {
            type("#amountField", c[0]);
            click("#continueButton");
            assertTrue(text("#sendMessage").contains(c[1]), c[0] + " -> " + text("#sendMessage"));
        }
        assertEquals(0, bank.sends.size());
        type("#amountField", "100");
        click("#continueButton");
        assertEquals("$100.00", text("#reviewAmountLabel"));
    }

    @Test
    void doubleClickingConfirmSendsOnce() throws Exception {
        toReview("10");
        bank.blockSend = new CountDownLatch(1);
        click("#confirmTransferButton");
        click("#confirmTransferButton");
        click("#confirmTransferButton");
        bank.blockSend.countDown();
        waitUntil(() -> lookup("#transferSuccessTitle") != null);
        assertEquals(1, bank.sends.size());
        assertEquals(new BigDecimal("90.00"), bank.balances.get(1L));
    }

    @Test
    void retryAfterLostResponseUsesSameRequestAndSendsOnce() throws Exception {
        toReview("10");
        bank.loseNextResponse = true; // money moves, but the screen gets an error

        click("#confirmTransferButton");
        waitUntil(() -> text("#sendMessage").contains("could not be completed"));
        assertFalse(onFxNode("#confirmTransferButton", Node::isDisabled), "user can retry");

        click("#confirmTransferButton");
        waitUntil(() -> lookup("#transferSuccessTitle") != null);
        assertEquals(2, bank.sends.size());
        assertEquals(bank.sends.get(0).requestId, bank.sends.get(1).requestId);
        assertEquals(new BigDecimal("90.00"), bank.balances.get(1L), "debited once");
        assertEquals("New balance: $90.00", text("#newBalanceLabel"));
    }

    @Test
    void insufficientFundsAtConfirmationCanBeCorrected() throws Exception {
        toReview("80");
        bank.balances.put(1L, new BigDecimal("50.00")); // spent elsewhere after the review opened

        click("#confirmTransferButton");
        waitUntil(() -> text("#sendMessage").contains("Insufficient funds"));
        click("#editTransferButton");
        assertEquals("80.00", onFxNode("#amountField", n -> ((TextInputControl) n).getText()));
        type("#amountField", "50");
        click("#continueButton");
        click("#confirmTransferButton");
        waitUntil(() -> lookup("#transferSuccessTitle") != null);
        assertNotEquals(bank.sends.get(0).requestId, bank.sends.get(1).requestId, "edited transfer is a new request");
        assertEquals(0, BigDecimal.ZERO.compareTo(bank.balances.get(1L)));
    }

    @Test
    void signingOutDuringSendShowsNoSuccess() throws Exception {
        toReview("10");
        bank.blockSend = new CountDownLatch(1);
        click("#confirmTransferButton");
        onFx(() -> {
            navigator.signOut();
            return null;
        });
        bank.blockSend.countDown();
        Thread.sleep(400);
        onFx(() -> null);
        assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        assertFalse(onFx(() -> navigator.session().isSignedIn()));
    }

    @Test
    void lookupStartedByPreviousUserNeverAppearsForNextUser() throws Exception {
        openSendMoney(LINA);
        bank.blockLookup = new CountDownLatch(1);
        type("#recipientPhoneField", "+1 514 555 0100");
        click("#findRecipientButton");
        onFx(() -> {
            navigator.signOut();
            navigator.signedIn(fresh(RAMI));
            navigator.showSendMoney();
            return null;
        });
        bank.blockLookup.countDown();
        Thread.sleep(400);
        onFx(() -> null);

        assertInstanceOf(SendMoneyView.class, onFx(navigator::currentScreen));
        assertFalse(visible("#recipientCard"));
        assertFalse(screenText().contains("Nour Saad"));
    }

    @Test
    void cancelReturnsToDashboardWithoutSending() throws Exception {
        toReview("10");
        click("#cancelSendButton");
        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertEquals(0, bank.sends.size());
    }

    // ---------- helpers ----------

    private static AuthenticatedUser fresh(AuthenticatedUser u) {
        return new AuthenticatedUser(u.userId(), u.firstName(), u.lastName(), u.phoneNumber());
    }

    private void signIn(AuthenticatedUser user) throws Exception {
        onFx(() -> {
            navigator.signedIn(fresh(user));
            return null;
        });
        waitUntil(() -> lookup("#sendMoneyButton") != null);
    }

    private void openSendMoney(AuthenticatedUser user) throws Exception {
        signIn(user);
        click("#sendMoneyButton");
        assertNotNull(lookup("#recipientPhoneField"));
    }

    private void toAmountStep() throws Exception {
        openSendMoney(LINA);
        type("#recipientPhoneField", "70 123 456");
        click("#findRecipientButton");
        waitUntil(() -> visible("#recipientCard"));
        click("#recipientContinueButton");
        waitUntil(() -> text("#availableBalanceLabel").contains("$"));
    }

    private void toReview(String amount) throws Exception {
        toAmountStep();
        type("#amountField", amount);
        click("#continueButton");
        assertNotNull(lookup("#confirmTransferButton"));
    }

    private boolean visible(String id) {
        Node node = lookup(id);
        try {
            return node != null && onFx(node::isVisible);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T onFxNode(String id, java.util.function.Function<Node, T> f) throws Exception {
        Node node = lookup(id);
        return onFx(() -> f.apply(node));
    }

    private String screenText() {
        try {
            return onFx(() -> {
                Parent root = navigator.currentScreen();
                root.applyCss();
                StringBuilder sb = new StringBuilder("|");
                collect(root, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String textUnder(String id) {
        Node node = lookup(id);
        try {
            return node == null ? "" : onFx(() -> {
                StringBuilder sb = new StringBuilder("|");
                collect(node, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('|');
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private Node lookup(String id) {
        try {
            return onFx(() -> {
                navigator.currentScreen().applyCss();
                return navigator.currentScreen().lookup(id);
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        assertNotNull(node, "no node " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
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

    /** In-memory stand-in for wallets and transfers between three users. */
    private static final class FakeBank {
        record Send(long from, long to, BigDecimal amount, String requestId) {
        }

        final Map<Long, BigDecimal> balances = new ConcurrentHashMap<>();
        final Map<Long, List<WalletTransaction>> history = new ConcurrentHashMap<>();
        final List<Send> sends = new CopyOnWriteArrayList<>();
        final Map<String, TransferService.TransferResult> done = new ConcurrentHashMap<>();
        final Map<String, TransferService.Recipient> directory = Map.of(
                "9613123456", new TransferService.Recipient(1, "Lina", "Haddad", "9613123456"),
                "96170123456", new TransferService.Recipient(2, "Rami", "Khoury", "96170123456"),
                "15145550100", new TransferService.Recipient(3, "Nour", "Saad", "15145550100"));
        volatile CountDownLatch blockLookup;
        volatile CountDownLatch blockSend;
        volatile boolean loseNextResponse;

        BigDecimal balance(long userId) {
            return balances.getOrDefault(userId, new BigDecimal("0.00"));
        }

        final WalletService wallets = new WalletService(null) {
            @Override
            public PersonalWallet loadPersonalWallet(long userId) {
                return new PersonalWallet(100 + userId, userId, balance(userId));
            }

            @Override
            public List<WalletTransaction> loadRecentTransactions(long userId, int limit) {
                return history.getOrDefault(userId, List.of());
            }
        };

        final TransferService transfers = new TransferService(null) {
            @Override
            public Recipient findRecipient(long senderUserId, String phone) throws ValidationException {
                await(blockLookup);
                Recipient r = directory.get(PhoneNumbers.normalize(phone));
                if (r == null) {
                    throw new ValidationException("No LAMMA account uses this phone number. Check the number and try again.");
                }
                if (r.userId() == senderUserId) {
                    throw new ValidationException("You can't send money to yourself.");
                }
                return r;
            }

            @Override
            public synchronized TransferResult send(long from, long to, BigDecimal amount, String requestId)
                    throws ValidationException, DatabaseException {
                sends.add(new Send(from, to, amount, requestId));
                await(blockSend);
                TransferResult earlier = done.get(requestId);
                if (earlier != null) {
                    return new TransferResult(earlier.sent(), earlier.recipient(), true);
                }
                if (balance(from).compareTo(amount) < 0) {
                    throw new ValidationException("Insufficient funds. Your available balance is $"
                            + balance(from).setScale(2) + ".");
                }
                balances.put(from, balance(from).subtract(amount));
                balances.put(to, balance(to).add(amount));
                Recipient r = directory.values().stream().filter(x -> x.userId() == to).findFirst().orElseThrow();
                WalletTransaction sent = new WalletTransaction(sends.size(), 100 + from,
                        WalletTransaction.TYPE_TRANSFER_OUT, WalletTransaction.DEBIT, amount, balance(from),
                        WalletTransaction.STATUS_COMPLETED, LocalDateTime.now(), null, null, requestId, 100 + to,
                        r.fullName());
                history.computeIfAbsent(from, k -> new CopyOnWriteArrayList<>()).add(0, sent);
                TransferResult result = new TransferResult(sent, r, false);
                done.put(requestId, result);
                if (loseNextResponse) {
                    loseNextResponse = false;
                    throw new DatabaseException("The transfer could not be completed because of a database problem.");
                }
                return result;
            }
        };

        private static void await(CountDownLatch latch) {
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
