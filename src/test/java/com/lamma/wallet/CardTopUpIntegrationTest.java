package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.cards.CardMetadata;
import com.lamma.cards.CardValidator;
import com.lamma.cards.PaymentCardService;
import com.lamma.cards.SavedCard;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Card top-ups against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class CardTopUpIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T14:30:00Z"), ZoneOffset.UTC);
    private static final YearMonth NOW = YearMonth.of(2026, 11);

    private Database database;
    private TopUpService topUps;
    private WalletService wallets;
    private PaymentCardService cards;
    private long lina;
    private long rami;
    private SavedCard linasCard;
    private CardMetadata mastercard;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        topUps = new TopUpService(database, CLOCK);
        wallets = new WalletService(database);
        cards = new PaymentCardService(database);
        linasCard = cards.addCard(lina, "Whish Card",
                CardValidator.validate("Lina Haddad", "4242424242424242", "08/29", "123", NOW));
        mastercard = CardValidator.validate("Lina Haddad", "5555555555554444", "01/30", "321", NOW);
    }

    @Test
    void savedCardTopUpCreditsWalletAndRecordsCardSnapshot() throws Exception {
        TopUpService.TopUpResult r = topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("40.00"), "s1");

        WalletTransaction t = r.transaction();
        assertFalse(r.alreadyProcessed());
        assertEquals(WalletTransaction.TYPE_CARD_TOP_UP, t.type());
        assertEquals(WalletTransaction.CREDIT, t.direction());
        assertEquals(new BigDecimal("40.00"), t.amount());
        assertEquals(new BigDecimal("40.00"), t.balanceAfter());
        assertEquals("VISA", t.cardBrand());
        assertEquals("4242", t.cardLast4());
        assertEquals(new BigDecimal("40.00"), wallets.loadPersonalWallet(lina).balance());
        assertEquals(List.of(t), wallets.loadRecentTransactions(lina, 10));
    }

    @Test
    void newCardTopUpWorksWithoutSavingTheCard() throws Exception {
        WalletTransaction t = topUps.newCardTopUp(lina, mastercard, new BigDecimal("12.34"), "n1").transaction();

        assertEquals("MASTERCARD", t.cardBrand());
        assertEquals("4444", t.cardLast4());
        assertEquals(new BigDecimal("12.34"), wallets.loadPersonalWallet(lina).balance());
        assertEquals(1, cards.listCards(lina).size(), "new card not saved automatically");
    }

    @Test
    void cashAndCardTopUpsShareOneHistoryAndBalance() throws Exception {
        topUps.cashTopUp(lina, new BigDecimal("10.00"), "c1");
        topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("5.50"), "s1");
        topUps.newCardTopUp(lina, mastercard, new BigDecimal("1.25"), "n1");

        assertEquals(new BigDecimal("16.75"), wallets.loadPersonalWallet(lina).balance());
        assertEquals(List.of("CARD_TOP_UP", "CARD_TOP_UP", "CASH_TOP_UP"),
                wallets.loadRecentTransactions(lina, 10).stream().map(WalletTransaction::type).toList());
        assertEquals(List.of("16.75", "15.50", "10.00"), wallets.loadRecentTransactions(lina, 10).stream()
                .map(t -> t.balanceAfter().toPlainString()).toList());
    }

    @Test
    void anotherUsersCardCannotBeUsed() throws Exception {
        ValidationException e = assertThrows(ValidationException.class,
                () -> topUps.savedCardTopUp(rami, linasCard.cardId(), new BigDecimal("5.00"), "steal"));
        assertEquals(PaymentCardService.NOT_FOUND_MESSAGE, e.getMessage());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(rami).balance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(lina).balance()));
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void removedOrExpiredCardsAreRefused() throws Exception {
        execute("UPDATE payment_cards SET exp_month = 10, exp_year = 2026");  // expired before Nov 2026
        ValidationException expired = assertThrows(ValidationException.class,
                () -> topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("5.00"), "e1"));
        assertEquals(TopUpService.CARD_EXPIRED_MESSAGE, expired.getMessage());
        assertThrows(ValidationException.class, () -> topUps.newCardTopUp(lina,
                new CardMetadata("Lina Haddad", mastercard.brand(), "4444", 10, 2026), new BigDecimal("5.00"), "e2"));

        cards.removeCard(lina, linasCard.cardId());
        assertThrows(ValidationException.class,
                () -> topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("5.00"), "e3"));
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void invalidAmountsAreRejected() {
        for (String bad : List.of("0.00", "-1.00", "10000.01", "0.001")) {
            assertThrows(ValidationException.class,
                    () -> topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal(bad), UUID.randomUUID().toString()));
            assertThrows(ValidationException.class,
                    () -> topUps.newCardTopUp(lina, mastercard, new BigDecimal(bad), UUID.randomUUID().toString()));
        }
    }

    @Test
    void repeatedConfirmationCreditsOnceEvenAfterCardRemoval() throws Exception {
        TopUpService.TopUpResult first = topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("20.00"), "same");
        cards.removeCard(lina, linasCard.cardId());
        TopUpService.TopUpResult retry = topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("20.00"), "same");

        assertTrue(retry.alreadyProcessed());
        assertEquals(first.transaction(), retry.transaction());
        assertEquals(new BigDecimal("20.00"), wallets.loadPersonalWallet(lina).balance());

        TopUpService.TopUpResult newCardRetry1 = topUps.newCardTopUp(lina, mastercard, new BigDecimal("3.00"), "nc");
        TopUpService.TopUpResult newCardRetry2 = topUps.newCardTopUp(lina, mastercard, new BigDecimal("3.00"), "nc");
        assertTrue(newCardRetry2.alreadyProcessed());
        assertEquals(newCardRetry1.transaction(), newCardRetry2.transaction());
        assertEquals(new BigDecimal("23.00"), wallets.loadPersonalWallet(lina).balance());
        assertEquals(2, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void requestIdCannotBeReusedAcrossTopUpTypes() throws Exception {
        topUps.cashTopUp(lina, new BigDecimal("5.00"), "shared");
        assertThrows(ValidationException.class,
                () -> topUps.newCardTopUp(lina, mastercard, new BigDecimal("5.00"), "shared"));
        assertEquals(new BigDecimal("5.00"), wallets.loadPersonalWallet(lina).balance());
    }

    @Test
    void failureWritingTheRecordRollsBackTheCardTopUp() throws Exception {
        TopUpService failing = new TopUpService(database, CLOCK) {
            @Override
            protected long insertTransaction(Connection conn, WalletTransaction t, String requestId)
                    throws SQLException {
                throw new SQLException("Simulated failure");
            }
        };

        assertThrows(DatabaseException.class,
                () -> failing.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("9.00"), "f1"));
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(lina).balance()));
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));

        topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("9.00"), "f1"); // retry succeeds
        assertEquals(new BigDecimal("9.00"), wallets.loadPersonalWallet(lina).balance());
    }

    @Test
    void concurrentCashAndCardTopUpsAreAllCounted() throws Exception {
        List<Callable<TopUpService.TopUpResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> topUps.cashTopUp(lina, new BigDecimal("1.00"), UUID.randomUUID().toString()));
            calls.add(() -> topUps.savedCardTopUp(lina, linasCard.cardId(), new BigDecimal("2.00"), UUID.randomUUID().toString()));
            calls.add(() -> topUps.newCardTopUp(lina, mastercard, new BigDecimal("0.50"), UUID.randomUUID().toString()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(15);
        try {
            for (Future<TopUpService.TopUpResult> f : pool.invokeAll(calls)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(new BigDecimal("35.00"), wallets.loadPersonalWallet(lina).balance());
        assertEquals(30, count("SELECT COUNT(*) FROM wallet_transactions"));
        Set<BigDecimal> runningBalances = new HashSet<>();
        wallets.loadRecentTransactions(lina, 100).forEach(t -> runningBalances.add(t.balanceAfter()));
        assertEquals(30, runningBalances.size(), "every top-up saw the previous balance");
    }

    @Test
    void cashTopUpsStillHaveNoCardDetails() throws Exception {
        WalletTransaction t = topUps.cashTopUp(lina, new BigDecimal("1.00"), "cash").transaction();
        assertEquals(null, t.cardBrand());
        assertEquals(null, wallets.loadRecentTransactions(lina, 1).get(0).cardLast4());
    }

    private long count(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
