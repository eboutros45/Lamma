package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** Creation-form rules, member roles and outage messages (no MySQL needed). */
class SharedWalletValidationTest {

    @Test
    void nameIsRequiredAndOtherFieldsAreOptional() throws ValidationException {
        SharedWalletService.NewSharedWallet w = SharedWalletService.validate("  Beirut   Trip ", " ", "", "");
        assertEquals("Beirut Trip", w.name());
        assertNull(w.purpose());
        assertNull(w.description());
        assertNull(w.targetAmount());

        SharedWalletService.NewSharedWallet full = SharedWalletService.validate("Rent", "Monthly rent",
                "Split between flatmates", "$1,200".replace(",", ""));
        assertEquals("Monthly rent", full.purpose());
        assertEquals(new BigDecimal("1200.00"), full.targetAmount());
    }

    @Test
    void invalidInputIsRejectedWithAllProblemsListed() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> SharedWalletService.validate(" ", "x".repeat(81), "y".repeat(501), "0"));
        assertTrue(e.problems().contains("Wallet name is required."));
        assertTrue(e.problems().contains("Purpose must be 80 characters or fewer."));
        assertTrue(e.problems().contains("Description must be 500 characters or fewer."));
        assertTrue(e.problems().contains("Target amount must be greater than zero."));

        for (String badTarget : new String[] {"-5", "abc", "12.345", "1,000", "0.00"}) {
            assertThrows(ValidationException.class, () -> SharedWalletService.validate("Trip", null, null, badTarget),
                    badTarget);
        }
        assertThrows(ValidationException.class, () -> SharedWalletService.validate("n".repeat(61), null, null, null));
        assertThrows(ValidationException.class, () -> SharedWalletService.validate(null, null, null, null));
    }

    @Test
    void rolePermissionsMatchTheApprovedModel() {
        assertTrue(MemberRole.OWNER.canAdminister() && MemberRole.OWNER.canSendOut() && MemberRole.OWNER.canContribute());
        assertTrue(MemberRole.CONTRIBUTOR.canContribute());
        assertFalse(MemberRole.CONTRIBUTOR.canSendOut());
        assertFalse(MemberRole.CONTRIBUTOR.canAdminister());
        assertTrue(MemberRole.SPENDER.canContribute() && MemberRole.SPENDER.canSendOut());
        assertFalse(MemberRole.SPENDER.canAdminister());
        assertEquals(MemberRole.CONTRIBUTOR, MemberRole.defaultForNewMembers());
        assertEquals("Owner", MemberRole.OWNER.displayName());
    }

    @Test
    void outageGivesPlainMessages() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        SharedWalletService service = new SharedWalletService(new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + port + "/lamma?connectTimeout=2000", "u", "p")));
        assertEquals(SharedWalletService.FAILED_MESSAGE, assertThrows(DatabaseException.class,
                () -> service.create(1, SharedWalletService.validate("Trip", null, null, null))).getMessage());
        assertEquals(SharedWalletService.UNAVAILABLE_MESSAGE,
                assertThrows(DatabaseException.class, () -> service.listForMember(1)).getMessage());
        assertEquals(SharedWalletService.UNAVAILABLE_MESSAGE,
                assertThrows(DatabaseException.class, () -> service.details(1, 1)).getMessage());
    }
}
