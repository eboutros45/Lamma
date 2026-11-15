package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.lamma.wallet.TransferService.Recipient;
import com.lamma.wallet.TransferService.TransferResult;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Send Money against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class TransferServiceIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T09:15:00Z"), ZoneOffset.UTC);

    private Database database;
    private TransferService transfers;
    private TopUpService topUps;
    private WalletService wallets;
    private Registration lina;
    private Registration rami;
    private Registration nour;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        nour = registration.registerPersonal("Nour", "Saad", "+1 514 555 0100", "333333", "333333");
        transfers = new TransferService(database, CLOCK);
        topUps = new TopUpService(database, CLOCK);
        wallets = new WalletService(database);
        topUps.cashTopUp(lina.userId(), new BigDecimal("100.00"), "seed-lina");
    }

    // ---------- recipient lookup ----------

    @Test
    void findsRecipientByAnyAcceptedPhoneFormat() throws Exception {
        for (String phone : List.of("70 123 456", "+961 70 123 456", "96170123456", "0096170123456")) {
            Recipient r = transfers.findRecipient(lina.userId(), phone);
            assertEquals(rami.userId(), r.userId(), phone);
            assertEquals("Rami Khoury", r.fullName());
            assertEquals("96170123456", r.phoneNumber());
        }
    }

    @Test
    void lookupRejectsUnknownInvalidAndSelf() {
        ValidationException unknown = assertThrows(ValidationException.class,
                () -> transfers.findRecipient(lina.userId(), "03 999 999"));
        assertEquals(TransferService.NOT_FOUND_MESSAGE, unknown.getMessage());
        assertThrows(ValidationException.class, () -> transfers.findRecipient(lina.userId(), "12ab"));
        assertThrows(ValidationException.class, () -> transfers.findRecipient(lina.userId(), ""));
        ValidationException self = assertThrows(ValidationException.class,
                () -> transfers.findRecipient(lina.userId(), "+961 3 123 456"));
        assertEquals(TransferService.SELF_TRANSFER_MESSAGE, self.getMessage());
    }

    @Test
    void recipientCarriesNoPrivateData() {
        for (var component : Recipient.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertFalse(name.contains("pin") || name.contains("hash") || name.contains("balance"), name);
        }
    }

    // ---------- successful transfers ----------

    @Test
    void transferMovesMoneyAndRecordsBothLinkedSides() throws Exception {
        TransferResult result = transfers.send(lina.userId(), rami.userId(), new BigDecimal("25.50"), "t1");

        assertFalse(result.alreadyProcessed());
        assertEquals("Rami Khoury", result.recipient().fullName());
        assertEquals(new BigDecimal("74.50"), balance(lina));
        assertEquals(new BigDecimal("25.50"), balance(rami));

        WalletTransaction sent = wallets.loadRecentTransactions(lina.userId(), 10).get(0);
        WalletTransaction received = wallets.loadRecentTransactions(rami.userId(), 10).get(0);
        assertEquals(result.sent(), sent, "returned record matches the stored one");

        assertEquals(WalletTransaction.TYPE_TRANSFER_OUT, sent.type());
        assertEquals(WalletTransaction.DEBIT, sent.direction());
        assertEquals(new BigDecimal("25.50"), sent.amount());
        assertEquals(new BigDecimal("74.50"), sent.balanceAfter());
        assertEquals(lina.walletId(), sent.walletId());
        assertEquals(rami.walletId(), sent.counterpartyWalletId());
        assertEquals("Rami Khoury", sent.counterpartyName());
        assertEquals(WalletTransaction.STATUS_COMPLETED, sent.status());
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 15), sent.createdAt());

        assertEquals(WalletTransaction.TYPE_TRANSFER_IN, received.type());
        assertEquals(WalletTransaction.CREDIT, received.direction());
        assertEquals(new BigDecimal("25.50"), received.amount());
        assertEquals(new BigDecimal("25.50"), received.balanceAfter());
        assertEquals(rami.walletId(), received.walletId());
        assertEquals(lina.walletId(), received.counterpartyWalletId());
        assertEquals("Lina Haddad", received.counterpartyName());

        assertEquals("t1", sent.transferId());
        assertEquals(sent.transferId(), received.transferId(), "both sides linked by the same transfer id");
        assertEquals(2, count("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_id = 't1'"));
    }

    @Test
    void sendingTheWholeBalanceLeavesZero() throws Exception {
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("100.00"), "all");
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(lina)));
        assertEquals(new BigDecimal("100.00"), balance(rami));
    }

    @Test
    void unrelatedUserSeesNothingAndMoneyIsConserved() throws Exception {
        BigDecimal totalBefore = totalMoney();
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("10.00"), "a");
        transfers.send(rami.userId(), lina.userId(), new BigDecimal("4.00"), "b");
        transfers.send(lina.userId(), nour.userId(), new BigDecimal("1.25"), "c");

        assertEquals(totalBefore, totalMoney(), "transfers neither create nor destroy money");
        assertEquals(List.of(), wallets.loadRecentTransactions(nour.userId(), 10).stream()
                .filter(t -> !t.counterpartyName().equals("Lina Haddad")).toList(), "Nour only sees Lina's transfer");
        assertEquals(1, wallets.loadRecentTransactions(nour.userId(), 10).size());
        assertEquals(List.of("TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_OUT", "CASH_TOP_UP"),
                wallets.loadRecentTransactions(lina.userId(), 10).stream().map(WalletTransaction::type).toList());
        assertEquals(List.of("TRANSFER_OUT", "TRANSFER_IN"),
                wallets.loadRecentTransactions(rami.userId(), 10).stream().map(WalletTransaction::type).toList());
        // Every wallet's balance equals its own credits minus debits (all money came from top-ups here).
        for (Registration r : List.of(lina, rami, nour)) {
            BigDecimal net = BigDecimal.ZERO;
            for (WalletTransaction t : wallets.loadRecentTransactions(r.userId(), 100)) {
                net = t.isCredit() ? net.add(t.amount()) : net.subtract(t.amount());
            }
            assertEquals(0, net.compareTo(balance(r)), "ledger matches balance for user " + r.userId());
        }
    }

    @Test
    void transfersSurviveARestart() throws Exception {
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("12.00"), "persist");
        DatabaseConfig c = database.config();
        WalletService afterRestart = new WalletService(new Database(new DatabaseConfig(c.url(), c.user(), c.password())));

        assertEquals(new BigDecimal("88.00"), afterRestart.loadPersonalWallet(lina.userId()).balance());
        assertEquals(new BigDecimal("12.00"), afterRestart.loadPersonalWallet(rami.userId()).balance());
        assertEquals("Lina Haddad", afterRestart.loadRecentTransactions(rami.userId(), 10).get(0).counterpartyName());
    }

    // ---------- validation ----------

    @Test
    void invalidAmountsAreRejectedAndNothingChanges() throws Exception {
        for (String bad : List.of("0.00", "-5.00", "10000.01", "0.001")) {
            assertThrows(ValidationException.class,
                    () -> transfers.send(lina.userId(), rami.userId(), new BigDecimal(bad), UUID.randomUUID().toString()), bad);
        }
        assertThrows(ValidationException.class, () -> transfers.send(lina.userId(), rami.userId(), null, "n"));
        assertUnchanged();
    }

    @Test
    void insufficientFundsAreRejected() throws Exception {
        ValidationException e = assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), rami.userId(), new BigDecimal("100.01"), "too-much"));
        assertEquals("Insufficient funds. Your available balance is $100.00.", e.getMessage());
        assertUnchanged();
    }

    @Test
    void balanceIsRecheckedAtConfirmationNotAtReview() throws Exception {
        // The user reviews a $80 transfer while holding $100...
        Recipient recipient = transfers.findRecipient(lina.userId(), "70 123 456");
        // ...but meanwhile another transfer spends $30.
        transfers.send(lina.userId(), nour.userId(), new BigDecimal("30.00"), "meanwhile");

        ValidationException e = assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), recipient.userId(), new BigDecimal("80.00"), "reviewed"));
        assertTrue(e.getMessage().contains("Insufficient funds"));
        assertEquals(new BigDecimal("70.00"), balance(lina));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(rami)));
    }

    @Test
    void selfTransferIsRejectedEvenIfRequestedDirectly() {
        assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), lina.userId(), new BigDecimal("5.00"), "self"));
    }

    @Test
    void recipientNoLongerEligibleAtConfirmation() throws Exception {
        // Nour has no transactions yet; removing her wallet simulates an account that is gone.
        Recipient recipient = transfers.findRecipient(lina.userId(), "+1 514 555 0100");
        execute("DELETE FROM wallets WHERE wallet_id = " + nour.walletId());

        ValidationException e = assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), recipient.userId(), new BigDecimal("5.00"), "gone"));
        assertEquals(TransferService.RECIPIENT_UNAVAILABLE_MESSAGE, e.getMessage());
        assertEquals(new BigDecimal("100.00"), balance(lina));
        assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), 999_999, new BigDecimal("5.00"), "nobody"));
    }

    @Test
    void recipientBalanceLimitIsEnforced() throws Exception {
        execute("UPDATE wallets SET balance = 9999999999999.00 WHERE wallet_id = " + rami.walletId());
        ValidationException e = assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), rami.userId(), new BigDecimal("1.00"), "overflow"));
        assertEquals(TransferService.RECIPIENT_LIMIT_MESSAGE, e.getMessage());
        assertEquals(new BigDecimal("100.00"), balance(lina));
    }

    // ---------- reliability ----------

    @Test
    void failureWritingTheSecondSideRollsBackEverything() throws Exception {
        TransferService failing = new TransferService(database, CLOCK) {
            @Override
            protected long insertTransaction(Connection conn, WalletTransaction t, String requestId)
                    throws SQLException {
                if (WalletTransaction.TYPE_TRANSFER_IN.equals(t.type())) {
                    throw new SQLException("Simulated failure after debit, credit and the sent record");
                }
                return super.insertTransaction(conn, t, requestId);
            }
        };

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> failing.send(lina.userId(), rami.userId(), new BigDecimal("40.00"), "fail"));
        assertEquals(TransferService.FAILED_MESSAGE, e.getMessage());
        assertUnchanged();

        transfers.send(lina.userId(), rami.userId(), new BigDecimal("40.00"), "fail"); // retry succeeds once
        assertEquals(new BigDecimal("60.00"), balance(lina));
        assertEquals(new BigDecimal("40.00"), balance(rami));
    }

    @Test
    void repeatedConfirmationSendsOnce() throws Exception {
        TransferResult first = transfers.send(lina.userId(), rami.userId(), new BigDecimal("20.00"), "same");
        TransferResult second = transfers.send(lina.userId(), rami.userId(), new BigDecimal("20.00"), "same");

        assertTrue(second.alreadyProcessed());
        assertEquals(first.sent(), second.sent());
        assertEquals(new BigDecimal("80.00"), balance(lina));
        assertEquals(new BigDecimal("20.00"), balance(rami));
        assertEquals(3, count("SELECT COUNT(*) FROM wallet_transactions"));
    }

    @Test
    void retryAfterLostResponseDoesNotSendTwiceEvenFromANewConnection() throws Exception {
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("15.00"), "lost-response");
        // The app never saw the answer; it retries with the same request id on a fresh service.
        DatabaseConfig c = database.config();
        TransferService retrying = new TransferService(new Database(new DatabaseConfig(c.url(), c.user(), c.password())));

        TransferResult retry = retrying.send(lina.userId(), rami.userId(), new BigDecimal("15.00"), "lost-response");

        assertTrue(retry.alreadyProcessed());
        assertEquals(new BigDecimal("85.00"), balance(lina));
        assertEquals(new BigDecimal("15.00"), balance(rami));
    }

    @Test
    void requestIdCannotBeReusedWithDifferentDetails() throws Exception {
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("5.00"), "used");
        assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), rami.userId(), new BigDecimal("6.00"), "used"));
        assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), nour.userId(), new BigDecimal("5.00"), "used"));
        topUps.cashTopUp(lina.userId(), new BigDecimal("1.00"), "cash-id");
        assertThrows(ValidationException.class,
                () -> transfers.send(lina.userId(), rami.userId(), new BigDecimal("1.00"), "cash-id"));
        assertEquals(new BigDecimal("96.00"), balance(lina));
    }

    @Test
    void concurrentTransfersCannotOverspendTheSenderWallet() throws Exception {
        // $100 available, 25 concurrent $10 transfers to two recipients: exactly 10 may succeed.
        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long to = i % 2 == 0 ? rami.userId() : nour.userId();
            calls.add(() -> transfers.send(lina.userId(), to, new BigDecimal("10.00"), UUID.randomUUID().toString()));
        }
        BigDecimal totalBefore = totalMoney();
        int ok = 0;
        int insufficient = 0;
        ExecutorService pool = Executors.newFixedThreadPool(25);
        try {
            for (Future<TransferResult> f : pool.invokeAll(calls)) {
                try {
                    f.get();
                    ok++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof ValidationException
                            && e.getCause().getMessage().startsWith("Insufficient funds"), String.valueOf(e.getCause()));
                    insufficient++;
                }
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(10, ok);
        assertEquals(15, insufficient);
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(lina)), "never negative, fully spent");
        assertEquals(new BigDecimal("100.00"), balance(rami).add(balance(nour)));
        assertEquals(totalBefore, totalMoney());
        assertEquals(20, count("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_id IS NOT NULL"));
    }

    @Test
    void simultaneousOppositeTransfersDoNotDeadlockOrLoseMoney() throws Exception {
        topUps.cashTopUp(rami.userId(), new BigDecimal("100.00"), "seed-rami");
        BigDecimal totalBefore = totalMoney();
        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            calls.add(() -> transfers.send(lina.userId(), rami.userId(), new BigDecimal("1.00"), UUID.randomUUID().toString()));
            calls.add(() -> transfers.send(rami.userId(), lina.userId(), new BigDecimal("2.00"), UUID.randomUUID().toString()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            for (Future<TransferResult> f : pool.invokeAll(calls)) {
                f.get(); // a deadlock would surface here as a DatabaseException
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(new BigDecimal("130.00"), balance(lina), "100 - 30x1 + 30x2");
        assertEquals(new BigDecimal("70.00"), balance(rami), "100 + 30x1 - 30x2");
        assertEquals(totalBefore, totalMoney());
        assertEquals(120, count("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_id IS NOT NULL"));
    }

    // ---------- helpers ----------

    private void assertUnchanged() throws Exception {
        assertEquals(new BigDecimal("100.00"), balance(lina));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(rami)));
        assertEquals(0, count("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_id IS NOT NULL"));
    }

    private BigDecimal balance(Registration r) throws Exception {
        return wallets.loadPersonalWallet(r.userId()).balance();
    }

    private BigDecimal totalMoney() throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT SUM(balance) FROM wallets");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
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
