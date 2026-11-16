package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.ExpenseService.Collection;
import com.lamma.wallet.ExpenseService.ExpenseDetails;
import com.lamma.wallet.ExpenseService.NewExpense;
import com.lamma.wallet.ExpenseService.Payment;
import com.lamma.wallet.ExpenseService.Share;
import com.lamma.wallet.ExpenseService.ShareState;
import com.lamma.wallet.ExpenseService.SplitMethod;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Shared expenses against real MySQL (skipped without LAMMA_TEST_DB_*). Lina is the Owner,
 * Rami a Contributor, Nour a Spender, Sami a registered user who isn't a member. Everyone
 * starts with $100.00 in their Personal Wallet.
 */
class ExpenseServiceIntegrationTest {

    private Database database;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private ExpenseService expenses;
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
        SharedWalletService wallets = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        expenses = new ExpenseService(database);
        trip = wallets.create(lina, SharedWalletService.validate("Beirut Trip", null, null, null));
        addMember(rami);
        addMember(nour);
        members.changeRole(lina, trip, nour, MemberRole.SPENDER);
        TopUpService topUps = new TopUpService(database);
        for (long user : new long[] {lina, rami, nour, sami}) {
            topUps.cashTopUp(user, usd("100"), UUID.randomUUID().toString());
        }
    }

    // ---------------- collecting into the shared wallet ----------------

    @Test
    void aPooledExpenseCollectsEachShareIntoTheSharedWallet() throws Exception {
        long hotel = expenses.create(rami, trip, equal("Hotel", "30.00", Collection.SHARED_WALLET, null, lina, rami, nour));
        ExpenseDetails d = expenses.details(lina, hotel);
        assertEquals(List.of("Lina Haddad:10.00:PENDING", "Nour Saad:10.00:PENDING", "Rami Khoury:10.00:PENDING"), shares(d));
        assertEquals(usd("0.00"), shared(), "creating an expense charges nobody");
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
        assertEquals(usd("30.00"), d.summary().outstanding());
        assertTrue(d.canManage(), "the Owner can manage it");
        assertFalse(expenses.details(nour, hotel).canManage(), "a Spender who didn't create it can't");

        ExpenseService.MyRequest mine = expenses.myPendingRequests(rami).get(0);
        assertEquals("Hotel", mine.title());
        assertEquals("Beirut Trip", mine.payToName());
        Payment paid = expenses.pay(rami, mine.requestId());
        assertFalse(paid.alreadyPaid());
        assertEquals(usd("90.00"), paid.myBalanceAfter());
        assertEquals(usd("10.00"), shared());
        assertTrue(expenses.myPendingRequests(rami).isEmpty());
        assertEquals("PAID", value("SELECT status FROM payment_requests WHERE request_id = " + mine.requestId()));
        assertEquals(value("SELECT transaction_id FROM wallet_transactions WHERE request_id = 'expense-request-"
                + mine.requestId() + "'"), value("SELECT transaction_id FROM payment_requests WHERE request_id = " + mine.requestId()),
                "the request is linked to the transfer that paid it");

        WalletTransaction inShared = funds.history(lina, trip, 1).get(0);
        assertEquals(WalletTransaction.KIND_EXPENSE_PAYMENT, inShared.transferKind());
        assertEquals("Hotel", inShared.expenseTitle());
        assertEquals("Rami Khoury", inShared.counterpartyName());
        assertEquals("Hotel", new WalletService(database).loadRecentTransactions(rami, 1).get(0).expenseTitle());

        expenses.pay(lina, expenses.myPendingRequests(lina).get(0).requestId());
        expenses.pay(nour, expenses.myPendingRequests(nour).get(0).requestId());
        ExpenseService.ExpenseSummary s = expenses.list(lina, trip).get(0);
        assertTrue(s.isSettled());
        assertEquals(usd("30.00"), s.collected());
        assertEquals(usd("30.00"), shared());
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
    }

    // ---------------- reimbursing the member who paid ----------------

    @Test
    void aReimbursementPaysThePayerDirectlyAndNeverTouchesTheSharedWallet() throws Exception {
        long dinner = expenses.create(nour, trip, new NewExpense("Dinner", usd("50"), "Paid by card at the restaurant",
                List.of(lina, rami, nour), SplitMethod.CUSTOM, Map.of(lina, usd("20"), rami, usd("18"), nour, usd("12")),
                Collection.REIMBURSEMENT, lina));
        ExpenseDetails d = expenses.details(rami, dinner);
        assertEquals(List.of("Lina Haddad:20.00:COVERED", "Nour Saad:12.00:PENDING", "Rami Khoury:18.00:PENDING"), shares(d));
        assertEquals("Lina Haddad", d.summary().paidByName());
        assertEquals(usd("20.00"), d.summary().covered());
        assertEquals(usd("30.00"), d.summary().outstanding());
        assertEquals("0", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind IS NOT NULL"),
                "the bill paid outside LAMMA is not a LAMMA transaction");

        expenses.pay(rami, expenses.myPendingRequests(rami).get(0).requestId());
        expenses.pay(nour, expenses.myPendingRequests(nour).get(0).requestId());
        assertEquals(usd("130.00"), personal(lina), "reimbursed straight into Lina's Personal Wallet");
        assertEquals(usd("82.00"), personal(rami));
        assertEquals(usd("0.00"), shared(), "no money passed through the shared wallet");
        assertTrue(expenses.details(lina, dinner).summary().isSettled());
        WalletTransaction received = new WalletService(database).loadRecentTransactions(lina, 1).get(0);
        assertEquals(WalletTransaction.KIND_REIMBURSEMENT, received.transferKind());
        assertEquals("Dinner", received.expenseTitle());
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));

        // The payer doesn't have to be a participant (or the creator).
        long taxi = expenses.create(lina, trip, equal("Taxi", "9", Collection.REIMBURSEMENT, nour, lina, rami));
        assertEquals(List.of("Lina Haddad:4.50:PENDING", "Rami Khoury:4.50:PENDING"), shares(expenses.details(lina, taxi)));
        assertEquals("Nour Saad", expenses.myPendingRequests(rami).get(0).payToName());
    }

    // ---------------- declines, revisions and cancelling ----------------

    @Test
    void aDeclinedShareStaysUnresolvedUntilTheCreatorRevisesIt() throws Exception {
        long hotel = expenses.create(lina, trip, equal("Hotel", "30", Collection.SHARED_WALLET, null, lina, rami, nour));
        long nourRequest = request(nour, hotel);
        long ramiRequest = request(rami, hotel);
        expenses.decline(nour, nourRequest);
        assertMessage(ExpenseService.ALREADY_ANSWERED_MESSAGE, () -> expenses.decline(nour, nourRequest));
        assertMessage(ExpenseService.REQUEST_CLOSED_MESSAGE, () -> expenses.pay(nour, nourRequest));
        ExpenseDetails d = expenses.details(lina, hotel);
        assertEquals(List.of("Lina Haddad:10.00:PENDING", "Nour Saad:10.00:DECLINED", "Rami Khoury:10.00:PENDING"), shares(d),
                "a decline is not redistributed silently");
        assertEquals(usd("30.00"), d.summary().outstanding());

        expenses.pay(rami, ramiRequest);
        // Only the unpaid shares can be revised, and they must still add up to the same amount.
        assertMessage("Give a new amount for each unpaid share, and only for those. Paid shares can't be changed.",
                () -> expenses.revise(lina, hotel, Map.of(lina, usd("10"), nour, usd("5"), rami, usd("15"))));
        assertThrows(ValidationException.class, () -> expenses.revise(lina, hotel, Map.of(lina, usd("15"), nour, usd("0"))),
                "adds up to $15, not $20");
        assertThrows(ValidationException.class, () -> expenses.revise(lina, hotel, Map.of(lina, usd("25"), nour, usd("-5"))));
        assertMessage(ExpenseService.MANAGE_ONLY_MESSAGE, () -> expenses.revise(rami, hotel, Map.of(lina, usd("20"), nour, usd("0"))));

        long linaRequest = request(lina, hotel);
        expenses.revise(lina, hotel, Map.of(lina, usd("20"), nour, usd("0")));
        d = expenses.details(lina, hotel);
        assertEquals(List.of("Lina Haddad:20.00:PENDING", "Nour Saad:0.00:NOTHING_OWED", "Rami Khoury:10.00:PAID"), shares(d));
        assertEquals("CANCELLED", value("SELECT status FROM payment_requests WHERE request_id = " + linaRequest),
                "the superseded request is kept as cancelled");
        assertMessage(ExpenseService.REQUEST_CLOSED_MESSAGE, () -> expenses.pay(lina, linaRequest));
        long revised = request(lina, hotel);
        assertEquals(String.valueOf(linaRequest), value("SELECT replaces_request_id FROM payment_requests WHERE request_id = " + revised));
        java.util.List<ExpenseService.RequestRecord> history = expenses.details(lina, hotel).requests();
        assertNull(history.get(0).replacesRequestId(), "an original request replaces nothing");
        assertEquals(Long.valueOf(linaRequest), history.get(history.size() - 1).replacesRequestId());
        assertEquals("DECLINED", value("SELECT status FROM payment_requests WHERE request_id = " + nourRequest), "history kept");
        assertEquals(usd("10.00"), shared(), "the paid share was never altered");
        expenses.pay(lina, revised);
        assertTrue(expenses.details(lina, hotel).summary().isSettled());
        assertEquals(usd("30.00"), shared());
        assertMessage(ExpenseService.NOTHING_TO_REVISE_MESSAGE, () -> expenses.revise(lina, hotel, Map.of()));
    }

    @Test
    void reissuingTheSameAmountAndLettingTheReimbursedPayerAbsorbAShare() throws Exception {
        long dinner = expenses.create(lina, trip, equal("Dinner", "30", Collection.REIMBURSEMENT, lina, lina, rami, nour));
        long nourRequest = request(nour, dinner);
        expenses.decline(nour, nourRequest);
        long ramiRequest = request(rami, dinner);
        // Ask Nour again for the same amount: a new request; Rami's untouched pending request stays.
        expenses.revise(lina, dinner, Map.of(lina, usd("10"), rami, usd("10"), nour, usd("10")));
        assertEquals(ramiRequest, request(rami, dinner));
        long again = request(nour, dinner);
        assertTrue(again != nourRequest);
        expenses.decline(nour, again);
        // Lina (who paid) absorbs Nour's part instead.
        expenses.revise(lina, dinner, Map.of(lina, usd("20"), rami, usd("10"), nour, usd("0")));
        ExpenseDetails d = expenses.details(lina, dinner);
        assertEquals(List.of("Lina Haddad:20.00:COVERED", "Nour Saad:0.00:NOTHING_OWED", "Rami Khoury:10.00:PENDING"), shares(d));
        assertEquals(usd("20.00"), d.summary().covered());
        assertEquals(usd("10.00"), d.summary().outstanding());
    }

    @Test
    void cancellingKeepsPaidSharesAndClosesTheRest() throws Exception {
        long hotel = expenses.create(lina, trip, equal("Hotel", "30", Collection.SHARED_WALLET, null, lina, rami, nour));
        expenses.pay(rami, request(rami, hotel));
        long nourRequest = request(nour, hotel);
        assertMessage(ExpenseService.MANAGE_ONLY_MESSAGE, () -> expenses.cancel(nour, hotel));
        expenses.cancel(lina, hotel);
        ExpenseDetails d = expenses.details(lina, hotel);
        assertTrue(d.summary().isCancelled());
        assertEquals(List.of("Lina Haddad:10.00:CANCELLED", "Nour Saad:10.00:CANCELLED", "Rami Khoury:10.00:PAID"), shares(d));
        assertEquals(usd("10.00"), shared(), "the paid share stays; nothing is refunded automatically");
        assertMessage(ExpenseService.REQUEST_CLOSED_MESSAGE, () -> expenses.pay(nour, nourRequest));
        assertMessage(ExpenseService.EXPENSE_CANCELLED_MESSAGE, () -> expenses.cancel(lina, hotel));
        assertMessage(ExpenseService.EXPENSE_CANCELLED_MESSAGE, () -> expenses.revise(lina, hotel, Map.of()));
        assertTrue(expenses.myPendingRequests(nour).isEmpty());
    }

    // ---------------- authorization and membership changes ----------------

    @Test
    void onlyMembersTakePartAndOnlyThePayerAnswersTheirRequest() throws Exception {
        assertMessage(SharedWalletService.NOT_AVAILABLE_MESSAGE,
                () -> expenses.create(sami, trip, equal("X", "10", Collection.SHARED_WALLET, null, lina)));
        assertMessage("Only current members of the wallet can be participants.",
                () -> expenses.create(lina, trip, equal("X", "10", Collection.SHARED_WALLET, null, lina, sami)));
        assertMessage("The person who paid must be a current member of the wallet.",
                () -> expenses.create(lina, trip, equal("X", "10", Collection.REIMBURSEMENT, sami, lina, rami)));
        long hotel = expenses.create(lina, trip, equal("Hotel", "20", Collection.SHARED_WALLET, null, lina, rami));
        long ramiRequest = request(rami, hotel);
        assertMessage(ExpenseService.REQUEST_NOT_AVAILABLE_MESSAGE, () -> expenses.pay(lina, ramiRequest), "someone else's request");
        assertMessage(ExpenseService.REQUEST_NOT_AVAILABLE_MESSAGE, () -> expenses.decline(sami, ramiRequest));
        assertMessage(ExpenseService.REQUEST_NOT_AVAILABLE_MESSAGE, () -> expenses.pay(rami, 987_654));
        assertMessage(ExpenseService.NOT_AVAILABLE_MESSAGE, () -> expenses.details(sami, hotel));
        assertMessage(SharedWalletService.NOT_AVAILABLE_MESSAGE, () -> expenses.list(sami, trip));
        assertMessage(ExpenseService.NOT_AVAILABLE_MESSAGE, () -> expenses.revise(sami, hotel, Map.of()));
        assertEquals(usd("0.00"), shared());
    }

    @Test
    void aRemovedMemberCantPayAndTheirRequestIsKeptForRevision() throws Exception {
        long hotel = expenses.create(lina, trip, equal("Hotel", "30", Collection.SHARED_WALLET, null, lina, rami, nour));
        long nourRequest = request(nour, hotel);
        members.removeMember(lina, trip, nour);
        assertMessage(ExpenseService.NOT_A_MEMBER_NOW_MESSAGE, () -> expenses.pay(nour, nourRequest));
        assertTrue(expenses.myPendingRequests(nour).isEmpty(), "not offered outside the wallet any more");
        Share nourShare = expenses.details(lina, hotel).shares().get(1);
        assertEquals("Nour Saad", nourShare.name());
        assertFalse(nourShare.stillMember());
        assertEquals(ShareState.PENDING, nourShare.state(), "the history is kept");
        assertThrows(ValidationException.class,
                () -> expenses.revise(lina, hotel, Map.of(lina, usd("10"), rami, usd("10"), nour, usd("10"))),
                "a removed member can't be asked again");
        expenses.revise(lina, hotel, Map.of(lina, usd("15"), rami, usd("15"), nour, usd("0")));
        assertEquals("CANCELLED", value("SELECT status FROM payment_requests WHERE request_id = " + nourRequest));
        assertEquals(usd("100.00"), personal(nour));

        // A reimbursement can't be paid to someone who has left the wallet.
        long taxi = expenses.create(lina, trip, equal("Taxi", "10", Collection.REIMBURSEMENT, rami, lina, rami));
        long linaRequest = request(lina, taxi);
        members.leave(rami, trip);
        assertMessage(ExpenseService.PAYEE_GONE_MESSAGE, () -> expenses.pay(lina, linaRequest));
        assertEquals(usd("100.00"), personal(rami));
    }

    // ---------------- failures, retries and concurrency ----------------

    @Test
    void insufficientFundsLeaveTheRequestOpenAndARetryWorks() throws Exception {
        long villa = expenses.create(lina, trip, equal("Villa", "330", Collection.SHARED_WALLET, null, lina, rami, nour));
        long ramiRequest = request(rami, villa);
        ValidationException e = assertThrows(ValidationException.class, () -> expenses.pay(rami, ramiRequest));
        assertTrue(e.getMessage().startsWith("Insufficient funds. Your available balance is $100.00."));
        assertEquals("PENDING", value("SELECT status FROM payment_requests WHERE request_id = " + ramiRequest));
        new TopUpService(database).cashTopUp(rami, usd("50"), UUID.randomUUID().toString());
        expenses.pay(rami, ramiRequest);
        assertEquals(usd("40.00"), personal(rami));
        assertEquals(usd("110.00"), shared());
    }

    @Test
    void aFailureHalfwayRollsBackAndTheRequestStaysPayable() throws Exception {
        long hotel = expenses.create(lina, trip, equal("Hotel", "20", Collection.REIMBURSEMENT, lina, lina, rami));
        long ramiRequest = request(rami, hotel);
        ExpenseService failing = new ExpenseService(database) {
            @Override
            protected long insertTransaction(Connection conn, WalletTransaction t, String requestId, long by)
                    throws SQLException {
                if (WalletTransaction.TYPE_TRANSFER_IN.equals(t.type())) {
                    throw new SQLException("simulated failure on the second row");
                }
                return super.insertTransaction(conn, t, requestId, by);
            }
        };
        assertThrows(DatabaseException.class, () -> failing.pay(rami, ramiRequest));
        assertEquals("PENDING", value("SELECT status FROM payment_requests WHERE request_id = " + ramiRequest));
        assertEquals(usd("100.00"), personal(rami));
        assertEquals(usd("100.00"), personal(lina));
        assertEquals("0", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind IS NOT NULL"));
        assertFalse(expenses.pay(rami, ramiRequest).alreadyPaid(), "the retry is the first real payment");
        assertEquals(usd("110.00"), personal(lina));
    }

    @Test
    void payingTwiceOrManyTimesAtOnceCollectsOnce() throws Exception {
        long hotel = expenses.create(lina, trip, equal("Hotel", "30", Collection.SHARED_WALLET, null, lina, rami, nour));
        long ramiRequest = request(rami, hotel);
        assertFalse(expenses.pay(rami, ramiRequest).alreadyPaid());
        Payment again = expenses.pay(rami, ramiRequest);
        assertTrue(again.alreadyPaid());
        assertNull(again.myBalanceAfter());
        assertMessage(ExpenseService.ALREADY_ANSWERED_MESSAGE, () -> expenses.decline(rami, ramiRequest), "paid is final");

        long nourRequest = request(nour, hotel);
        List<Object> outcomes = concurrently(10, i -> expenses.pay(nour, nourRequest));
        long real = outcomes.stream().filter(o -> o instanceof Payment p && !p.alreadyPaid()).count();
        long repeats = outcomes.stream().filter(o -> o instanceof Payment p && p.alreadyPaid()).count();
        assertEquals(1, real, String.valueOf(outcomes));
        assertEquals(9, repeats, String.valueOf(outcomes));
        assertEquals(usd("90.00"), personal(nour));
        assertEquals(usd("20.00"), shared());
        assertEquals("2", value("SELECT COUNT(*) FROM wallet_transactions WHERE transfer_kind = 'EXPENSE_PAYMENT'"
                + " AND transaction_type = 'TRANSFER_OUT'"));
    }

    @Test
    void payingWhileTheExpenseIsCancelledOrRevisedStaysConsistent() throws Exception {
        for (int r = 0; r < 8; r++) {
            int round = r;
            long expense = expenses.create(lina, trip, equal("Round " + round, "2", Collection.SHARED_WALLET, null, lina, rami));
            long ramiRequest = request(rami, expense);
            List<Object> outcomes = concurrently(2, i -> {
                if (i == 0) {
                    return expenses.pay(rami, ramiRequest);
                }
                if (round % 2 == 0) {
                    expenses.cancel(lina, expense);
                } else {
                    expenses.revise(lina, expense, Map.of(lina, usd("2"), rami, usd("0")));
                }
                return "changed";
            });
            String status = value("SELECT status FROM payment_requests WHERE request_id = " + ramiRequest);
            if (outcomes.get(0) instanceof Payment) {
                assertEquals("PAID", status);
                assertEquals("1", value("SELECT COUNT(*) FROM wallet_transactions WHERE request_id = 'expense-request-"
                        + ramiRequest + "'"));
            } else {
                assertEquals("CANCELLED", status, String.valueOf(outcomes));
                assertTrue(outcomes.get(0) instanceof ValidationException, String.valueOf(outcomes));
                assertEquals("0", value("SELECT COUNT(*) FROM wallet_transactions WHERE request_id = 'expense-request-"
                        + ramiRequest + "'"));
            }
            if (outcomes.get(1) instanceof Exception other) {
                // A revision after the payment must fail cleanly (the paid share can't be revised).
                assertTrue(other instanceof ValidationException, String.valueOf(outcomes));
                assertEquals("PAID", status);
            }
        }
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
    }

    @Test
    void aCancellationInProgressIsWaitedForAndThenRespected() throws Exception {
        // A reimbursement: the money would go to Lina's Personal Wallet, so only the shared
        // wallet's lock (taken by every expense operation) puts the payment after the cancellation.
        long hotel = expenses.create(lina, trip, equal("Hotel", "20", Collection.REIMBURSEMENT, lina, lina, rami));
        long ramiRequest = request(rami, hotel);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection admin = database.getConnection()) {
            // Exactly what cancel() does, held open: lock the shared wallet and the expense, close the request.
            admin.setAutoCommit(false);
            admin.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            exec(admin, "SELECT wallet_id FROM wallets WHERE wallet_id = " + trip + " FOR UPDATE");
            exec(admin, "SELECT expense_id FROM expenses WHERE expense_id = " + hotel + " FOR UPDATE");
            exec(admin, "UPDATE payment_requests SET status = 'CANCELLED', responded_at = UTC_TIMESTAMP()"
                    + " WHERE expense_id = " + hotel + " AND status = 'PENDING'");
            exec(admin, "UPDATE expenses SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP() WHERE expense_id = " + hotel);

            Future<Payment> payment = pool.submit(() -> expenses.pay(rami, ramiRequest));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> payment.get(700, TimeUnit.MILLISECONDS),
                    "the payment must wait for the cancellation");
            admin.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> payment.get(10, TimeUnit.SECONDS));
            assertEquals(ExpenseService.REQUEST_CLOSED_MESSAGE, e.getCause().getMessage(),
                    "after waiting, the payment sees the cancellation and refuses cleanly");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(usd("100.00"), personal(rami));
        assertEquals(usd("100.00"), personal(lina));
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    @Test
    void expensePaymentsAndOtherTransfersAtOnceDoNotDeadlock() throws Exception {
        funds.contribute(lina, trip, usd("40"), UUID.randomUUID().toString());
        long a = expenses.create(lina, trip, equal("A", "30", Collection.SHARED_WALLET, null, lina, rami, nour));
        long b = expenses.create(lina, trip, equal("B", "30", Collection.REIMBURSEMENT, nour, lina, rami, nour));
        List<Long> toPay = List.of(request(lina, a), request(rami, a), request(nour, a), request(lina, b), request(rami, b));
        long[] payers = {lina, rami, nour, lina, rami};
        TransferService personalSend = new TransferService(database);
        List<Object> outcomes = concurrently(15, i -> switch (i % 3) {
            case 0 -> i / 3 < toPay.size() ? expenses.pay(payers[i / 3], toPay.get(i / 3)) : "skip";
            case 1 -> funds.withdraw(nour, trip, usd("1"), UUID.randomUUID().toString());
            default -> personalSend.send(rami, lina, usd("1"), UUID.randomUUID().toString());
        });
        for (Object o : outcomes) {
            assertFalse(o instanceof Exception, "every operation should succeed, got " + o);
        }
        assertEquals("400.00", value("SELECT SUM(balance) FROM wallets"));
        assertTrue(expenses.details(lina, a).summary().isSettled());
        assertTrue(expenses.details(lina, b).summary().isSettled());
    }

    // ---------------- helpers ----------------

    private NewExpense equal(String title, String total, Collection collection, Long paidBy, long... participants) {
        List<Long> ids = new ArrayList<>();
        for (long p : participants) {
            ids.add(p);
        }
        return new NewExpense(title, usd(total), null, ids, SplitMethod.EQUAL, null, collection, paidBy);
    }

    private long request(long payer, long expenseId) throws Exception {
        return Long.parseLong(value("SELECT request_id FROM payment_requests WHERE payer_id = " + payer
                + " AND expense_id = " + expenseId + " ORDER BY request_id DESC LIMIT 1"));
    }

    private static List<String> shares(ExpenseDetails d) {
        return d.shares().stream().map(s -> s.name() + ":" + s.amount() + ":" + s.state()).toList();
    }

    private void addMember(long userId) throws Exception {
        String phone = value("SELECT phone_number FROM users WHERE user_id = " + userId);
        members.inviteByPhone(lina, trip, "+" + phone);
        members.acceptInvitation(userId, members.incomingInvitations(userId).get(0).invitationId());
    }

    private BigDecimal shared() throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE wallet_id = " + trip));
    }

    private BigDecimal personal(long user) throws Exception {
        return new BigDecimal(value("SELECT balance FROM wallets WHERE owner_user_id = " + user));
    }

    private static BigDecimal usd(String amount) {
        return new BigDecimal(amount).setScale(2);
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

    private String value(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
