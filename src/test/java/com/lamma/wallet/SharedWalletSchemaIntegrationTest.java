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

/** Database constraints added by V006 (skipped without LAMMA_TEST_DB_* settings). */
class SharedWalletSchemaIntegrationTest {

    private Database database;
    private long user;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        user = new RegistrationService(database, new PinHasher(1_000))
                .registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
    }

    @Test
    void walletTypeOwnerAndStatusRules() {
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, name, owner_user_id) VALUES ('SHARED', 'X', " + user + ")"),
                "a shared wallet has no personal owner");
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, name, owner_user_id) VALUES ('PERSONAL', 'X', NULL)"),
                "a personal wallet needs its user");
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, name, owner_user_id) VALUES ('GIFT', 'X', NULL)"));
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, name, status) VALUES ('SHARED', 'X', 'CLOSED')"));
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, name) VALUES ('SHARED', '   ')"), "blank name");
    }

    @Test
    void sharedWalletAndMembershipRules() throws Exception {
        long wallet = insertSharedWallet();
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO shared_wallets (shared_wallet_id, target_amount) VALUES (" + insertWalletOnly() + ", 0)"),
                "target must be positive");
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "INSERT INTO shared_wallets (shared_wallet_id) VALUES (999999)"), "must be an existing wallet");
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "INSERT INTO shared_wallets (shared_wallet_id) VALUES (" + wallet + ")"), "one row per wallet");

        exec("INSERT INTO shared_wallet_members VALUES (" + user + ", " + wallet + ", 'OWNER', UTC_TIMESTAMP())");
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "INSERT INTO shared_wallet_members VALUES (" + user + ", " + wallet + ", 'SPENDER', UTC_TIMESTAMP())"),
                "a user is a member of a wallet only once");
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO shared_wallet_members VALUES (" + user + ", " + insertSharedWallet()
                        + ", 'ADMIN', UTC_TIMESTAMP())"), "unknown role");
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "INSERT INTO shared_wallet_members VALUES (999999, " + wallet + ", 'OWNER', UTC_TIMESTAMP())"));
    }

    @Test
    void manySharedWalletsAreAllowedButStillOnlyOnePersonalWallet() throws Exception {
        insertSharedWallet();
        insertSharedWallet();
        assertThrows(SQLIntegrityConstraintViolationException.class, () -> exec(
                "INSERT INTO wallets (wallet_type, owner_user_id) VALUES ('PERSONAL', " + user + ")"));
    }

    private long insertSharedWallet() throws Exception {
        long id = insertWalletOnly();
        exec("INSERT INTO shared_wallets (shared_wallet_id) VALUES (" + id + ")");
        return id;
    }

    private long insertWalletOnly() throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO wallets (wallet_type, name) VALUES ('SHARED', 'Trip')", Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void exec(String sql) throws SQLException, Exception {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
