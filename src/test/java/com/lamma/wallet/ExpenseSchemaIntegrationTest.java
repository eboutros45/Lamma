package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Database constraints added by V010 (skipped without LAMMA_TEST_DB_* settings). */
class ExpenseSchemaIntegrationTest {

    private Database database;
    private long lina;
    private long rami;
    private long trip;
    private long linaWallet;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        RegistrationService.Registration l = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        lina = l.userId();
        linaWallet = l.walletId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        trip = new SharedWalletService(database).create(lina, SharedWalletService.validate("Trip", null, null, null));
    }

    @Test
    void expensesCheckTheirValues() throws Exception {
        expense("SHARED_WALLET", null, "OPEN", null, "10.00");
        expense("REIMBURSEMENT", lina, "OPEN", null, "10.00");
        assertThrows(SQLException.class, () -> expense("REIMBURSEMENT", null, "OPEN", null, "10.00"), "a reimbursement needs a payer");
        assertThrows(SQLException.class, () -> expense("SHARED_WALLET", lina, "OPEN", null, "10.00"), "pooled has no payer");
        assertThrows(SQLException.class, () -> expense("SHARED_WALLET", null, "OPEN", null, "0.00"), "total > 0");
        assertThrows(SQLException.class, () -> expense("SHARED_WALLET", null, "DONE", null, "10.00"), "unknown status");
        assertThrows(SQLException.class, () -> expense("SHARED_WALLET", null, "CANCELLED", null, "10.00"), "cancelled needs a time");
        assertThrows(SQLException.class, () -> expense("SHARED_WALLET", null, "OPEN", "UTC_TIMESTAMP()", "10.00"));
    }

    @Test
    void oneOpenRequestPerPersonAndPaidOnlyWithATransfer() throws Exception {
        long e = expense("SHARED_WALLET", null, "OPEN", null, "10.00");
        request(e, rami, "PENDING", null, false);
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> request(e, rami, "PENDING", null, false),
                "a second open request for the same person");
        request(e, rami, "DECLINED", null, true);
        request(e, rami, "CANCELLED", null, true);
        request(e, lina, "DECLINED", null, true);
        assertThrows(SQLException.class, () -> request(e, lina, "PAID", null, true), "paid needs its transfer");
        assertThrows(SQLException.class, () -> request(e, lina, "PENDING", null, true), "pending has no answer time");
        assertThrows(SQLException.class, () -> request(e, lina, "DECLINED", null, false), "an answer has a time");
        assertThrows(SQLException.class, () -> request(e, lina, "LATER", null, true));
        assertThrows(SQLException.class, () -> exec("INSERT INTO payment_requests (expense_id, requester_id, payer_id,"
                + " destination_wallet_id, amount, status, created_at) VALUES (" + e + ", " + lina + ", " + rami + ", "
                + trip + ", 0.00, 'PENDING', UTC_TIMESTAMP())"), "amount > 0");
    }

    @Test
    void transferKindsIncludeExpensePayments() throws Exception {
        exec("INSERT INTO wallet_transactions (wallet_id, transaction_type, direction, amount, balance_after, status,"
                + " transfer_id, counterparty_wallet_id, transfer_kind, initiated_by_user_id, request_id, created_at)"
                + " VALUES (" + linaWallet + ", 'TRANSFER_OUT', 'DEBIT', 1.00, 1.00, 'COMPLETED', 'x', " + trip
                + ", 'EXPENSE_PAYMENT', " + lina + ", 'r1', UTC_TIMESTAMP())");
        assertThrows(SQLException.class, () -> exec("INSERT INTO wallet_transactions (wallet_id, transaction_type,"
                + " direction, amount, balance_after, status, transfer_id, counterparty_wallet_id, transfer_kind,"
                + " initiated_by_user_id, request_id, created_at) VALUES (" + linaWallet + ", 'TRANSFER_OUT', 'DEBIT',"
                + " 1.00, 1.00, 'COMPLETED', 'y', " + trip + ", 'REFUND', " + lina + ", 'r2', UTC_TIMESTAMP())"));
    }

    private long expense(String collection, Long paidBy, String status, String cancelledAt, String total) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO expenses (shared_wallet_id, created_by_user_id,"
                     + " title, total_amount, split_method, collection_method, paid_by_user_id, status, expense_date,"
                     + " created_at, cancelled_at) VALUES (?, ?, 'Hotel', " + total + ", 'EQUAL', ?, ?, ?, UTC_DATE(),"
                     + " UTC_TIMESTAMP(), " + (cancelledAt == null ? "NULL" : cancelledAt) + ")",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, trip);
            ps.setLong(2, lina);
            ps.setString(3, collection);
            ps.setObject(4, paidBy);
            ps.setString(5, status);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void request(long expenseId, long payer, String status, Long transactionId, boolean answered) throws Exception {
        exec("INSERT INTO payment_requests (expense_id, requester_id, payer_id, destination_wallet_id, amount, status,"
                + " transaction_id, created_at, responded_at) VALUES (" + expenseId + ", " + lina + ", " + payer + ", "
                + trip + ", 5.00, '" + status + "', " + transactionId + ", UTC_TIMESTAMP(), "
                + (answered ? "UTC_TIMESTAMP()" : "NULL") + ")");
    }

    private void exec(String sql) throws Exception {
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
