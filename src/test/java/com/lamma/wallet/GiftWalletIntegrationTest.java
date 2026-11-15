package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Gift wallets (P04B) against real MySQL (skipped without LAMMA_TEST_DB_*). */
class GiftWalletIntegrationTest {

    private Database database;
    private SharedWalletService wallets;
    private SharedWalletMembershipService members;
    private SharedWalletFundsService funds;
    private long lina;
    private long rami;
    private long nour;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        nour = registration.registerPersonal("Nour", "Saad", "71 555 666", "333333", "333333").userId();
        wallets = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        funds = new SharedWalletFundsService(database);
        new TopUpService(database).cashTopUp(lina, new BigDecimal("50.00"), UUID.randomUUID().toString());
    }

    @Test
    void aGiftWalletRecordsItsRecipientAndOtherwiseBehavesLikeAnyWallet() throws Exception {
        TransferService.Recipient found = wallets.findGiftRecipient(lina, "+961 71 555 666");
        assertEquals("Nour Saad", found.fullName());
        long gift = wallets.create(lina, SharedWalletService.validate("Nour's Birthday", null, null, "200")
                .asGiftFor(found.userId()));

        assertEquals(String.valueOf(nour), value("SELECT gift_recipient_id FROM shared_wallets WHERE shared_wallet_id = " + gift));
        SharedWallet.Summary w = wallets.details(lina, gift).wallet();
        assertTrue(w.isGift());
        assertEquals("Nour Saad", w.giftRecipientName());
        assertEquals(MemberRole.OWNER, w.myRole());
        assertEquals("$0.00", Money.formatUsd(w.balance()), "same balance column; no separate gift balance");
        assertEquals("SHARED", value("SELECT wallet_type FROM wallets WHERE wallet_id = " + gift), "no new wallet type");

        // The recipient gets nothing automatically: no membership, no access, no money.
        assertTrue(wallets.listForMember(nour).isEmpty());
        assertThrows(ValidationException.class, () -> wallets.details(nour, gift));
        assertThrows(ValidationException.class, () -> funds.history(nour, gift, 10));

        // Normal permissions apply.
        funds.contribute(lina, gift, new BigDecimal("20"), UUID.randomUUID().toString());
        members.inviteByPhone(lina, gift, "70 123 456");
        members.acceptInvitation(rami, members.incomingInvitations(rami).get(0).invitationId());
        assertEquals("Nour Saad", wallets.details(rami, gift).wallet().giftRecipientName(), "members see it");
        assertThrows(ValidationException.class, () -> funds.withdraw(rami, gift, BigDecimal.ONE, UUID.randomUUID().toString()),
                "a Contributor still can't withdraw");
        assertEquals("0.00", value("SELECT balance FROM wallets WHERE owner_user_id = " + nour), "no payout");
    }

    @Test
    void theRecipientMustBeAnotherRegisteredUser() throws Exception {
        assertEquals(TransferService.NOT_FOUND_MESSAGE,
                assertThrows(ValidationException.class, () -> wallets.findGiftRecipient(lina, "76 000 111")).getMessage());
        assertEquals(SharedWalletService.GIFT_SELF_MESSAGE,
                assertThrows(ValidationException.class, () -> wallets.findGiftRecipient(lina, "03 123 456")).getMessage());
        assertThrows(ValidationException.class, () -> wallets.findGiftRecipient(lina, "12ab"));

        // Checked again when saving, so a forged id can't get through either.
        SharedWalletService.NewSharedWallet input = SharedWalletService.validate("Gift", null, null, null);
        assertEquals(SharedWalletService.GIFT_SELF_MESSAGE,
                assertThrows(ValidationException.class, () -> wallets.create(lina, input.asGiftFor(lina))).getMessage());
        assertEquals(SharedWalletService.GIFT_RECIPIENT_GONE_MESSAGE,
                assertThrows(ValidationException.class, () -> wallets.create(lina, input.asGiftFor(999_999))).getMessage());
        assertEquals("0", value("SELECT COUNT(*) FROM shared_wallets"), "nothing was saved");
        assertEquals("0", value("SELECT COUNT(*) FROM wallets WHERE wallet_type = 'SHARED'"));
    }

    @Test
    void anOrdinaryWalletIsUnchangedAndTheRecipientNeverChangesAfterCreation() throws Exception {
        long plain = wallets.create(lina, SharedWalletService.validate("Flat", null, null, null));
        assertNull(wallets.details(lina, plain).wallet().giftRecipientName());
        assertFalse(wallets.details(lina, plain).wallet().isGift());
        assertNull(value("SELECT gift_recipient_id FROM shared_wallets WHERE shared_wallet_id = " + plain));

        long gift = wallets.create(lina, SharedWalletService.validate("Gift", null, null, null).asGiftFor(nour));
        // Every operation the other services offer leaves the recipient as it was.
        members.inviteByPhone(lina, gift, "70 123 456");
        members.acceptInvitation(rami, members.incomingInvitations(rami).get(0).invitationId());
        members.changeRole(lina, gift, rami, MemberRole.SPENDER);
        members.newInviteCode(lina, gift);
        members.turnOffInviteCode(lina, gift);
        funds.contribute(lina, gift, new BigDecimal("10"), UUID.randomUUID().toString());
        funds.send(rami, gift, nour, new BigDecimal("4"), UUID.randomUUID().toString());
        members.leave(rami, gift);
        assertEquals(String.valueOf(nour), value("SELECT gift_recipient_id FROM shared_wallets WHERE shared_wallet_id = " + gift));
        assertEquals("Nour Saad", wallets.details(lina, gift).wallet().giftRecipientName());
    }

    private String value(String sql) throws Exception {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
