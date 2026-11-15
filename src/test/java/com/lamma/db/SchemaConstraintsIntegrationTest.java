package com.lamma.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the real MySQL schema and its constraints.
 *
 * <p>Runs only when LAMMA_TEST_DB_URL, LAMMA_TEST_DB_USER and LAMMA_TEST_DB_PASSWORD are set,
 * otherwise it is skipped. The database name must end in "_test" because every test
 * drops and recreates all LAMMA tables in it.
 */
class SchemaConstraintsIntegrationTest {

    private static final String VALID_HASH = "x".repeat(60);

    private Database database;

    @BeforeEach
    void freshSchema() throws Exception {
        String url = System.getenv("LAMMA_TEST_DB_URL");
        String user = System.getenv("LAMMA_TEST_DB_USER");
        String password = System.getenv("LAMMA_TEST_DB_PASSWORD");
        assumeTrue(url != null && user != null && password != null,
                "Set LAMMA_TEST_DB_URL / LAMMA_TEST_DB_USER / LAMMA_TEST_DB_PASSWORD to run MySQL tests");

        database = new Database(new DatabaseConfig(url, user, password));
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            if (!conn.getCatalog().endsWith("_test")) {
                throw new IllegalStateException("Refusing to wipe non-test database: " + conn.getCatalog());
            }
            st.execute("DROP TABLE IF EXISTS wallets");
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("DROP TABLE IF EXISTS schema_version");
        }
        new SchemaMigrator(database).migrate();
    }

    @Test
    void schemaIsCreatedFromScratchAndRerunningIsHarmless() throws Exception {
        SchemaMigrator migrator = new SchemaMigrator(database);
        assertEquals(SchemaMigrator.latestVersion(), migrator.currentVersion());
        assertEquals(0, migrator.migrate(), "second run must not apply anything");
    }

    @Test
    void statusReportShowsConnection() throws Exception {
        DatabaseStatus.Report report = DatabaseStatus.checkAndMigrate(database);
        assertEquals(SchemaMigrator.latestVersion(), report.schemaVersion());
        assertEquals(0, report.migrationsApplied());
    }

    @Test
    void duplicatePhoneNumberIsRejected() throws Exception {
        try (Connection conn = database.getConnection()) {
            insertUser(conn, "15145550100");
            assertThrows(SQLIntegrityConstraintViolationException.class, () -> insertUser(conn, "15145550100"));
        }
    }

    @Test
    void unnormalizedPhoneNumberIsRejected() throws Exception {
        try (Connection conn = database.getConnection()) {
            assertThrows(SQLException.class, () -> insertUser(conn, "+1 (514) 555-0100"));
            assertThrows(SQLException.class, () -> insertUser(conn, "12345"));
        }
    }

    @Test
    void plaintextPinIsRejectedByHashLengthCheck() throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (first_name, last_name, phone_number, pin_hash) VALUES ('A', 'B', ?, ?)")) {
            ps.setString(1, "15145550101");
            ps.setString(2, "123456");
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void newWalletStartsWithZeroBalance() throws Exception {
        try (Connection conn = database.getConnection()) {
            long userId = insertUser(conn, "15145550102");
            long walletId = insertWallet(conn, userId, "PERSONAL");
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM wallets WHERE wallet_id = ?")) {
                ps.setLong(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, BigDecimal.ZERO.compareTo(rs.getBigDecimal(1)));
                }
            }
        }
    }

    @Test
    void userCannotHaveTwoPersonalWallets() throws Exception {
        try (Connection conn = database.getConnection()) {
            long userId = insertUser(conn, "15145550103");
            insertWallet(conn, userId, "PERSONAL");
            assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> insertWallet(conn, userId, "PERSONAL"));
        }
    }

    @Test
    void differentUsersEachGetTheirOwnPersonalWallet() throws Exception {
        try (Connection conn = database.getConnection()) {
            insertWallet(conn, insertUser(conn, "15145550104"), "PERSONAL");
            insertWallet(conn, insertUser(conn, "15145550105"), "PERSONAL");
        }
    }

    @Test
    void walletMustBelongToAnExistingUser() throws Exception {
        try (Connection conn = database.getConnection()) {
            assertThrows(SQLIntegrityConstraintViolationException.class, () -> insertWallet(conn, 999_999, "PERSONAL"));
        }
    }

    @Test
    void unknownWalletTypeIsRejected() throws Exception {
        try (Connection conn = database.getConnection()) {
            long userId = insertUser(conn, "15145550106");
            assertThrows(SQLException.class, () -> insertWallet(conn, userId, "BUSINESS"));
        }
    }

    @Test
    void negativeBalanceIsRejected() throws Exception {
        try (Connection conn = database.getConnection()) {
            long userId = insertUser(conn, "15145550107");
            long walletId = insertWallet(conn, userId, "PERSONAL");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE wallets SET balance = -1 WHERE wallet_id = ?")) {
                ps.setLong(1, walletId);
                assertThrows(SQLException.class, ps::executeUpdate);
            }
        }
    }

    private static long insertUser(Connection conn, String phone) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (first_name, last_name, phone_number, pin_hash) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "Test");
            ps.setString(2, "User");
            ps.setString(3, phone);
            ps.setString(4, VALID_HASH);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertWallet(Connection conn, long userId, String type) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (wallet_type, owner_user_id) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setLong(2, userId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
