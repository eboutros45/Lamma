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

/** Database constraints added by V007 (skipped without LAMMA_TEST_DB_* settings). */
class MembershipSchemaIntegrationTest {

    private Database database;
    private long a;
    private long b;
    private long wallet;
    private long otherWallet;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        a = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        b = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        SharedWalletService shared = new SharedWalletService(database);
        wallet = shared.create(a, SharedWalletService.validate("Trip", null, null, null));
        otherWallet = shared.create(a, SharedWalletService.validate("Rent", null, null, null));
    }

    @Test
    void onePendingInvitationPerPersonPerWallet() throws Exception {
        invite(wallet, "PENDING");
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> invite(wallet, "PENDING"));
        invite(wallet, "DECLINED");
        invite(wallet, "CANCELLED");
        invite(otherWallet, "PENDING");
        assertThrows(SQLException.class, () -> invite(wallet, "MAYBE"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO shared_wallet_invitations (shared_wallet_id,"
                + " invitee_user_id, invited_by_user_id, status, created_at) VALUES (" + wallet + ", " + a + ", " + a
                + ", 'PENDING', UTC_TIMESTAMP())"), "cannot invite oneself");
    }

    @Test
    void onePendingRequestPerPersonPerWalletAndDecisionsRecorded() throws Exception {
        request(wallet, "PENDING", false);
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> request(wallet, "PENDING", false));
        request(wallet, "REJECTED", true);
        assertThrows(SQLException.class, () -> request(wallet, "APPROVED", false), "a decision needs who and when");
        assertThrows(SQLException.class, () -> request(otherWallet, "PENDING", true), "pending has no decision");
    }

    @Test
    void inviteCodesAreUnique() throws Exception {
        exec("UPDATE shared_wallets SET invite_token = 'AAAAAAAAAAAAAAAAAAAAAAAA' WHERE shared_wallet_id = " + wallet);
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "UPDATE shared_wallets SET invite_token = 'AAAAAAAAAAAAAAAAAAAAAAAA' WHERE shared_wallet_id = " + otherWallet));
    }

    private void invite(long w, String status) throws Exception {
        exec("INSERT INTO shared_wallet_invitations (shared_wallet_id, invitee_user_id, invited_by_user_id, status,"
                + " created_at) VALUES (" + w + ", " + b + ", " + a + ", '" + status + "', UTC_TIMESTAMP())");
    }

    private void request(long w, String status, boolean decided) throws Exception {
        exec("INSERT INTO shared_wallet_join_requests (shared_wallet_id, requester_user_id, status, created_at,"
                + " decided_at, decided_by_user_id) VALUES (" + w + ", " + b + ", '" + status + "', UTC_TIMESTAMP(), "
                + (decided ? "UTC_TIMESTAMP(), " + a : "NULL, NULL") + ")");
    }

    private void exec(String sql) throws SQLException, Exception {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
