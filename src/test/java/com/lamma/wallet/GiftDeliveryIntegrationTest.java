package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.GiftDeliveryService.Delivery;
import com.lamma.wallet.GiftDeliveryService.Preview;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gift delivery against real MySQL (skipped without LAMMA_TEST_DB_*). Lina is the Owner, Rami a
 * Contributor, Nour a Spender; the gift is for Sami, who is not a member. Everyone starts with $100.
 */
class GiftDeliveryIntegrationTest {

    private Database database;
    private SharedWalletService wallets;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private ExpenseService expenses;
    private GiftDeliveryService gifts;
    private long lina;
    private long rami;
    private long nour;
    private long sami;
    private long gift;

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
        expenses = new ExpenseService(database);
        gifts = new GiftDeliveryService(database);
        gift = wallets.create(lina, SharedWalletService.validate("Sami's Birthday", null, null, "500").asGiftFor(sami));
        addMember(rami);
        addMember(nour);
        members.changeRole(lina, gift, nour, MemberRole.SPENDER);
        TopUpService topUps = new TopUpService(database);
        for (long user : new long[] {lina, rami, nour, sami}) {
            topUps.cashTopUp(user, usd("100"), UUID.randomUUID().toString());
        }
    }

    @Test
    void theOwnerDeliversTheWholeBalanceBelowTheTargetAndTheGiftIsCompleted() throws Exception {
        funds.contribute(lina, gift, usd("40"), id());
        funds.contribute(rami, gift, usd("25"), id());
        Preview p = gifts.preview(lina, gift);
        assertEquals("Sami's Birthday", p.walletName());
        assertEquals("Sami Aoun", p.recipientName());
        assertEquals("96176999000", p.recipientPhone());
        assertEquals(usd("65.00"), p.balance());
        assertEquals(usd("500.00"), p.targetAmount(), "the target is information only");
        assertTrue(p.canDeliver());

        Delivery d = gifts.deliver(lina, gift, usd("65.00"));
        assertFalse(d.alreadyDelivered());
        assertEquals(usd("65.00"), d.amount());
        assertEquals("Sami Aoun", d.recipientName());
        assertEquals(usd("165.00"), personal(sami), "received in Sami's Personal Wallet");
        assertEquals("0.00", value("SELECT balance FROM wallets WHERE wallet_id = " + gift));
        assertEquals("COMPLETED", value("SELECT status FROM wallets WHERE wallet_id = " + gift));
        assertNotNull(value("SELECT completed_at FROM wallets WHERE wallet_id = " + gift));
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"), "total money unchanged");

        WalletTransaction received = new WalletService(database).loadRecentTransactions(sami, 1).get(0);
        assertEquals(WalletTransaction.KIND_GIFT_DELIVERY, received.transferKind());
        assertTrue(received.isCredit());
        assertEquals("Sami's Birthday", received.counterpartyName());
        assertEquals("Lina Haddad", received.initiatedByName());

        // Still visible, read-only, to every member, with its full history.
        SharedWallet.Summary s = wallets.listForMember(rami).get(0);
        assertTrue(s.completed());
        assertTrue(wallets.details(rami, gift).wallet().completed());
        assertEquals(List.of("GIFT_DELIVERY:DEBIT", "CONTRIBUTION:CREDIT", "CONTRIBUTION:CREDIT"),
                funds.history(nour, gift, 10).stream().map(t -> t.transferKind() + ":" + t.direction()).toList());
        Delivery seen = gifts.deliveryFor(rami, gift);
        assertEquals(usd("65.00"), seen.amount());
        assertEquals("Lina Haddad", seen.deliveredByName());
        assertTrue(funds.overview(rami, gift).completed());
        assertTrue(wallets.listForMember(sami).isEmpty(), "the recipient still isn't a member");
    }

    @Test
    void onlyTheOwnerOfAGiftCanSendIt() throws Exception {
        funds.contribute(lina, gift, usd("10"), id());
        assertMessage(GiftDeliveryService.OWNER_ONLY_MESSAGE, () -> gifts.deliver(rami, gift, usd("10")), "Contributor");
        assertMessage(GiftDeliveryService.OWNER_ONLY_MESSAGE, () -> gifts.deliver(nour, gift, usd("10")), "Spender");
        assertMessage(GiftDeliveryService.OWNER_ONLY_MESSAGE, () -> gifts.preview(nour, gift));
        assertMessage(SharedWalletService.NOT_AVAILABLE_MESSAGE, () -> gifts.deliver(sami, gift, usd("10")),
                "the recipient isn't a member and learns nothing");
        long plain = wallets.create(lina, SharedWalletService.validate("Flat", null, null, null));
        assertMessage(GiftDeliveryService.NOT_A_GIFT_MESSAGE, () -> gifts.deliver(lina, plain, usd("1")));
        assertEquals(usd("10.00"), shared());
        assertEquals("ACTIVE", value("SELECT status FROM wallets WHERE wallet_id = " + gift));
    }

    @Test
    void aZeroBalanceOrAChangedBalanceSendsNothing() throws Exception {
        assertFalse(gifts.preview(lina, gift).canDeliver());
        assertMessage(GiftDeliveryService.ZERO_BALANCE_MESSAGE, () -> gifts.deliver(lina, gift, usd("0.00")));
        assertEquals("ACTIVE", value("SELECT status FROM wallets WHERE wallet_id = " + gift));

        funds.contribute(lina, gift, usd("40"), id());
        BigDecimal reviewed = gifts.preview(lina, gift).balance();
        funds.contribute(rami, gift, usd("10"), id()); // arrives after the Owner reviewed $40
        assertMessage(GiftDeliveryService.BALANCE_CHANGED_MESSAGE, () -> gifts.deliver(lina, gift, reviewed));
        assertEquals(usd("100.00"), personal(sami));
        assertEquals(usd("50.00"), gifts.deliver(lina, gift, usd("50.00")).amount());
    }

    @Test
    void anInvalidRecipientCantReceiveTheGift() throws Exception {
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        RegistrationService.Registration ghost = registration.registerPersonal("Ghost", "User", "76 111 222",
                "555555", "555555");
        long other = wallets.create(lina, SharedWalletService.validate("Ghost gift", null, null, null).asGiftFor(ghost.userId()));
        funds.contribute(lina, other, usd("5"), id());
        exec("DELETE FROM wallets WHERE wallet_id = " + ghost.walletId()); // the recipient's wallet is gone
        assertMessage(GiftDeliveryService.RECIPIENT_UNAVAILABLE_MESSAGE, () -> gifts.preview(lina, other));
        assertMessage(GiftDeliveryService.RECIPIENT_UNAVAILABLE_MESSAGE, () -> gifts.deliver(lina, other, usd("5")));
        assertEquals("5.00", value("SELECT balance FROM wallets WHERE wallet_id = " + other));
        assertEquals("ACTIVE", value("SELECT status FROM wallets WHERE wallet_id = " + other));
    }

    @Test
    void aRepeatedOrConcurrentDeliverySendsOnce() throws Exception {
        funds.contribute(lina, gift, usd("30"), id());
        Delivery first = gifts.deliver(lina, gift, usd("30"));
        Delivery again = gifts.deliver(lina, gift, usd("30"));
        assertTrue(again.alreadyDelivered(), "a retry after a lost answer");
        assertEquals(first.transferId(), again.transferId());
        assertEquals(usd("130.00"), personal(sami));

        long second = wallets.create(lina, SharedWalletService.validate("Second gift", null, null, null).asGiftFor(sami));
        funds.contribute(lina, second, usd("20"), id());
        List<Object> outcomes = concurrently(8, i -> gifts.deliver(lina, second, usd("20")));
        assertEquals(1, outcomes.stream().filter(o -> o instanceof Delivery d && !d.alreadyDelivered()).count(),
                String.valueOf(outcomes));
        assertEquals(7, outcomes.stream().filter(o -> o instanceof Delivery d && d.alreadyDelivered()).count());
        assertEquals(usd("150.00"), personal(sami));
        assertEquals("2", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind = 'GIFT_DELIVERY'"
                + " AND transaction_type = 'TRANSFER_OUT'"));
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
    }

    @Test
    void contributionsAndWithdrawalsRacingTheDeliveryNeverLoseOrStrandMoney() throws Exception {
        for (int round = 0; round < 5; round++) {
            long g = wallets.create(lina, SharedWalletService.validate("Race " + round, null, null, null).asGiftFor(sami));
            addMember(nour, g);
            members.changeRole(lina, g, nour, MemberRole.SPENDER);
            funds.contribute(lina, g, usd("5"), id());
            List<Object> outcomes = concurrently(6, i -> switch (i) {
                case 0 -> gifts.deliver(lina, g, usd("5.00"));
                case 1, 2, 3 -> funds.contribute(lina, g, usd("1"), id());
                default -> funds.withdraw(nour, g, usd("1"), id());
            });
            String status = value("SELECT status FROM wallets WHERE wallet_id = " + g);
            if (outcomes.get(0) instanceof Delivery d) {
                assertEquals("COMPLETED", status);
                assertEquals("0.00", value("SELECT balance FROM wallets WHERE wallet_id = " + g),
                        "nothing could land after completion");
                assertEquals(usd("5.00"), d.amount());
                for (int i = 1; i < 6; i++) {
                    if (outcomes.get(i) instanceof ValidationException v) {
                        assertEquals(GiftDeliveryService.COMPLETED_MESSAGE, v.getMessage(), String.valueOf(outcomes));
                    }
                }
            } else {
                assertEquals("ACTIVE", status);
                assertEquals(GiftDeliveryService.BALANCE_CHANGED_MESSAGE, ((Exception) outcomes.get(0)).getMessage(),
                        String.valueOf(outcomes));
            }
            for (Object o : outcomes) {
                assertFalse(o instanceof DatabaseException, "no database errors: " + outcomes);
            }
        }
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
    }

    @Test
    void aFailureHalfwayLeavesTheGiftActiveAndUnchanged() throws Exception {
        funds.contribute(lina, gift, usd("30"), id());
        members.inviteByPhone(lina, gift, "76 999 000");
        GiftDeliveryService failing = new GiftDeliveryService(database) {
            @Override
            protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long by)
                    throws SQLException {
                if (WalletTransaction.TYPE_TRANSFER_IN.equals(t.type())) {
                    throw new SQLException("simulated failure on the second row");
                }
                return super.insertTransaction(conn, t, requestId, by);
            }
        };
        assertThrows(DatabaseException.class, () -> failing.deliver(lina, gift, usd("30")));
        assertEquals("ACTIVE", value("SELECT status FROM wallets WHERE wallet_id = " + gift));
        assertEquals(usd("30.00"), shared());
        assertEquals(usd("100.00"), personal(sami));
        assertEquals("PENDING", value("SELECT status FROM shared_wallet_invitations WHERE invitee_user_id = " + sami), "nothing else changed");
        assertFalse(gifts.deliver(lina, gift, usd("30")).alreadyDelivered(), "the retry is the real delivery");
        assertEquals(usd("130.00"), personal(sami));
    }

    @Test
    void openExpensesBlockDeliveryUntilSettledOrCancelled() throws Exception {
        funds.contribute(lina, gift, usd("20"), id());
        long cake = expenses.create(lina, gift, new ExpenseService.NewExpense("Cake", usd("30"), null, List.of(lina, rami, nour),
                ExpenseService.SplitMethod.EQUAL, null, ExpenseService.Collection.SHARED_WALLET, null));
        long taxi = expenses.create(nour, gift, new ExpenseService.NewExpense("Taxi", usd("10"), null, List.of(lina, nour),
                ExpenseService.SplitMethod.EQUAL, null, ExpenseService.Collection.REIMBURSEMENT, nour));
        Preview p = gifts.preview(lina, gift);
        assertEquals(List.of("Cake", "Taxi"), p.unsettledExpenses());
        assertFalse(p.canDeliver());
        assertMessage(GiftDeliveryService.unsettledMessage(List.of("Cake", "Taxi")), () -> gifts.deliver(lina, gift, usd("20")));
        assertEquals("PENDING", value("SELECT status FROM payment_requests WHERE expense_id = " + cake + " LIMIT 1"),
                "nothing was cancelled or settled silently");

        // Settle one, cancel the other: then the gift can go.
        for (ExpenseService.MyRequest r : expenses.myPendingRequests(lina)) {
            if (r.expenseId() == taxi) {
                expenses.pay(lina, r.requestId());
            }
        }
        expenses.cancel(lina, cake);
        assertTrue(gifts.preview(lina, gift).unsettledExpenses().isEmpty());
        assertEquals(usd("20.00"), gifts.deliver(lina, gift, usd("20")).amount());
    }

    @Test
    void aCompletedGiftIsReadOnlyForMoneyMembershipAndExpenses() throws Exception {
        funds.contribute(lina, gift, usd("10"), id());
        String code = members.newInviteCode(lina, gift);
        members.inviteByPhone(lina, gift, "76 999 000");
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        long tarek = registration.registerPersonal("Tarek", "Nasr", "76 333 444", "666666", "666666").userId();
        members.requestToJoin(tarek, code);
        Preview p = gifts.preview(lina, gift);
        assertEquals(1, p.pendingInvitations());
        assertEquals(1, p.pendingJoinRequests());
        gifts.deliver(lina, gift, usd("10"));

        assertEquals("CANCELLED", value("SELECT status FROM shared_wallet_invitations WHERE invitee_user_id = " + sami));
        assertEquals("REJECTED", value("SELECT status FROM shared_wallet_join_requests"));
        assertMessage(GiftDeliveryService.COMPLETED_MESSAGE, () -> funds.contribute(rami, gift, usd("1"), id()));
        assertMessage(GiftDeliveryService.COMPLETED_MESSAGE, () -> funds.withdraw(lina, gift, usd("1"), id()));
        assertMessage(GiftDeliveryService.COMPLETED_MESSAGE, () -> funds.send(nour, gift, sami, usd("1"), id()));
        assertMessage(GiftDeliveryService.COMPLETED_MESSAGE, () -> expenses.create(rami, gift, new ExpenseService.NewExpense(
                "Late", usd("2"), null, List.of(rami), ExpenseService.SplitMethod.EQUAL, null,
                ExpenseService.Collection.SHARED_WALLET, null)));
        assertThrows(ValidationException.class, () -> members.inviteByPhone(lina, gift, "76 333 444"));
        assertThrows(ValidationException.class, () -> members.changeRole(lina, gift, rami, MemberRole.SPENDER));
        assertThrows(ValidationException.class, () -> members.removeMember(lina, gift, rami));
        assertThrows(ValidationException.class, () -> members.leave(rami, gift));
        assertThrows(ValidationException.class, () -> members.newInviteCode(lina, gift));
        assertThrows(ValidationException.class, () -> members.requestToJoin(tarek, code));
        assertThrows(ValidationException.class, () -> gifts.preview(lina, gift), "nothing left to send");
        assertThrows(SQLException.class, () -> exec("UPDATE wallets SET balance = 1.00 WHERE wallet_id = " + gift),
                "the database itself refuses money in a completed wallet");
        assertEquals(List.of("Lina:OWNER", "Nour:SPENDER", "Rami:CONTRIBUTOR"), wallets.details(rami, gift).members()
                .stream().map(m -> m.firstName() + ":" + m.role()).sorted().toList(), "members kept");
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
    }

    @Test
    void theDeliveryPersistsAfterARestart() throws Exception {
        funds.contribute(nour, gift, usd("12.34"), id());
        gifts.deliver(lina, gift, usd("12.34"));
        DatabaseConfig c = database.config();
        Database again = new Database(new DatabaseConfig(c.url(), c.user(), c.password()));
        Delivery d = new GiftDeliveryService(again).deliveryFor(nour, gift);
        assertEquals(usd("12.34"), d.amount());
        assertEquals("Sami Aoun", d.recipientName());
        assertTrue(new SharedWalletService(again).details(nour, gift).wallet().completed());
        assertTrue(new GiftDeliveryService(again).deliver(lina, gift, usd("12.34")).alreadyDelivered());
    }

    // ---------------- helpers ----------------

    private void addMember(long userId) throws Exception {
        addMember(userId, gift);
    }

    private void addMember(long userId, long wallet) throws Exception {
        String phone = value("SELECT phone_number FROM users WHERE user_id = " + userId);
        members.inviteByPhone(lina, wallet, "+" + phone);
        members.acceptInvitation(userId, members.incomingInvitations(userId).get(0).invitationId());
    }

    private BigDecimal shared() throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE wallet_id = " + gift));
    }

    private BigDecimal personal(long user) throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE owner_user_id = " + user));
    }

    private static BigDecimal usd(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private interface Action {
        void run() throws Exception;
    }

    private interface Task {
        Object run(int index) throws Exception;
    }

    private static void assertMessage(String expected, Action action) {
        assertMessage(expected, action, "");
    }

    private static void assertMessage(String expected, Action action, String why) {
        assertEquals(expected, assertThrows(ValidationException.class, action::run, why).getMessage(), why);
    }

    private static List<Object> concurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.run(index);
                }));
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

    private void exec(String sql) throws Exception {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
