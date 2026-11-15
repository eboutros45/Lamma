package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.SharedWalletFundsService.Result;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Shared-wallet contributions, withdrawals, sends and history against real MySQL
 * (skipped without LAMMA_TEST_DB_*). Lina is the Owner, Rami a Contributor, Nour a Spender,
 * and Sami a registered user who is not a member.
 */
class SharedWalletFundsIntegrationTest {

    private Database database;
    private SharedWalletService wallets;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private TopUpService topUps;
    private WalletService personal;
    private long lina;
    private long rami;
    private long nour;
    private long sami;
    private long trip;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        nour = registration.registerPersonal("Nour", "Saad", "71 555 666", "333333", "333333").userId();
        sami = registration.registerPersonal("Sami", "Aoun", "76 999 000", "444444", "444444").userId();
        wallets = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        topUps = new TopUpService(database);
        personal = new WalletService(database);
        trip = wallets.create(lina, SharedWalletService.validate("Beirut Trip", null, null, "1000"));
        addMember(rami);
        addMember(nour);
        members.changeRole(lina, trip, nour, MemberRole.SPENDER);
        for (long user : new long[] {lina, rami, nour, sami}) {
            topUps.cashTopUp(user, new BigDecimal("100.00"), UUID.randomUUID().toString());
        }
    }

    // ---------------- contributions ----------------

    @Test
    void everyRoleCanContributeAndBothSidesAreRecorded() throws Exception {
        Result r = funds.contribute(lina, trip, usd("50"), id());
        assertFalse(r.alreadyProcessed());
        assertEquals(usd("50.00"), r.sharedBalanceAfter());
        assertEquals(usd("50.00"), r.myBalanceAfter());
        funds.contribute(rami, trip, usd("20.25"), id());
        funds.contribute(nour, trip, usd("4.75"), id());

        assertEquals(usd("75.00"), sharedBalance());
        assertEquals(usd("50.00"), personalBalance(lina));
        assertEquals(usd("79.75"), personalBalance(rami));
        assertEquals(usd("95.25"), personalBalance(nour));
        assertTotal("400.00");

        List<WalletTransaction> history = funds.history(rami, trip, 50);
        assertEquals(3, history.size(), "one row per contribution on the shared wallet");
        WalletTransaction newest = history.get(0);
        assertEquals(WalletTransaction.KIND_CONTRIBUTION, newest.transferKind());
        assertTrue(newest.isCredit());
        assertEquals("Nour Saad", newest.counterpartyName());
        assertEquals("Nour Saad", newest.initiatedByName());
        assertEquals(usd("75.00"), newest.balanceAfter());

        WalletTransaction mine = personal.loadRecentTransactions(lina, 10).get(0);
        assertEquals(WalletTransaction.KIND_CONTRIBUTION, mine.transferKind());
        assertFalse(mine.isCredit());
        assertEquals("Beirut Trip", mine.counterpartyName(), "personal history names the shared wallet");
        assertEquals(mine.transferId(), history.get(2).transferId(), "both rows share one transfer id");
    }

    @Test
    void contributionNeedsEnoughPersonalFundsAndAValidAmount() throws Exception {
        assertMessage("Insufficient funds. Your available balance is $100.00.",
                () -> funds.contribute(rami, trip, usd("100.01"), id()));
        for (String bad : new String[] {"0", "-5", "0.001", "10000.01"}) {
            assertThrows(ValidationException.class, () -> funds.contribute(rami, trip, new BigDecimal(bad), id()), bad);
        }
        assertThrows(ValidationException.class, () -> funds.contribute(rami, trip, null, id()));
        assertNothingMoved();
    }

    // ---------------- withdrawals and sends ----------------

    @Test
    void ownerAndSpenderCanWithdrawToTheirOwnWallet() throws Exception {
        funds.contribute(rami, trip, usd("60"), id());
        Result owner = funds.withdraw(lina, trip, usd("10"), id());
        assertEquals(usd("50.00"), owner.sharedBalanceAfter());
        assertEquals(usd("110.00"), owner.myBalanceAfter());
        Result spender = funds.withdraw(nour, trip, usd("15.50"), id());
        assertEquals(usd("34.50"), spender.sharedBalanceAfter());
        assertEquals(usd("115.50"), personalBalance(nour));
        assertTotal("400.00");

        WalletTransaction row = funds.history(rami, trip, 1).get(0);
        assertEquals(WalletTransaction.KIND_WITHDRAWAL, row.transferKind());
        assertFalse(row.isCredit());
        assertEquals("Nour Saad", row.counterpartyName());
        WalletTransaction nourRow = personal.loadRecentTransactions(nour, 1).get(0);
        assertTrue(nourRow.isCredit());
        assertEquals("Beirut Trip", nourRow.counterpartyName());

        assertMessage("Insufficient funds in “Beirut Trip”. Its balance is $34.50.",
                () -> funds.withdraw(lina, trip, usd("34.51"), id()));
    }

    @Test
    void ownerAndSpenderCanSendToAnyRegisteredUser() throws Exception {
        funds.contribute(lina, trip, usd("90"), id());
        TransferService.Recipient outsider = funds.findRecipient(nour, trip, "+961 76 999 000");
        assertEquals("Sami Aoun", outsider.fullName());
        Result r = funds.send(nour, trip, outsider.userId(), usd("15"), id());
        assertNull(r.myBalanceAfter(), "the sender's own personal wallet is untouched");
        assertEquals("Sami Aoun", r.otherName());
        assertEquals(usd("115.00"), personalBalance(sami));
        assertEquals(usd("100.00"), personalBalance(nour));

        funds.send(lina, trip, rami, usd("5"), id());                    // to another member
        TransferService.Recipient self = funds.findRecipient(nour, trip, "71 555 666");
        Result toSelf = funds.send(nour, trip, self.userId(), usd("1"), id()); // to own personal wallet
        assertEquals(usd("101.00"), toSelf.myBalanceAfter());
        assertEquals(usd("69.00"), sharedBalance());
        assertTotal("400.00");

        WalletTransaction sent = funds.history(rami, trip, 50).get(2);
        assertEquals(WalletTransaction.KIND_SHARED_SEND, sent.transferKind());
        assertEquals("Sami Aoun", sent.counterpartyName());
        assertEquals("Nour Saad", sent.initiatedByName(), "who sent it is kept");
        WalletTransaction received = personal.loadRecentTransactions(sami, 1).get(0);
        assertEquals("Beirut Trip", received.counterpartyName());
        assertEquals("Nour Saad", received.initiatedByName());

        assertMessage(TransferService.NOT_FOUND_MESSAGE, () -> funds.findRecipient(lina, trip, "03 999 999"));
        assertThrows(ValidationException.class, () -> funds.findRecipient(lina, trip, "12ab"));
        assertMessage(SharedWalletFundsService.RECIPIENT_UNAVAILABLE_MESSAGE,
                () -> funds.send(lina, trip, 999_999, usd("1"), id()));
        assertMessage("Insufficient funds in “Beirut Trip”. Its balance is $69.00.",
                () -> funds.send(lina, trip, sami, usd("69.01"), id()));
    }

    @Test
    void contributorCannotWithdrawOrSendEvenThroughTheService() throws Exception {
        funds.contribute(rami, trip, usd("40"), id());
        BigDecimal before = sharedBalance();
        assertMessage(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE,
                () -> funds.withdraw(rami, trip, usd("1"), id()));
        assertMessage(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE,
                () -> funds.send(rami, trip, sami, usd("1"), id()));
        assertMessage(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE,
                () -> funds.send(rami, trip, rami, usd("1"), id()), "not even to himself");
        assertMessage(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE,
                () -> funds.findRecipient(rami, trip, "76 999 000"));
        assertEquals(before, sharedBalance());
        assertEquals(1, funds.history(rami, trip, 50).size());

        members.changeRole(lina, trip, nour, MemberRole.CONTRIBUTOR); // demoted: refused at once
        assertMessage(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE,
                () -> funds.withdraw(nour, trip, usd("1"), id()));
        members.changeRole(lina, trip, rami, MemberRole.SPENDER);     // promoted: allowed at once
        funds.withdraw(rami, trip, usd("1"), id());
    }

    @Test
    void outsidersPendingInviteesAndRemovedMembersHaveNoAccess() throws Exception {
        funds.contribute(lina, trip, usd("30"), id());
        members.inviteByPhone(lina, trip, "76 999 000"); // Sami: invited, not yet accepted
        members.removeMember(lina, trip, nour);           // Nour: removed Spender
        for (long user : new long[] {sami, nour}) {
            assertDenied(() -> funds.contribute(user, trip, usd("1"), id()));
            assertDenied(() -> funds.withdraw(user, trip, usd("1"), id()));
            assertDenied(() -> funds.send(user, trip, user, usd("1"), id()));
            assertDenied(() -> funds.findRecipient(user, trip, "03 123 456"));
            assertDenied(() -> funds.history(user, trip, 10));
            assertDenied(() -> funds.overview(user, trip));
        }
        long personalWalletOfLina = Long.parseLong(value(
                "SELECT wallet_id FROM wallets WHERE owner_user_id = " + lina));
        assertDenied(() -> funds.contribute(lina, personalWalletOfLina, usd("1"), id()));
        assertDenied(() -> funds.withdraw(lina, personalWalletOfLina, usd("1"), id()));
        assertDenied(() -> funds.overview(lina, 987_654));
        assertEquals(usd("30.00"), sharedBalance());
        assertTotal("400.00");
    }

    // ---------------- idempotency ----------------

    @Test
    void aRepeatedRequestMovesMoneyOnlyOnce() throws Exception {
        String request = id();
        Result first = funds.contribute(rami, trip, usd("25"), request);
        Result again = funds.contribute(rami, trip, usd("25"), request);
        assertTrue(again.alreadyProcessed());
        assertEquals(first.sharedSide().transactionId(), again.sharedSide().transactionId());
        assertEquals(usd("25.00"), again.sharedBalanceAfter());
        assertEquals(usd("75.00"), again.myBalanceAfter());
        assertEquals(usd("25.00"), sharedBalance());

        String withdrawal = id();
        funds.withdraw(nour, trip, usd("5"), withdrawal);
        assertTrue(funds.withdraw(nour, trip, usd("5"), withdrawal).alreadyProcessed());
        String send = id();
        funds.send(lina, trip, sami, usd("5"), send);
        assertTrue(funds.send(lina, trip, sami, usd("5"), send).alreadyProcessed());
        assertEquals(usd("15.00"), sharedBalance());

        // Same id with different details, or by someone else: refused, nothing moves.
        assertMessage(SharedWalletFundsService.REQUEST_REUSED_MESSAGE, () -> funds.contribute(rami, trip, usd("26"), request));
        assertMessage(SharedWalletFundsService.REQUEST_REUSED_MESSAGE, () -> funds.contribute(lina, trip, usd("25"), request));
        assertMessage(SharedWalletFundsService.REQUEST_REUSED_MESSAGE, () -> funds.withdraw(rami, trip, usd("25"), request));
        // Distinct operations with the same amount are allowed.
        funds.contribute(rami, trip, usd("25"), id());
        assertEquals(usd("40.00"), sharedBalance());
        assertEquals("8", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind IS NOT NULL"),
                "4 operations, 2 rows each");
        assertTotal("400.00");
    }

    @Test
    void theSameRequestSubmittedConcurrentlyRunsOnce() throws Exception {
        String request = id();
        List<Result> results = runConcurrently(8, i -> funds.contribute(rami, trip, usd("10"), request));
        assertEquals(1, results.stream().filter(r -> !r.alreadyProcessed()).count());
        assertEquals(usd("10.00"), sharedBalance());
        assertEquals(usd("90.00"), personalBalance(rami));
    }

    // ---------------- concurrency ----------------

    @Test
    void concurrentWithdrawalsAndSendsCannotOverdraw() throws Exception {
        funds.contribute(lina, trip, usd("100"), id());
        List<Object> outcomes = runConcurrentlyCollecting(12, i -> switch (i % 3) {
            case 0 -> funds.withdraw(lina, trip, usd("30"), id());
            case 1 -> funds.withdraw(nour, trip, usd("30"), id());
            default -> funds.send(nour, trip, sami, usd("30"), id());
        });
        long succeeded = outcomes.stream().filter(o -> o instanceof Result).count();
        for (Object o : outcomes) {
            if (!(o instanceof Result)) {
                assertTrue(o instanceof ValidationException v && v.getMessage().startsWith("Insufficient funds"),
                        "only insufficient-funds refusals, got " + o);
            }
        }
        assertEquals(3, succeeded, "$100 allows exactly three $30 withdrawals");
        assertEquals(usd("10.00"), sharedBalance());
        assertEquals("0", value("SELECT COUNT(*) FROM wallets WHERE balance < 0"));
        assertTotal("400.00");
    }

    @Test
    void transfersInBothDirectionsAtOnceDoNotDeadlock() throws Exception {
        funds.contribute(lina, trip, usd("50"), id());
        TransferService personalSend = new TransferService(database);
        List<Object> outcomes = runConcurrentlyCollecting(24, i -> switch (i % 4) {
            case 0 -> funds.contribute(lina, trip, usd("1"), id());
            case 1 -> funds.withdraw(lina, trip, usd("1"), id());
            case 2 -> personalSend.send(lina, nour, usd("1"), id());
            default -> funds.send(nour, trip, lina, usd("1"), id());
        });
        for (Object o : outcomes) {
            assertFalse(o instanceof Exception, "every transfer should succeed, got " + o);
        }
        assertTotal("400.00");
        // 6 contributions in, 6 withdrawals out, 6 sends out of the shared wallet.
        assertEquals(usd("44.00"), sharedBalance());
    }

    @Test
    void aRoleChangeInProgressIsWaitedForAndThenRespected() throws Exception {
        funds.contribute(lina, trip, usd("50"), id());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection admin = database.getConnection()) {
            // Simulate a membership change holding the wallet lock (as the membership service does).
            admin.setAutoCommit(false);
            exec(admin, "SELECT wallet_id FROM wallets WHERE wallet_id = " + trip + " FOR UPDATE");
            exec(admin, "UPDATE shared_wallet_members SET member_role = 'CONTRIBUTOR' WHERE user_id = " + nour);

            Future<Result> withdrawal = pool.submit(() -> funds.withdraw(nour, trip, usd("5"), id()));
            assertThrows(TimeoutException.class, () -> withdrawal.get(700, TimeUnit.MILLISECONDS),
                    "the withdrawal must wait for the wallet lock");
            admin.commit();

            ExecutionException e = assertThrows(ExecutionException.class, () -> withdrawal.get(10, TimeUnit.SECONDS));
            assertEquals(SharedWalletFundsService.CONTRIBUTOR_CANNOT_SPEND_MESSAGE, e.getCause().getMessage(),
                    "the role is read after the lock, so the demotion counts");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(usd("50.00"), sharedBalance());
    }

    @Test
    void removalAndWithdrawalAtTheSameMomentStayConsistent() throws Exception {
        for (int round = 0; round < 8; round++) {
            funds.contribute(lina, trip, usd("2"), id());
            if (round > 0) {
                addMember(nour);
                members.changeRole(lina, trip, nour, MemberRole.SPENDER);
            }
            BigDecimal before = sharedBalance();
            List<Object> outcomes = runConcurrentlyCollecting(2, i -> {
                if (i == 0) {
                    return funds.withdraw(nour, trip, usd("1"), id());
                }
                members.removeMember(lina, trip, nour);
                return "removed";
            });
            assertEquals("removed", outcomes.get(1), "the removal always succeeds");
            if (outcomes.get(0) instanceof Result) {
                assertEquals(before.subtract(usd("1")), sharedBalance());
            } else {
                assertEquals(SharedWalletService.NOT_AVAILABLE_MESSAGE, ((Exception) outcomes.get(0)).getMessage());
                assertEquals(before, sharedBalance());
            }
            assertDenied(() -> funds.withdraw(nour, trip, usd("1"), id()));
        }
        assertTotal("400.00");
    }

    // ---------------- integrity ----------------

    @Test
    void aFailureHalfwayRollsEverythingBack() throws Exception {
        funds.contribute(lina, trip, usd("40"), id());
        int rowsBefore = Integer.parseInt(value("SELECT COUNT(*) FROM wallet_transactions"));
        SharedWalletFundsService failing = new SharedWalletFundsService(database) {
            @Override
            protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long by)
                    throws SQLException {
                if (WalletTransaction.TYPE_TRANSFER_IN.equals(t.type())) {
                    throw new SQLException("simulated failure on the second row");
                }
                return super.insertTransaction(conn, t, requestId, by);
            }
        };
        String request = id();
        assertThrows(DatabaseException.class, () -> failing.contribute(rami, trip, usd("10"), request));
        assertThrows(DatabaseException.class, () -> failing.withdraw(lina, trip, usd("10"), id()));
        assertThrows(DatabaseException.class, () -> failing.send(nour, trip, sami, usd("10"), id()));
        assertEquals(usd("40.00"), sharedBalance());
        assertEquals(usd("100.00"), personalBalance(rami));
        assertEquals(usd("60.00"), personalBalance(lina));
        assertEquals(usd("100.00"), personalBalance(sami));
        assertEquals(String.valueOf(rowsBefore), value("SELECT COUNT(*) FROM wallet_transactions"));
        // The failed request id was not used up: retrying it works once.
        assertFalse(funds.contribute(rami, trip, usd("10"), request).alreadyProcessed());
        assertEquals(usd("50.00"), sharedBalance());
    }

    @Test
    void everythingPersistsAndPersonalFeaturesStillWork() throws Exception {
        funds.contribute(lina, trip, usd("30"), id());
        funds.withdraw(nour, trip, usd("10"), id());
        new TransferService(database).send(rami, sami, usd("7"), id());

        DatabaseConfig c = database.config();
        SharedWalletFundsService afterRestart = new SharedWalletFundsService(
                new Database(new DatabaseConfig(c.url(), c.user(), c.password())));
        SharedWalletFundsService.Overview o = afterRestart.overview(rami, trip);
        assertEquals("Beirut Trip", o.walletName());
        assertEquals(usd("20.00"), o.sharedBalance());
        assertEquals(MemberRole.CONTRIBUTOR, o.myRole());
        assertEquals(usd("93.00"), o.myPersonalBalance());
        assertEquals(List.of("WITHDRAWAL:DEBIT", "CONTRIBUTION:CREDIT"), afterRestart.history(rami, trip, 10).stream()
                .map(t -> t.transferKind() + ":" + t.direction()).toList());
        assertEquals(usd("20.00"), wallets.details(rami, trip).wallet().balance(), "target progress uses this");

        WalletTransaction ramiSent = personal.loadRecentTransactions(rami, 1).get(0);
        assertNull(ramiSent.transferKind(), "personal Send Money is unchanged");
        assertEquals("Sami Aoun", ramiSent.counterpartyName());
        assertEquals(List.of("TRANSFER_IN", "CASH_TOP_UP"), personal.loadRecentTransactions(sami, 10).stream()
                .map(WalletTransaction::type).toList());
        assertTotal("400.00");
    }

    // ---------------- helpers ----------------

    private void addMember(long userId) throws Exception {
        String phone = value("SELECT phone_number FROM users WHERE user_id = " + userId);
        members.inviteByPhone(lina, trip, "+" + phone);
        members.acceptInvitation(userId, members.incomingInvitations(userId).get(0).invitationId());
    }

    private void assertNothingMoved() throws Exception {
        assertEquals(usd("0.00"), sharedBalance());
        assertEquals("0", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind IS NOT NULL"));
        assertTotal("400.00");
    }

    private void assertTotal(String expected) throws Exception {
        assertEquals(expected, value("SELECT SUM(balance) FROM wallets"), "total money must not change");
    }

    private BigDecimal sharedBalance() throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE wallet_id = " + trip));
    }

    private BigDecimal personalBalance(long userId) throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE owner_user_id = " + userId));
    }

    private static BigDecimal usd(String amount) {
        return new BigDecimal(amount);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private interface Action {
        void run() throws Exception;
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private static void assertDenied(Action action) {
        assertMessage(SharedWalletService.NOT_AVAILABLE_MESSAGE, action);
    }

    private static void assertMessage(String expected, Action action) {
        assertMessage(expected, action, "");
    }

    private static void assertMessage(String expected, Action action, String why) {
        ValidationException e = assertThrows(ValidationException.class, action::run, why);
        assertEquals(expected, e.getMessage(), why);
    }

    /** Runs the tasks at the same moment; every task must succeed. */
    private static <T> List<T> runConcurrently(int count, Task<T> task) throws Exception {
        List<T> results = new ArrayList<>();
        for (Object o : runConcurrentlyCollecting(count, task)) {
            if (o instanceof Exception e) {
                fail("unexpected failure", e);
            }
            @SuppressWarnings("unchecked")
            T t = (T) o;
            results.add(t);
        }
        return results;
    }

    /** Runs the tasks at the same moment; returns each result or the exception it threw, in order. */
    private static List<Object> runConcurrentlyCollecting(int count, Task<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Object> c = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    outcomes.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private String value(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
