package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Personal registration against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class RegistrationIntegrationTest {

    private final PinHasher hasher = new PinHasher(1_000);
    private Database database;
    private RegistrationService service;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        service = new RegistrationService(database, hasher);
    }

    @Test
    void validRegistrationCreatesUserAndOneZeroBalancePersonalWallet() throws Exception {
        RegistrationService.Registration r =
                service.registerPersonal("  Lina ", "Haddad", "03 123 456", "123456", "123456");

        assertEquals("9613123456", r.phoneNumber());
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT u.first_name, u.last_name, u.phone_number, u.pin_hash, u.failed_pin_attempts,"
                     + " w.wallet_id, w.wallet_type, w.balance"
                     + " FROM users u JOIN wallets w ON w.owner_user_id = u.user_id WHERE u.user_id = ?")) {
            ps.setLong(1, r.userId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Lina", rs.getString("first_name"), "names are trimmed");
                assertEquals("Haddad", rs.getString("last_name"));
                assertEquals("9613123456", rs.getString("phone_number"));
                assertEquals(0, rs.getInt("failed_pin_attempts"));
                assertEquals(r.walletId(), rs.getLong("wallet_id"));
                assertEquals("PERSONAL", rs.getString("wallet_type"));
                assertEquals(0, BigDecimal.ZERO.compareTo(rs.getBigDecimal("balance")));

                String storedHash = rs.getString("pin_hash");
                assertFalse(storedHash.contains("123456"), "PIN must not be stored in plaintext");
                assertTrue(hasher.verify("123456", storedHash));

                assertFalse(rs.next(), "exactly one wallet");
            }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM users"));
        assertEquals(1, count("SELECT COUNT(*) FROM wallets"));
    }

    @Test
    void duplicatePhoneIsRejectedEvenWhenWrittenDifferently() throws Exception {
        service.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.registerPersonal("Other", "Person", "+961 3 123 456", "654321", "654321"));

        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM users"));
        assertEquals(1, count("SELECT COUNT(*) FROM wallets"));
    }

    @Test
    void failedWalletCreationRollsBackTheUser() throws Exception {
        RegistrationService failingWallet = new RegistrationService(database, hasher) {
            @Override
            protected long insertPersonalWallet(Connection conn, long userId) throws SQLException {
                throw new SQLException("Simulated wallet failure");
            }
        };

        assertThrows(DatabaseException.class,
                () -> failingWallet.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456"));

        assertEquals(0, count("SELECT COUNT(*) FROM users"), "user insert must be rolled back");
        assertEquals(0, count("SELECT COUNT(*) FROM wallets"));

        // The same phone can then register normally.
        service.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");
        assertEquals(1, count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void walletConstraintFailureAlsoRollsBackTheUser() throws Exception {
        // Inserts the wallet twice, so the database's one-personal-wallet rule fails the transaction.
        RegistrationService doubleWallet = new RegistrationService(database, hasher) {
            @Override
            protected long insertPersonalWallet(Connection conn, long userId) throws SQLException {
                super.insertPersonalWallet(conn, userId);
                return super.insertPersonalWallet(conn, userId);
            }
        };

        assertThrows(DatabaseException.class,
                () -> doubleWallet.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456"));

        assertEquals(0, count("SELECT COUNT(*) FROM users"));
        assertEquals(0, count("SELECT COUNT(*) FROM wallets"));
    }

    private long count(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
