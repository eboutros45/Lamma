package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.RegistrationService.Registration;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared wallets against real MySQL (skipped without LAMMA_TEST_DB_* settings). */
class SharedWalletServiceIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T08:30:00Z"), ZoneOffset.UTC);

    private Database database;
    private SharedWalletService shared;
    private Registration lina;
    private Registration rami;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111");
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222");
        shared = new SharedWalletService(database, CLOCK);
    }

    @Test
    void creationWritesWalletSharedRowAndOwnerMembershipWithZeroBalance() throws Exception {
        long id = shared.create(lina.userId(),
                SharedWalletService.validate("Beirut Trip", "Summer trip", "Hotel and food", "1500"));

        assertEquals("SHARED|Beirut Trip|null|0.00|ACTIVE|null",
                row("SELECT CONCAT_WS('|', wallet_type, name, IFNULL(owner_user_id,'null'), balance, status,"
                        + " IFNULL(personal_owner_user_id,'null')) FROM wallets WHERE wallet_id = " + id));
        assertEquals("Summer trip|Hotel and food|1500.00|null",
                row("SELECT CONCAT_WS('|', purpose, description, target_amount, IFNULL(gift_recipient_id,'null'))"
                        + " FROM shared_wallets WHERE shared_wallet_id = " + id));
        assertEquals(lina.userId() + "|OWNER|2026-11-22 08:30:00",
                row("SELECT CONCAT_WS('|', user_id, member_role, joined_at) FROM shared_wallet_members"
                        + " WHERE shared_wallet_id = " + id));
        assertEquals("1", row("SELECT COUNT(*) FROM shared_wallet_members WHERE shared_wallet_id = " + id));
    }

    @Test
    void detailsShowTheWalletAndTheCreatorAsOwner() throws Exception {
        long id = shared.create(lina.userId(), SharedWalletService.validate("Rent", null, null, null));

        SharedWallet.Details d = shared.details(lina.userId(), id);

        assertEquals(id, d.wallet().walletId());
        assertEquals("Rent", d.wallet().name());
        assertEquals(0, BigDecimal.ZERO.compareTo(d.wallet().balance()));
        assertNull(d.wallet().purpose());
        assertNull(d.wallet().targetAmount());
        assertEquals(MemberRole.OWNER, d.wallet().myRole());
        assertEquals(List.of(new SharedWallet.Member(lina.userId(), "Lina", "Haddad", MemberRole.OWNER,
                LocalDateTime.of(2026, 11, 22, 8, 30))), d.members());
    }

    @Test
    void listShowsOnlyTheMembersOwnSharedWallets() throws Exception {
        long trip = shared.create(lina.userId(), SharedWalletService.validate("Trip", "Fun", null, "300"));
        long rent = shared.create(lina.userId(), SharedWalletService.validate("Rent", null, null, null));
        long ramis = shared.create(rami.userId(), SharedWalletService.validate("Gym", null, null, null));

        List<SharedWallet.Summary> linas = shared.listForMember(lina.userId());
        assertEquals(List.of(trip, rent), linas.stream().map(SharedWallet.Summary::walletId).toList());
        assertEquals("Fun", linas.get(0).purpose());
        assertEquals(new BigDecimal("300.00"), linas.get(0).targetAmount());
        assertEquals(List.of(ramis), shared.listForMember(rami.userId()).stream()
                .map(SharedWallet.Summary::walletId).toList());
    }

    @Test
    void nonMembersCannotOpenTheWalletAndGetTheSameAnswerAsForAMissingWallet() throws Exception {
        long id = shared.create(lina.userId(), SharedWalletService.validate("Private", null, null, null));

        ValidationException stranger = assertThrows(ValidationException.class, () -> shared.details(rami.userId(), id));
        ValidationException missing = assertThrows(ValidationException.class, () -> shared.details(rami.userId(), 999_999));
        assertEquals(SharedWalletService.NOT_AVAILABLE_MESSAGE, stranger.getMessage());
        assertEquals(stranger.getMessage(), missing.getMessage());
        assertTrue(shared.listForMember(rami.userId()).isEmpty());
    }

    @Test
    void personalWalletIdsAreNotSharedWallets() {
        assertThrows(ValidationException.class, () -> shared.details(lina.userId(), lina.walletId()));
        assertThrows(ValidationException.class, () -> shared.details(rami.userId(), lina.walletId()));
    }

    @Test
    void invalidInputCreatesNothing() throws Exception {
        assertThrows(ValidationException.class, () -> SharedWalletService.validate("", null, null, "-1"));
        assertEquals("0", row("SELECT COUNT(*) FROM shared_wallets"));
        assertEquals("2", row("SELECT COUNT(*) FROM wallets"), "only the two personal wallets");
    }

    @Test
    void failureAtTheLastStepRollsBackTheWalletAndSharedRows() throws Exception {
        SharedWalletService failing = new SharedWalletService(database, CLOCK) {
            @Override
            protected void insertOwnerMembership(Connection conn, long userId, long walletId, LocalDateTime joinedAt)
                    throws SQLException {
                throw new SQLException("Simulated failure while adding the owner");
            }
        };

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> failing.create(lina.userId(), SharedWalletService.validate("Trip", "x", "y", "10")));

        assertEquals(SharedWalletService.FAILED_MESSAGE, e.getMessage());
        assertEquals("2", row("SELECT COUNT(*) FROM wallets"), "no SHARED wallet row left behind");
        assertEquals("0", row("SELECT COUNT(*) FROM shared_wallets"));
        assertEquals("0", row("SELECT COUNT(*) FROM shared_wallet_members"));
    }

    @Test
    void creatorWhoDoesNotExistIsRejectedByTheDatabaseAndNothingIsKept() throws Exception {
        assertThrows(DatabaseException.class,
                () -> shared.create(999_999, SharedWalletService.validate("Ghost", null, null, null)));
        assertEquals("2", row("SELECT COUNT(*) FROM wallets"));
        assertEquals("0", row("SELECT COUNT(*) FROM shared_wallets"));
    }

    @Test
    void sharedWalletsSurviveARestart() throws Exception {
        long id = shared.create(lina.userId(), SharedWalletService.validate("Trip", null, null, "50"));
        DatabaseConfig c = database.config();
        SharedWalletService afterRestart = new SharedWalletService(
                new Database(new DatabaseConfig(c.url(), c.user(), c.password())));

        assertEquals("Trip", afterRestart.details(lina.userId(), id).wallet().name());
        assertEquals(1, afterRestart.listForMember(lina.userId()).size());
    }

    @Test
    void personalWalletsAndExistingFlowsAreUnaffected() throws Exception {
        shared.create(lina.userId(), SharedWalletService.validate("Trip", null, null, null));
        shared.create(lina.userId(), SharedWalletService.validate("Rent", null, null, null));

        WalletService wallets = new WalletService(database);
        assertEquals(lina.walletId(), wallets.loadPersonalWallet(lina.userId()).walletId(),
                "the personal wallet is still found, not a shared one");
        new TopUpService(database).cashTopUp(lina.userId(), new BigDecimal("20.00"), "cash-1");
        TransferService transfers = new TransferService(database);
        assertEquals(rami.userId(), transfers.findRecipient(lina.userId(), "70 123 456").userId());
        transfers.send(lina.userId(), rami.userId(), new BigDecimal("5.00"), "t-1");

        assertEquals(new BigDecimal("15.00"), wallets.loadPersonalWallet(lina.userId()).balance());
        assertEquals(new BigDecimal("5.00"), wallets.loadPersonalWallet(rami.userId()).balance());
        assertEquals("0.00|0.00", row("SELECT CONCAT_WS('|', MIN(balance), MAX(balance)) FROM wallets"
                + " WHERE wallet_type = 'SHARED'"), "shared wallets untouched by personal flows");
        assertEquals("Personal Wallet", row("SELECT name FROM wallets WHERE wallet_id = " + lina.walletId()));
        // Registration still creates exactly one personal wallet per user.
        assertEquals("2", row("SELECT COUNT(*) FROM wallets WHERE wallet_type = 'PERSONAL'"));
    }

    private String row(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
