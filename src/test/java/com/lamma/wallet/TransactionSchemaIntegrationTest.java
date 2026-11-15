package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Database constraints on wallet_transactions (skipped without LAMMA_TEST_DB_* settings). */
class TransactionSchemaIntegrationTest {

    private Database database;
    private long walletId;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        walletId = new RegistrationService(database, new PinHasher(1_000))
                .registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").walletId();
    }

    @Test
    void validRowIsAccepted() throws Exception {
        insert(walletId, "CASH_TOP_UP", "CREDIT", "10.00", "10.00", "COMPLETED", "r1");
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "CREDIT", "0.00", "0.00", "COMPLETED", "r1"));
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "CREDIT", "-1.00", "0.00", "COMPLETED", "r2"));
    }

    @Test
    void negativeBalanceAfterIsRejected() {
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "CREDIT", "1.00", "-1.00", "COMPLETED", "r1"));
    }

    @Test
    void unknownTypeDirectionOrStatusIsRejected() {
        assertThrows(SQLException.class, () -> insert(walletId, "CARD_TOP_UP", "CREDIT", "1.00", "1.00", "COMPLETED", "r1"));
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "SIDEWAYS", "1.00", "1.00", "COMPLETED", "r2"));
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "CREDIT", "1.00", "1.00", "PENDING", "r3"));
    }

    @Test
    void cashTopUpMustBeACredit() {
        assertThrows(SQLException.class, () -> insert(walletId, "CASH_TOP_UP", "DEBIT", "1.00", "1.00", "COMPLETED", "r1"));
    }

    @Test
    void requestIdIsUnique() throws Exception {
        insert(walletId, "CASH_TOP_UP", "CREDIT", "1.00", "1.00", "COMPLETED", "same");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insert(walletId, "CASH_TOP_UP", "CREDIT", "1.00", "2.00", "COMPLETED", "same"));
    }

    @Test
    void transactionMustBelongToAnExistingWallet() {
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insert(999_999, "CASH_TOP_UP", "CREDIT", "1.00", "1.00", "COMPLETED", "r1"));
    }

    @Test
    void walletWithTransactionsCannotBeDeleted() throws Exception {
        insert(walletId, "CASH_TOP_UP", "CREDIT", "1.00", "1.00", "COMPLETED", "r1");
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM wallets WHERE wallet_id = ?")) {
            ps.setLong(1, walletId);
            assertThrows(SQLIntegrityConstraintViolationException.class, ps::executeUpdate);
        }
    }

    private void insert(long wallet, String type, String direction, String amount, String balanceAfter,
                        String status, String requestId) throws SQLException, Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions"
                     + " (wallet_id, transaction_type, direction, amount, balance_after, status, request_id, created_at)"
                     + " VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())")) {
            ps.setLong(1, wallet);
            ps.setString(2, type);
            ps.setString(3, direction);
            ps.setBigDecimal(4, new java.math.BigDecimal(amount));
            ps.setBigDecimal(5, new java.math.BigDecimal(balanceAfter));
            ps.setString(6, status);
            ps.setString(7, requestId);
            ps.executeUpdate();
        }
    }
}
