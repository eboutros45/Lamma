package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.ValidationException;
import com.lamma.cards.CardBrand;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.PaymentCardService;
import com.lamma.cards.SavedCard;
import com.lamma.db.DatabaseException;
import com.lamma.wallet.PersonalWallet;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.WalletService;
import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * Payment Methods and credit card top-up screens, driven through the real JavaFX views with
 * fake services (no MySQL). Needs a display like the other JavaFX tests.
 */
class CardFlowsTest {

    private static final AuthenticatedUser LINA = new AuthenticatedUser(1, "Lina", "Haddad", "9613123456");
    private static final AuthenticatedUser RAMI = new AuthenticatedUser(2, "Rami", "Khoury", "96170123456");
    private static final String FULL_NUMBER = "4242424242424242";

    private final Fakes fakes = new Fakes();
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
        navigator = onFx(() -> new AppNavigator(new Stage(), null, null, fakes.wallets, fakes.topUps, fakes.cards));
    }

    // ---------- navigation guards ----------

    @Test
    void cardScreensRequireASession() throws Exception {
        for (Runnable open : List.<Runnable>of(navigator::showCardTopUp, () -> navigator.showPaymentMethods(""),
                navigator::showAddCard)) {
            onFx(() -> {
                open.run();
                return null;
            });
            assertInstanceOf(SignInView.class, onFx(navigator::currentScreen));
        }
    }

    // ---------- credit card top-up ----------

    @Test
    void noSavedCardsGoesStraightToNewCardThenSaveCard() throws Exception {
        openCardTopUp(LINA);
        assertNotNull(lookup("#cardNumberField"), "no saved cards: new-card form opens immediately");

        fillCard("Lina Haddad", FULL_NUMBER, "01/20", "123");
        click("#cardContinueButton");
        assertTrue(text("#cardFormMessage").contains("expired"), text("#cardFormMessage"));

        fillCard("Lina Haddad", FULL_NUMBER, "08/29", "123");
        click("#cardContinueButton");
        assertTrue(text("#payingWithLabel").contains("Visa •••• 4242"), text("#payingWithLabel"));
        assertNoSensitiveTextOnScreen();

        type("#amountField", "30");
        click("#continueButton");
        assertEquals("$30.00", text("#reviewAmountLabel"));
        assertTrue(text("#reviewPaymentMethodLabel").contains("new card Visa •••• 4242"));
        assertTrue(screenText().contains("simulated payment"), "simulation is stated");

        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);
        assertEquals("New balance: $30.00", text("#newBalanceLabel"));
        assertEquals("Would you like to save this card for future top-ups?", text("#saveCardQuestion"));

        click("#saveCardButton");
        click("#confirmSaveCardButton");
        assertTrue(text("#saveCardMessage").contains("nickname is required"), text("#saveCardMessage"));
        type("#nicknameField", "Personal Visa");
        click("#confirmSaveCardButton");
        waitUntil(() -> lookup("#cardSavedTitle") != null);

        assertEquals(1, fakes.topUpCalls.size());
        assertEquals("new:4242", fakes.topUpCalls.get(0).card);
        List<SavedCard> saved = fakes.cards.listCards(1);
        assertEquals(1, saved.size());
        assertEquals("Personal Visa", saved.get(0).nickname());
        assertEquals(new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029), saved.get(0).details());
    }

    @Test
    void notNowDoesNotSaveTheCard() throws Exception {
        newCardTopUpToSuccess("15");

        click("#notNowButton");

        assertInstanceOf(DashboardView.class, onFx(navigator::currentScreen));
        assertTrue(fakes.cards.listCards(1).isEmpty());
        assertEquals(0, fakes.addCardCalls.get());
        waitUntil(() -> "$15.00".equals(text("#balanceLabel")));
    }

    @Test
    void saveCardFailureDoesNotReverseOrRepeatTheTopUp() throws Exception {
        newCardTopUpToSuccess("15");
        fakes.failNextAdd = true;

        click("#saveCardButton");
        type("#nicknameField", "Personal Visa");
        click("#confirmSaveCardButton");
        waitUntil(() -> text("#saveCardMessage").contains("not affected"));
        assertTrue(text("#saveCardMessage").contains("Your top-up of $15.00 is complete"), text("#saveCardMessage"));
        assertEquals(new BigDecimal("15.00"), fakes.balance(1), "top-up not reversed");

        click("#confirmSaveCardButton"); // try again
        waitUntil(() -> lookup("#cardSavedTitle") != null);
        assertEquals(1, fakes.topUpCalls.size(), "top-up not repeated");
        assertEquals(new BigDecimal("15.00"), fakes.balance(1));
        assertEquals(1, fakes.cards.listCards(1).size());
    }

    @Test
    void savedCardSelectionAndTopUp() throws Exception {
        SavedCard whish = fakes.cards.addCard(1, "Whish Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        openCardTopUp(LINA);

        Node choice = lookup("#chooseCard-" + whish.cardId());
        assertNotNull(choice, "saved cards: selection screen");
        String choiceText = textUnder(choice);
        for (String expected : List.of("Whish Card", "Lina Haddad", "Visa •••• 4242", "Expires 08/29")) {
            assertTrue(choiceText.contains(expected), expected + " in " + choiceText);
        }
        assertNotNull(lookup("#useNewCardButton"));

        click("#chooseCard-" + whish.cardId());
        type("#amountField", "40");
        click("#continueButton");
        assertTrue(text("#reviewPaymentMethodLabel").contains("Whish Card (Visa •••• 4242)"));
        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);

        assertNull(lookup("#saveCardQuestion"), "saved card: no save prompt");
        assertEquals("saved:" + whish.cardId(), fakes.topUpCalls.get(0).card);
        click("#backToDashboardButton");
        waitUntil(() -> "$40.00".equals(text("#balanceLabel")));
    }

    @Test
    void useNewCardFromSelectionAndGoBack() throws Exception {
        fakes.cards.addCard(1, "Whish Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        openCardTopUp(LINA);

        click("#useNewCardButton");
        assertNotNull(lookup("#cardNumberField"));
        click("#backToCardsButton");
        waitUntil(() -> lookup("#useNewCardButton") != null);
    }

    @Test
    void expiredSavedCardCannotBeChosen() throws Exception {
        SavedCard old = fakes.cards.addCard(1, "Old Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "1111", 1, 2020));
        openCardTopUp(LINA);

        Node choice = lookup("#chooseCard-" + old.cardId());
        assertTrue(onFx(choice::isDisabled));
        assertTrue(textUnder(choice).contains("Expired"));
    }

    @Test
    void doubleClickSubmitsOnceAndRetryReusesTheRequestId() throws Exception {
        SavedCard whish = fakes.cards.addCard(1, "Whish Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        openCardTopUp(LINA);
        click("#chooseCard-" + whish.cardId());
        type("#amountField", "10");
        click("#continueButton");

        fakes.failNextTopUp = true;
        fakes.block = new CountDownLatch(1);
        click("#confirmTopUpButton");
        click("#confirmTopUpButton"); // ignored: disabled while processing
        fakes.block.countDown();
        waitUntil(() -> text("#topUpMessage").contains("could not be completed"));
        assertEquals(1, fakes.topUpCalls.size());

        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#topUpSuccessTitle") != null);
        assertEquals(2, fakes.topUpCalls.size());
        assertEquals(fakes.topUpCalls.get(0).requestId, fakes.topUpCalls.get(1).requestId);
    }

    @Test
    void cardsLoadedForPreviousUserNeverAppearAfterSwitchingAccounts() throws Exception {
        fakes.cards.addCard(1, "Lina's Secret Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        fakes.blockList = new CountDownLatch(1);
        signIn(LINA);
        onFx(() -> {
            navigator.showCardTopUp(); // Lina's card list is now loading (blocked)
            navigator.signOut();
            navigator.signedIn(fresh(RAMI));
            navigator.showPaymentMethods("");
            return null;
        });
        fakes.blockList.countDown();
        waitUntil(() -> lookup("#noCardsLabel") != null);
        Thread.sleep(300);

        assertInstanceOf(PaymentMethodsView.class, onFx(navigator::currentScreen));
        assertFalse(screenText().contains("Lina's Secret Card"));
        assertEquals(2L, onFx(() -> navigator.session().currentUser().orElseThrow().userId()));
    }

    // ---------- Payment Methods ----------

    @Test
    void addCardFromPaymentMethodsRequiresNickname() throws Exception {
        signIn(LINA);
        clickNav("#navCards"); // Cards destination (redesign: was a dashboard button)
        waitUntil(() -> lookup("#noCardsLabel") != null);
        click("#addCardButton");

        fillCard("Lina Haddad", FULL_NUMBER, "08/29", "123");
        click("#saveNewCardButton");
        assertTrue(text("#addCardMessage").contains("nickname is required"));
        assertEquals(FULL_NUMBER, fieldText("#cardNumberField"), "not cleared while the form is still invalid");

        type("#nicknameField", "Whish Card");
        click("#saveNewCardButton");
        waitUntil(() -> lookup("#cardList") != null && screenText().contains("Whish Card"));
        String list = screenText();
        assertTrue(list.contains("Lina Haddad"));
        assertTrue(list.contains("Visa •••• 4242 · Expires 08/29"));
        assertTrue(text("#paymentMethodsMessage").contains("was saved"));
        assertNoSensitiveTextOnScreen();
    }

    @Test
    void addCardDatabaseFailureKeepsValidatedCardButClearsSensitiveFields() throws Exception {
        signIn(LINA);
        onFx(() -> {
            navigator.showAddCard();
            return null;
        });
        fakes.failNextAdd = true;
        type("#nicknameField", "Whish Card");
        fillCard("Lina Haddad", FULL_NUMBER, "08/29", "123");
        click("#saveNewCardButton");
        waitUntil(() -> text("#addCardMessage").contains("database"));

        assertEquals("", fieldText("#cardNumberField"));
        assertEquals("", fieldText("#cvvField"));
        assertNoSensitiveTextOnScreen();
        click("#saveNewCardButton"); // retry uses the validated metadata
        waitUntil(() -> screenText().contains("was saved"));
        assertEquals("4242", fakes.cards.listCards(1).get(0).details().last4());
    }

    @Test
    void renameAndCancel() throws Exception {
        SavedCard card = fakes.cards.addCard(1, "Whish Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        openPaymentMethods(card);

        click("#renameCard-" + card.cardId());
        type("#renameField-" + card.cardId(), "Changed");
        click("#cancelRename-" + card.cardId());
        assertEquals("Whish Card", text("#cardNickname-" + card.cardId()));

        click("#renameCard-" + card.cardId());
        type("#renameField-" + card.cardId(), "BLOM Card");
        click("#saveRename-" + card.cardId());
        waitUntil(() -> "BLOM Card".equals(text("#cardNickname-" + card.cardId())));
        assertEquals("Card renamed.", text("#paymentMethodsMessage"));
        assertEquals("4242", fakes.cards.listCards(1).get(0).details().last4());
    }

    @Test
    void removeAsksForConfirmation() throws Exception {
        SavedCard card = fakes.cards.addCard(1, "Whish Card", new CardMetadata("Lina Haddad", CardBrand.VISA, "4242", 8, 2029));
        openPaymentMethods(card);

        click("#removeCard-" + card.cardId());
        assertTrue(screenText().contains("Past top-ups stay in your activity"));
        click("#cancelRemove-" + card.cardId());
        assertEquals(1, fakes.cards.listCards(1).size());

        click("#removeCard-" + card.cardId());
        click("#confirmRemove-" + card.cardId());
        waitUntil(() -> lookup("#noCardsLabel") != null);
        assertTrue(fakes.cards.listCards(1).isEmpty());
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
        waitUntil(() -> lookup("#receiveMoneyButton") != null);
    }

    private void openCardTopUp(AuthenticatedUser user) throws Exception {
        signIn(user);
        click("#receiveMoneyButton");
        click("#cardTopUpButton");
        waitUntil(() -> lookup("#cardNumberField") != null || lookup("#useNewCardButton") != null);
    }

    private void openPaymentMethods(SavedCard card) throws Exception {
        signIn(LINA);
        clickNav("#navCards"); // Cards destination (redesign: was a dashboard button)
        waitUntil(() -> lookup("#cardNickname-" + card.cardId()) != null);
    }

    private void newCardTopUpToSuccess(String amount) throws Exception {
        openCardTopUp(LINA);
        fillCard("Lina Haddad", FULL_NUMBER, "08/29", "123");
        click("#cardContinueButton");
        type("#amountField", amount);
        click("#continueButton");
        click("#confirmTopUpButton");
        waitUntil(() -> lookup("#saveCardQuestion") != null);
    }

    private void fillCard(String holder, String number, String expiry, String cvv) throws Exception {
        type("#cardholderField", holder);
        type("#cardNumberField", number);
        type("#expiryField", expiry);
        type("#cvvField", cvv);
    }

    /** No text field or label anywhere in the window shows the full number or the CVV. */
    private void assertNoSensitiveTextOnScreen() {
        String all = screenText();
        assertFalse(all.contains(FULL_NUMBER), "full card number visible: " + all);
        assertFalse(all.contains("4242 4242"), "card number visible");
        assertFalse(all.contains("|123|"), "CVV visible");
    }

    /** All visible text (labels, buttons, fields), separated by "|". */
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

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled l && l.getText() != null) {
            sb.append(l.getText()).append('|');
        }
        if (node instanceof TextInputControl t) {
            sb.append(t.getText()).append('|');
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

    private String textUnder(Node node) {
        try {
            return onFx(() -> {
                StringBuilder sb = new StringBuilder();
                collect(node, sb);
                return sb.toString();
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
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

    private String fieldText(String id) throws Exception {
        Node node = lookup(id);
        return onFx(() -> ((TextInputControl) node).getText());
    }

    /** Navigation buttons live in the app shell, outside the current screen. */
    private void clickNav(String id) throws Exception {
        Node node = onFx(() -> navigator.windowRoot().lookup(id));
        assertNotNull(node, "no navigation item " + id);
        onFx(() -> {
            ((ButtonBase) node).fire();
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

    /** In-memory stand-ins for the wallet, top-up and card services. */
    private static final class Fakes {
        record TopUpCall(long userId, String card, BigDecimal amount, String requestId) {
        }

        final Map<Long, BigDecimal> balances = new ConcurrentHashMap<>();
        final Map<Long, List<SavedCard>> savedCards = new ConcurrentHashMap<>();
        final List<TopUpCall> topUpCalls = new CopyOnWriteArrayList<>();
        final AtomicLong nextCardId = new AtomicLong(7);
        final java.util.concurrent.atomic.AtomicInteger addCardCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean failNextTopUp;
        volatile boolean failNextAdd;
        volatile CountDownLatch block;
        volatile CountDownLatch blockList;

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
                return List.of();
            }
        };

        final TopUpService topUps = new TopUpService(null) {
            @Override
            public TopUpResult savedCardTopUp(long userId, long cardId, BigDecimal amount, String requestId)
                    throws DatabaseException {
                return record(new TopUpCall(userId, "saved:" + cardId, amount, requestId));
            }

            @Override
            public TopUpResult newCardTopUp(long userId, CardMetadata card, BigDecimal amount, String requestId)
                    throws DatabaseException {
                return record(new TopUpCall(userId, "new:" + card.last4(), amount, requestId));
            }

            private TopUpResult record(TopUpCall call) throws DatabaseException {
                topUpCalls.add(call);
                if (block != null) {
                    try {
                        block.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failNextTopUp) {
                    failNextTopUp = false;
                    throw new DatabaseException("The top-up could not be completed because of a database problem.");
                }
                BigDecimal after = balance(call.userId()).add(call.amount());
                balances.put(call.userId(), after);
                return new TopUpResult(new WalletTransaction(topUpCalls.size(), 100 + call.userId(),
                        WalletTransaction.TYPE_CARD_TOP_UP, WalletTransaction.CREDIT, call.amount(), after,
                        WalletTransaction.STATUS_COMPLETED, LocalDateTime.now(), "VISA", "4242"), false);
            }
        };

        final PaymentCardService cards = new PaymentCardService(null) {
            @Override
            public List<SavedCard> listCards(long userId) {
                if (blockList != null && userId == 1) {
                    try {
                        blockList.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return List.copyOf(savedCards.getOrDefault(userId, List.of()));
            }

            @Override
            public SavedCard addCard(long userId, String nickname, CardMetadata card)
                    throws ValidationException, DatabaseException {
                addCardCalls.incrementAndGet();
                if (failNextAdd) {
                    failNextAdd = false;
                    throw new DatabaseException("Your saved cards could not be loaded or changed because the database is unavailable.");
                }
                SavedCard saved = new SavedCard(nextCardId.getAndIncrement(), checkNickname(nickname), card);
                savedCards.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(saved);
                return saved;
            }

            @Override
            public void renameCard(long userId, long cardId, String nickname) throws ValidationException {
                List<SavedCard> list = savedCards.getOrDefault(userId, new ArrayList<>());
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).cardId() == cardId) {
                        list.set(i, new SavedCard(cardId, checkNickname(nickname), list.get(i).details()));
                        return;
                    }
                }
                throw new ValidationException(NOT_FOUND_MESSAGE);
            }

            @Override
            public void removeCard(long userId, long cardId) throws ValidationException {
                if (!savedCards.getOrDefault(userId, new ArrayList<>()).removeIf(c -> c.cardId() == cardId)) {
                    throw new ValidationException(NOT_FOUND_MESSAGE);
                }
            }
        };
    }
}
