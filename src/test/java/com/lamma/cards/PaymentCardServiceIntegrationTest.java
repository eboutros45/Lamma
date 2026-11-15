package com.lamma.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.testsupport.TestDatabase;
import com.lamma.wallet.TopUpService;
import com.lamma.wallet.WalletService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Saved cards against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class PaymentCardServiceIntegrationTest {

    private static final String FULL_NUMBER = "4242424242424242";
    private static final String CVV = "987";

    private Database database;
    private PaymentCardService cards;
    private long lina;
    private long rami;
    private CardMetadata visa;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        cards = new PaymentCardService(database);
        visa = CardValidator.validate("Lina Haddad", FULL_NUMBER, "08/29", CVV, YearMonth.of(2026, 11));
    }

    @Test
    void addAndListCards() throws Exception {
        SavedCard saved = cards.addCard(lina, "  Whish   Card ", visa);

        assertEquals("Whish Card", saved.nickname(), "nickname is trimmed");
        List<SavedCard> list = cards.listCards(lina);
        assertEquals(List.of(saved), list);
        assertEquals(visa, list.get(0).details());
    }

    @Test
    void nicknameIsRequiredAndLimited() {
        assertThrows(ValidationException.class, () -> cards.addCard(lina, "", visa));
        assertThrows(ValidationException.class, () -> cards.addCard(lina, "   ", visa));
        assertThrows(ValidationException.class, () -> cards.addCard(lina, null, visa));
        assertThrows(ValidationException.class, () -> cards.addCard(lina, "x".repeat(41), visa));
    }

    @Test
    void sameCardCannotBeSavedTwiceByOneUserButAnotherUserMaySaveIt() throws Exception {
        cards.addCard(lina, "Whish Card", visa);
        ValidationException e = assertThrows(ValidationException.class, () -> cards.addCard(lina, "Again", visa));
        assertTrue(e.getMessage().contains("already saved"), e.getMessage());
        cards.addCard(rami, "Rami's copy", visa);
        assertEquals(1, cards.listCards(lina).size());
    }

    @Test
    void renameChangesOnlyTheNickname() throws Exception {
        SavedCard saved = cards.addCard(lina, "Whish Card", visa);

        cards.renameCard(lina, saved.cardId(), "BLOM Card");

        SavedCard renamed = cards.listCards(lina).get(0);
        assertEquals("BLOM Card", renamed.nickname());
        assertEquals(saved.cardId(), renamed.cardId());
        assertEquals(visa, renamed.details(), "card metadata unchanged");
        assertThrows(ValidationException.class, () -> cards.renameCard(lina, saved.cardId(), " "));
    }

    @Test
    void removeDeletesTheCardButKeepsPastTopUps() throws Exception {
        SavedCard saved = cards.addCard(lina, "Whish Card", visa);
        new TopUpService(database).savedCardTopUp(lina, saved.cardId(), new BigDecimal("15.00"), "r1");

        cards.removeCard(lina, saved.cardId());

        assertTrue(cards.listCards(lina).isEmpty());
        var history = new WalletService(database).loadRecentTransactions(lina, 10);
        assertEquals(1, history.size(), "history survives card removal");
        assertEquals("4242", history.get(0).cardLast4());
        assertEquals(new BigDecimal("15.00"), new WalletService(database).loadPersonalWallet(lina).balance());
        assertThrows(ValidationException.class, () -> cards.removeCard(lina, saved.cardId()), "already removed");
    }

    @Test
    void usersCannotSeeRenameOrRemoveEachOthersCards() throws Exception {
        SavedCard linas = cards.addCard(lina, "Whish Card", visa);

        assertTrue(cards.listCards(rami).isEmpty());
        assertThrows(ValidationException.class, () -> cards.renameCard(rami, linas.cardId(), "Stolen"));
        assertThrows(ValidationException.class, () -> cards.removeCard(rami, linas.cardId()));
        assertEquals("Whish Card", cards.listCards(lina).get(0).nickname());
    }

    @Test
    void cardsSurviveARestart() throws Exception {
        cards.addCard(lina, "Whish Card", visa);
        DatabaseConfig c = database.config();
        PaymentCardService afterRestart = new PaymentCardService(
                new Database(new DatabaseConfig(c.url(), c.user(), c.password())));

        assertEquals("Whish Card", afterRestart.listCards(lina).get(0).nickname());
    }

    @Test
    void fullCardNumberAndCvvAreNeverStored() throws Exception {
        SavedCard saved = cards.addCard(lina, "Whish Card", visa);
        new TopUpService(database).savedCardTopUp(lina, saved.cardId(), new BigDecimal("1.00"), "r1");
        new TopUpService(database).newCardTopUp(lina,
                CardValidator.validate("Lina Haddad", "5555555555554444", "01/30", "321", YearMonth.of(2026, 11)),
                new BigDecimal("2.00"), "r2");

        for (String table : List.of("payment_cards", "wallet_transactions", "users", "wallets")) {
            String everything = dumpTable(table);
            assertFalse(everything.contains(FULL_NUMBER), table + " contains a full card number");
            assertFalse(everything.contains("5555555555554444"), table + " contains a full card number");
            assertFalse(everything.contains("42424242"), table + " contains part of the card number");
            assertFalse(everything.contains(CVV) || everything.contains("|321|"), table + " contains a CVV");
        }
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM payment_cards");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnName(i).toLowerCase();
                assertFalse(column.contains("cvv") || column.contains("number") || column.contains("pan"), column);
            }
        }
    }

    private String dumpTable(String table) throws Exception {
        StringBuilder sb = new StringBuilder("|");
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= columns; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
            }
        }
        return sb.toString();
    }
}
