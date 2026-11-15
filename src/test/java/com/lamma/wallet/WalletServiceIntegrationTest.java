package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.AuthenticationService;
import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.RegistrationService.Registration;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dashboard data retrieval against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class WalletServiceIntegrationTest {

    private final PinHasher hasher = new PinHasher(1_000);
    private Database database;
    private RegistrationService registration;
    private WalletService wallets;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        registration = new RegistrationService(database, hasher);
        wallets = new WalletService(database);
    }

    @Test
    void newUserHasZeroBalancePersonalWallet() throws Exception {
        Registration r = registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");

        PersonalWallet wallet = wallets.loadPersonalWallet(r.userId());

        assertEquals(r.walletId(), wallet.walletId());
        assertEquals(r.userId(), wallet.ownerUserId());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.balance()));
        assertEquals("$0.00", Money.formatUsd(wallet.balance()));
    }

    @Test
    void nonZeroBalanceStoredInDatabaseIsReturnedExactly() throws Exception {
        Registration r = registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");
        setBalance(r.walletId(), "1234.56");

        PersonalWallet wallet = wallets.loadPersonalWallet(r.userId());

        assertEquals(new BigDecimal("1234.56"), wallet.balance());
        assertEquals("$1,234.56", Money.formatUsd(wallet.balance()));
    }

    @Test
    void eachSignedInUserGetsOnlyTheirOwnWallet() throws Exception {
        Registration a = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        Registration b = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        setBalance(a.walletId(), "10.00");
        setBalance(b.walletId(), "20.50");

        AuthenticationService auth = new AuthenticationService(database, hasher);
        AuthenticatedUser userA = auth.signIn("+961 3 123 456", "111111");
        AuthenticatedUser userB = auth.signIn("+961 70 123 456", "222222");

        PersonalWallet walletA = wallets.loadPersonalWallet(userA.userId());
        PersonalWallet walletB = wallets.loadPersonalWallet(userB.userId());

        assertNotEquals(walletA.walletId(), walletB.walletId());
        assertEquals(a.walletId(), walletA.walletId());
        assertEquals(new BigDecimal("10.00"), walletA.balance());
        assertEquals(b.walletId(), walletB.walletId());
        assertEquals(new BigDecimal("20.50"), walletB.balance());
    }

    @Test
    void missingPersonalWalletGivesFriendlyError() throws Exception {
        Registration r = registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");
        execute("DELETE FROM wallets WHERE wallet_id = " + r.walletId());

        DatabaseException e = assertThrows(DatabaseException.class, () -> wallets.loadPersonalWallet(r.userId()));
        assertEquals(WalletService.NOT_FOUND_MESSAGE, e.getMessage());
    }

    @Test
    void unknownUserIdFindsNoWallet() throws Exception {
        registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");

        DatabaseException e = assertThrows(DatabaseException.class, () -> wallets.loadPersonalWallet(999_999));
        assertEquals(WalletService.NOT_FOUND_MESSAGE, e.getMessage());
    }

    @Test
    void databaseErrorDuringQueryIsFriendlyAndHidesSqlDetails() throws Exception {
        Registration r = registration.registerPersonal("Lina", "Haddad", "03 123 456", "123456", "123456");
        // Simulate a broken schema: the query's balance column no longer exists.
        execute("ALTER TABLE wallets DROP CHECK chk_wallets_balance_not_negative");
        execute("ALTER TABLE wallets RENAME COLUMN balance TO balance_broken");

        DatabaseException e = assertThrows(DatabaseException.class, () -> wallets.loadPersonalWallet(r.userId()));
        assertEquals(WalletService.UNAVAILABLE_MESSAGE, e.getMessage());
    }

    private void setBalance(long walletId, String amount) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE wallets SET balance = ? WHERE wallet_id = ?")) {
            ps.setBigDecimal(1, new BigDecimal(amount));
            ps.setLong(2, walletId);
            ps.executeUpdate();
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
