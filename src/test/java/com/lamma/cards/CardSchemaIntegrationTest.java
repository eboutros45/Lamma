package com.lamma.cards;

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

/** Database constraints added by V004 (skipped without LAMMA_TEST_DB_* settings). */
class CardSchemaIntegrationTest {

    private Database database;
    private long userId;
    private long walletId;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        var r = new RegistrationService(database, new PinHasher(1_000))
                .registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        userId = r.userId();
        walletId = r.walletId();
    }

    @Test
    void cardRowRules() throws Exception {
        card("Whish", "Lina", "VISA", "4242", 8, 2029);
        assertThrows(SQLException.class, () -> card(" ", "Lina", "VISA", "4243", 8, 2029), "blank nickname");
        assertThrows(SQLException.class, () -> card("X", " ", "VISA", "4244", 8, 2029), "blank holder");
        assertThrows(SQLException.class, () -> card("X", "Lina", "JCB", "4245", 8, 2029), "unknown brand");
        assertThrows(SQLException.class, () -> card("X", "Lina", "VISA", "42a6", 8, 2029), "last4 digits");
        assertThrows(SQLException.class, () -> card("X", "Lina", "VISA", "424", 8, 2029), "last4 length");
        assertThrows(SQLException.class, () -> card("X", "Lina", "VISA", "4247", 13, 2029), "month");
        assertThrows(SQLException.class, () -> card("X", "Lina", "VISA", "4248", 8, 1999), "year");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> card("Same card", "Lina", "VISA", "4242", 8, 2029), "same card twice");
    }

    @Test
    void cardTopUpRowsMustCarryCardDetailsAndCashRowsMustNot() throws Exception {
        tx("CARD_TOP_UP", "CREDIT", "VISA", "4242", "t1");
        tx("CASH_TOP_UP", "CREDIT", null, null, "t2");
        assertThrows(SQLException.class, () -> tx("CARD_TOP_UP", "CREDIT", null, null, "t3"));
        assertThrows(SQLException.class, () -> tx("CARD_TOP_UP", "CREDIT", "VISA", null, "t4"));
        assertThrows(SQLException.class, () -> tx("CASH_TOP_UP", "CREDIT", "VISA", "4242", "t5"));
        assertThrows(SQLException.class, () -> tx("CARD_TOP_UP", "DEBIT", "VISA", "4242", "t6"));
        assertThrows(SQLException.class, () -> tx("CARD_TOP_UP", "CREDIT", "VISA", "42x2", "t7"));
    }

    private void card(String nickname, String holder, String brand, String last4, int month, int year)
            throws SQLException, Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO payment_cards"
                     + " (user_id, nickname, cardholder_name, brand, last4, exp_month, exp_year) VALUES (?,?,?,?,?,?,?)")) {
            ps.setLong(1, userId);
            ps.setString(2, nickname);
            ps.setString(3, holder);
            ps.setString(4, brand);
            ps.setString(5, last4);
            ps.setInt(6, month);
            ps.setInt(7, year);
            ps.executeUpdate();
        }
    }

    private void tx(String type, String direction, String brand, String last4, String requestId)
            throws SQLException, Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO wallet_transactions (wallet_id,"
                     + " transaction_type, direction, amount, balance_after, status, card_brand, card_last4,"
                     + " request_id, created_at) VALUES (?, ?, ?, 1.00, 1.00, 'COMPLETED', ?, ?, ?, UTC_TIMESTAMP())")) {
            ps.setLong(1, walletId);
            ps.setString(2, type);
            ps.setString(3, direction);
            ps.setString(4, brand);
            ps.setString(5, last4);
            ps.setString(6, requestId);
            ps.executeUpdate();
        }
    }
}
