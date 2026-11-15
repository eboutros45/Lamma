package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.RegistrationService.Registration;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Database constraints added by V008 (skipped without LAMMA_TEST_DB_* settings). */
class SharedTransferSchemaIntegrationTest {

    private Database database;
    private long walletA;
    private long walletB;
    private long userA;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        Registration a = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        walletA = a.walletId();
        userA = a.userId();
        walletB = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").walletId();
    }

    @Test
    void sharedTransferRowsAreAcceptedWithKindAndInitiator() throws Exception {
        for (String kind : new String[] {"CONTRIBUTION", "WITHDRAWAL", "SHARED_SEND"}) {
            row(walletA, "TRANSFER_OUT", "DEBIT", kind + "1", walletB, kind, userA);
            row(walletB, "TRANSFER_IN", "CREDIT", kind + "1", walletA, kind, userA);
        }
        row(walletA, "TRANSFER_OUT", "DEBIT", "personal", walletB, null, null); // personal Send Money, as before
    }

    @Test
    void kindAndInitiatorGoTogetherAndOnlyOnTransfers() {
        assertThrows(SQLException.class, () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t1", walletB, "CONTRIBUTION", null),
                "kind without initiator");
        assertThrows(SQLException.class, () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t2", walletB, null, userA),
                "initiator without kind");
        assertThrows(SQLException.class, () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t3", walletB, "GIFT", userA),
                "unknown kind");
        assertThrows(SQLException.class, () -> row(walletA, "CASH_TOP_UP", "CREDIT", null, null, "CONTRIBUTION", userA),
                "a top-up is not a shared transfer");
    }

    @Test
    void initiatorMustBeARegisteredUser() {
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t1", walletB, "WITHDRAWAL", 999_999L));
    }

    @Test
    void sharedTransfersKeepTheExistingTransferRules() throws Exception {
        assertThrows(SQLException.class, () -> row(walletA, "TRANSFER_OUT", "CREDIT", "t1", walletB, "CONTRIBUTION", userA),
                "direction must match the side");
        assertThrows(SQLException.class, () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t2", walletA, "CONTRIBUTION", userA),
                "a wallet can't be its own counterparty");
        row(walletA, "TRANSFER_OUT", "DEBIT", "t3", walletB, "CONTRIBUTION", userA);
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> row(walletA, "TRANSFER_OUT", "DEBIT", "t3", walletB, "CONTRIBUTION", userA), "one row per side");
    }

    private int counter;

    private void row(long wallet, String type, String direction, String transferId, Long counterparty, String kind,
                     Long initiator) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (wallet_id,"
                     + " transaction_type, direction, amount, balance_after, status, transfer_id,"
                     + " counterparty_wallet_id, transfer_kind, initiated_by_user_id, request_id, created_at)"
                     + " VALUES (?, ?, ?, 1.00, 1.00, 'COMPLETED', ?, ?, ?, ?, ?, UTC_TIMESTAMP())")) {
            ps.setLong(1, wallet);
            ps.setString(2, type);
            ps.setString(3, direction);
            ps.setString(4, transferId);
            if (counterparty == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, counterparty);
            }
            ps.setString(6, kind);
            if (initiator == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, initiator);
            }
            ps.setString(8, "req-" + (++counter));
            ps.executeUpdate();
        }
    }
}
