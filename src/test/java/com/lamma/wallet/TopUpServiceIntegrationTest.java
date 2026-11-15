package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.RegistrationService.Registration;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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

/** Cash top-ups against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class TopUpServiceIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T14:30:00Z"), ZoneOffset.UTC);

    private Database database;
    private TopUpService topUps;
    private WalletService wallets;
    private Registration lina;
    private Registration rami;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        topUps = new TopUpService(database, CLOCK);
        wallets = new WalletService(database);
    }

    @Test
    void cashTopUpCreditsWalletAndRecordsTransaction() throws Exception {
        TopUpService.TopUpResult result = topUps.cashTopUp(lina.userId(), new BigDecimal("25.50"), "req-1");

        assertFalse(result.alreadyProcessed());
        WalletTransaction t = result.transaction();
        assertEquals(lina.walletId(), t.walletId());
        assertEquals(WalletTransaction.TYPE_CASH_TOP_UP, t.type());
        assertEquals(WalletTransaction.CREDIT, t.direction());
        assertEquals(new BigDecimal("25.50"), t.amount());
        assertEquals(new BigDecimal("25.50"), t.balanceAfter());
        assertEquals(WalletTransaction.STATUS_COMPLETED, t.status());
        assertEquals(LocalDateTime.of(2026, 11, 20, 14, 30), t.createdAt());

        assertEquals(new BigDecimal("25.50"), wallets.loadPersonalWallet(lina.userId()).balance());
        List<WalletTransaction> history = wallets.loadRecentTransactions(lina.userId(), 10);
        assertEquals(List.of(t), history, "stored record matches what was returned");
    }

    @Test
    void successiveTopUpsAccumulateAndHistoryIsNewestFirst() throws Exception {
        topUps.cashTopUp(lina.userId(), new BigDecimal("10.00"), "a");
        topUps.cashTopUp(lina.userId(), new BigDecimal("0.01"), "b");
        topUps.cashTopUp(lina.userId(), new BigDecimal("10000.00"), "c");

        assertEquals(new BigDecimal("10010.01"), wallets.loadPersonalWallet(lina.userId()).balance());
        List<WalletTransaction> history = wallets.loadRecentTransactions(lina.userId(), 10);
        assertEquals(List.of("10000.00", "0.01", "10.00"),
                history.stream().map(t -> t.amount().toPlainString()).toList());
        assertEquals(List.of("10010.01", "10.01", "10.00"),
                history.stream().map(t -> t.balanceAfter().toPlainString()).toList());
        assertEquals(2, wallets.loadRecentTransactions(lina.userId(), 2).size(), "limit is respected");
    }

    @Test
    void invalidAmountsChangeNothing() throws Exception {
        for (String bad : List.of("0.00", "-5.00", "10000.01", "0.001")) {
            assertThrows(ValidationException.class,
                    () -> topUps.cashTopUp(lina.userId(), new BigDecimal(bad), UUID.randomUUID().toString()), bad);
        }
        assertThrows(ValidationException.class, () -> topUps.cashTopUp(lina.userId(), null, "x"));
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(lina.userId()).balance()));
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void topUpAffectsOnlyTheSignedInUsersWallet() throws Exception {
        topUps.cashTopUp(lina.userId(), new BigDecimal("40.00"), "lina-1");
        topUps.cashTopUp(rami.userId(), new BigDecimal("5.00"), "rami-1");

        assertEquals(new BigDecimal("40.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(new BigDecimal("5.00"), wallets.loadPersonalWallet(rami.userId()).balance());
        List<WalletTransaction> linaHistory = wallets.loadRecentTransactions(lina.userId(), 10);
        List<WalletTransaction> ramiHistory = wallets.loadRecentTransactions(rami.userId(), 10);
        assertEquals(1, linaHistory.size());
        assertEquals(lina.walletId(), linaHistory.get(0).walletId());
        assertEquals(1, ramiHistory.size());
        assertEquals(rami.walletId(), ramiHistory.get(0).walletId());
    }

    @Test
    void failureWritingTheTransactionRollsBackTheBalance() throws Exception {
        TopUpService failing = new TopUpService(database, CLOCK) {
            @Override
            protected long insertTransaction(Connection conn, long walletId, BigDecimal amount,
                                             BigDecimal balanceAfter, String requestId, LocalDateTime createdAt)
                    throws SQLException {
                throw new SQLException("Simulated failure after the balance update");
            }
        };

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> failing.cashTopUp(lina.userId(), new BigDecimal("30.00"), "req-fail"));

        assertEquals(TopUpService.FAILED_MESSAGE, e.getMessage());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(lina.userId()).balance()),
                "balance update must be rolled back");
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));

        // The same request can be retried successfully afterwards.
        topUps.cashTopUp(lina.userId(), new BigDecimal("30.00"), "req-fail");
        assertEquals(new BigDecimal("30.00"), wallets.loadPersonalWallet(lina.userId()).balance());
    }

    @Test
    void repeatingTheSameRequestCreditsOnlyOnce() throws Exception {
        TopUpService.TopUpResult first = topUps.cashTopUp(lina.userId(), new BigDecimal("12.00"), "same");
        TopUpService.TopUpResult second = topUps.cashTopUp(lina.userId(), new BigDecimal("12.00"), "same");

        assertFalse(first.alreadyProcessed());
        assertTrue(second.alreadyProcessed());
        assertEquals(first.transaction(), second.transaction());
        assertEquals(new BigDecimal("12.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(1, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void reusingARequestIdWithDifferentDetailsIsRejected() throws Exception {
        topUps.cashTopUp(lina.userId(), new BigDecimal("12.00"), "same");

        ValidationException differentAmount = assertThrows(ValidationException.class,
                () -> topUps.cashTopUp(lina.userId(), new BigDecimal("99.00"), "same"));
        assertEquals(TopUpService.REQUEST_REUSED_MESSAGE, differentAmount.getMessage());
        assertThrows(ValidationException.class,
                () -> topUps.cashTopUp(rami.userId(), new BigDecimal("12.00"), "same"));

        assertEquals(new BigDecimal("12.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.loadPersonalWallet(rami.userId()).balance()));
    }

    @Test
    void concurrentTopUpsAreAllCountedWithoutLostUpdates() throws Exception {
        int threads = 20;
        List<Callable<TopUpService.TopUpResult>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> topUps.cashTopUp(lina.userId(), new BigDecimal("1.25"), UUID.randomUUID().toString()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (Future<TopUpService.TopUpResult> f : pool.invokeAll(calls)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(new BigDecimal("25.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(threads, count("SELECT COUNT(*) FROM wallet_transactions"));
        Set<BigDecimal> balancesAfter = new HashSet<>();
        for (WalletTransaction t : wallets.loadRecentTransactions(lina.userId(), 100)) {
            balancesAfter.add(t.balanceAfter());
        }
        assertEquals(threads, balancesAfter.size(), "each top-up saw the previous one's balance");
    }

    @Test
    void concurrentRepeatsOfOneRequestCreditOnce() throws Exception {
        int threads = 10;
        List<Callable<TopUpService.TopUpResult>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> topUps.cashTopUp(lina.userId(), new BigDecimal("50.00"), "double-click"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int fresh = 0;
        try {
            for (Future<TopUpService.TopUpResult> f : pool.invokeAll(calls)) {
                if (!f.get().alreadyProcessed()) {
                    fresh++;
                }
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(1, fresh, "exactly one call performed the credit");
        assertEquals(new BigDecimal("50.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(1, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void dataSurvivesARestart() throws Exception {
        topUps.cashTopUp(lina.userId(), new BigDecimal("7.77"), "before-restart");

        // A "restarted" application: brand-new connection settings and services.
        DatabaseConfig original = database.config();
        Database reopened = new Database(new DatabaseConfig(original.url(), original.user(), original.password()));
        WalletService afterRestart = new WalletService(reopened);

        assertEquals(new BigDecimal("7.77"), afterRestart.loadPersonalWallet(lina.userId()).balance());
        assertEquals(1, afterRestart.loadRecentTransactions(lina.userId(), 10).size());
    }

    @Test
    void topUpThatWouldOverflowTheBalanceIsRejected() throws Exception {
        execute("UPDATE wallets SET balance = 9999999999999.00 WHERE wallet_id = " + lina.walletId());

        ValidationException e = assertThrows(ValidationException.class,
                () -> topUps.cashTopUp(lina.userId(), new BigDecimal("1.00"), "overflow"));
        assertEquals(TopUpService.BALANCE_LIMIT_MESSAGE, e.getMessage());
        assertEquals(new BigDecimal("9999999999999.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void userWithoutPersonalWalletGetsFriendlyError() {
        DatabaseException e = assertThrows(DatabaseException.class,
                () -> topUps.cashTopUp(999_999, new BigDecimal("5.00"), "nobody"));
        assertEquals(WalletService.NOT_FOUND_MESSAGE, e.getMessage());
    }

    @Test
    void newUserHasNoActivity() throws Exception {
        assertTrue(wallets.loadRecentTransactions(rami.userId(), 10).isEmpty());
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
