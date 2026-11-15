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
import java.sql.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Database constraints added by V005 (skipped without LAMMA_TEST_DB_* settings). */
class TransferSchemaIntegrationTest {

    private Database database;
    private long a;
    private long b;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        a = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").walletId();
        b = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").walletId();
    }

    @Test
    void validTransferPairIsAccepted() throws Exception {
        row(a, "TRANSFER_OUT", "DEBIT", "x1", b, "r1");
        row(b, "TRANSFER_IN", "CREDIT", "x1", a, "r2");
    }

    @Test
    void directionMustMatchTransferSide() {
        assertThrows(SQLException.class, () -> row(a, "TRANSFER_OUT", "CREDIT", "x1", b, "r1"));
        assertThrows(SQLException.class, () -> row(b, "TRANSFER_IN", "DEBIT", "x1", a, "r2"));
    }

    @Test
    void transferRowsNeedLinkAndCounterpartyAndOthersMustNotHaveThem() {
        assertThrows(SQLException.class, () -> row(a, "TRANSFER_OUT", "DEBIT", null, b, "r1"));
        assertThrows(SQLException.class, () -> row(a, "TRANSFER_OUT", "DEBIT", "x1", null, "r2"));
        assertThrows(SQLException.class, () -> row(a, "TRANSFER_OUT", "DEBIT", "x1", a, "r3"), "self as counterparty");
        assertThrows(SQLException.class, () -> row(a, "CASH_TOP_UP", "CREDIT", "x1", null, "r4"));
        assertThrows(SQLException.class, () -> row(a, "CASH_TOP_UP", "CREDIT", null, b, "r5"));
    }

    @Test
    void atMostOneRowPerSideOfATransfer() throws Exception {
        row(a, "TRANSFER_OUT", "DEBIT", "x1", b, "r1");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> row(a, "TRANSFER_OUT", "DEBIT", "x1", b, "r2"));
    }

    @Test
    void counterpartyMustBeAnExistingWallet() {
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> row(a, "TRANSFER_OUT", "DEBIT", "x1", 999_999L, "r1"));
    }

    private void row(long wallet, String type, String direction, String transferId, Long counterparty, String requestId)
            throws SQLException, Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (wallet_id,"
                     + " transaction_type, direction, amount, balance_after, status, transfer_id,"
                     + " counterparty_wallet_id, request_id, created_at)"
                     + " VALUES (?, ?, ?, 1.00, 1.00, 'COMPLETED', ?, ?, ?, UTC_TIMESTAMP())")) {
            ps.setLong(1, wallet);
            ps.setString(2, type);
            ps.setString(3, direction);
            ps.setString(4, transferId);
            if (counterparty == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, counterparty);
            }
            ps.setString(6, requestId);
            ps.executeUpdate();
        }
    }
}
