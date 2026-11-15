package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.lamma.wallet.SharedWalletMembershipService.IncomingInvitation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared-wallet membership against real MySQL, with three users (skipped without LAMMA_TEST_DB_*). */
class SharedWalletMembershipIntegrationTest {

    private Database database;
    private SharedWalletService wallets;
    private SharedWalletMembershipService members;
    private long lina;   // owner
    private long rami;
    private long nour;
    private long trip;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        nour = registration.registerPersonal("Nour", "Saad", "+1 514 555 0100", "333333", "333333").userId();
        wallets = new SharedWalletService(database);
        members = new SharedWalletMembershipService(database);
        trip = wallets.create(lina, SharedWalletService.validate("Trip", null, null, null));
    }

    // ---------------- direct invitations ----------------

    @Test
    void invitationLifecycleAcceptMakesAContributor() throws Exception {
        assertEquals("Rami Khoury", members.inviteByPhone(lina, trip, "+961 70 123 456"));

        assertDenied(() -> wallets.details(rami, trip), "pending invitation gives no access");
        assertTrue(wallets.listForMember(rami).isEmpty());
        assertEquals(1, members.pendingInvitations(lina, trip).size());
        List<IncomingInvitation> incoming = members.incomingInvitations(rami);
        assertEquals(1, incoming.size());
        assertEquals("Trip", incoming.get(0).walletName());
        assertEquals("Lina Haddad", incoming.get(0).invitedBy());
        assertTrue(members.incomingInvitations(nour).isEmpty(), "only the intended recipient sees it");

        assertEquals(trip, members.acceptInvitation(rami, incoming.get(0).invitationId()));

        SharedWallet.Details d = wallets.details(rami, trip);
        assertEquals(MemberRole.CONTRIBUTOR, d.wallet().myRole());
        assertEquals(List.of("Lina:OWNER", "Rami:CONTRIBUTOR"), roles(d));
        assertTrue(members.incomingInvitations(rami).isEmpty());
        assertTrue(members.pendingInvitations(lina, trip).isEmpty());
        assertEquals("ACCEPTED", value("SELECT status FROM shared_wallet_invitations"));
        assertThrows(ValidationException.class, () -> members.acceptInvitation(rami, incoming.get(0).invitationId()),
                "cannot accept twice");
        assertEquals("2", value("SELECT COUNT(*) FROM shared_wallet_members"));
    }

    @Test
    void declinedOrCancelledInvitationsCannotBeAccepted() throws Exception {
        members.inviteByPhone(lina, trip, "70 123 456");
        long first = members.incomingInvitations(rami).get(0).invitationId();
        members.declineInvitation(rami, first);
        assertEquals(SharedWalletMembershipService.INVITATION_CLOSED_MESSAGE,
                assertThrows(ValidationException.class, () -> members.acceptInvitation(rami, first)).getMessage());

        members.inviteByPhone(lina, trip, "70 123 456"); // can be invited again after declining
        long second = members.incomingInvitations(rami).get(0).invitationId();
        members.cancelInvitation(lina, second);
        assertThrows(ValidationException.class, () -> members.acceptInvitation(rami, second));
        assertThrows(ValidationException.class, () -> members.cancelInvitation(lina, second), "already cancelled");
        assertDenied(() -> wallets.details(rami, trip), "never became a member");
    }

    @Test
    void invitationRulesAndDuplicates() throws Exception {
        assertMessage(SharedWalletMembershipService.NO_ACCOUNT_MESSAGE, () -> members.inviteByPhone(lina, trip, "03 999 999"));
        assertThrows(ValidationException.class, () -> members.inviteByPhone(lina, trip, "12ab"), "invalid phone");
        assertThrows(ValidationException.class, () -> members.inviteByPhone(lina, trip, "03 123 456"), "the owner");

        members.inviteByPhone(lina, trip, "70 123 456");
        assertMessage(SharedWalletMembershipService.ALREADY_INVITED_MESSAGE,
                () -> members.inviteByPhone(lina, trip, "+961 70 123 456"));
        members.acceptInvitation(rami, members.incomingInvitations(rami).get(0).invitationId());
        assertMessage(SharedWalletMembershipService.ALREADY_MEMBER_MESSAGE,
                () -> members.inviteByPhone(lina, trip, "70 123 456"));
        assertEquals("1", value("SELECT COUNT(*) FROM shared_wallet_invitations"));
    }

    @Test
    void onlyTheOwnerManagesInvitationsAndOnlyTheInviteeAnswers() throws Exception {
        members.inviteByPhone(lina, trip, "70 123 456");
        long invitation = members.incomingInvitations(rami).get(0).invitationId();

        assertDenied(() -> members.inviteByPhone(nour, trip, "70 123 456"), "stranger");
        assertDenied(() -> members.pendingInvitations(nour, trip), "stranger");
        assertDenied(() -> members.cancelInvitation(nour, invitation), "stranger");
        assertMessage(SharedWalletMembershipService.INVITATION_NOT_AVAILABLE_MESSAGE,
                () -> members.acceptInvitation(nour, invitation));
        assertMessage(SharedWalletMembershipService.INVITATION_NOT_AVAILABLE_MESSAGE,
                () -> members.declineInvitation(lina, invitation));
        assertThrows(ValidationException.class, () -> members.acceptInvitation(rami, 999_999));

        members.acceptInvitation(rami, invitation);
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE,
                () -> members.inviteByPhone(rami, trip, "+1 514 555 0100"), "contributor can't invite");
    }

    // ---------------- QR invitations and join requests ----------------

    @Test
    void qrCodeLifecycleRequestAndApproval() throws Exception {
        assertNull(members.currentInviteCode(lina, trip), "off until the Owner turns it on");
        String code = members.newInviteCode(lina, trip);
        assertEquals(code, members.currentInviteCode(lina, trip));
        assertTrue(code.matches("[A-Za-z0-9_-]{24}"));

        assertEquals(new SharedWalletMembershipService.InvitationPreview("Trip", "Lina Haddad"),
                members.previewInvitation(rami, InvitationCodes.payload(code)));
        assertEquals("Trip", members.requestToJoin(rami, InvitationCodes.payload(code)));
        assertDenied(() -> wallets.details(rami, trip), "a request gives no access");
        assertMessage(SharedWalletMembershipService.YOU_REQUESTED_MESSAGE, () -> members.requestToJoin(rami, code));

        List<SharedWalletMembershipService.JoinRequest> requests = members.pendingJoinRequests(lina, trip);
        assertEquals(1, requests.size());
        assertEquals("Rami Khoury", requests.get(0).requesterName());
        assertEquals("+961 70 123 456", requests.get(0).requesterPhone());

        members.approveJoinRequest(lina, requests.get(0).requestId());
        assertEquals(MemberRole.CONTRIBUTOR, wallets.details(rami, trip).wallet().myRole());
        assertMessage(SharedWalletMembershipService.REQUEST_CLOSED_MESSAGE,
                () -> members.approveJoinRequest(lina, requests.get(0).requestId()));
        assertMessage(SharedWalletMembershipService.YOU_ARE_MEMBER_MESSAGE, () -> members.requestToJoin(rami, code));
        assertEquals("APPROVED|" + lina, value("SELECT CONCAT(status, '|', decided_by_user_id) FROM shared_wallet_join_requests"));
    }

    @Test
    void rejectedRequestGivesNoAccessAndCanBeMadeAgain() throws Exception {
        String code = members.newInviteCode(lina, trip);
        members.requestToJoin(rami, code);
        long request = members.pendingJoinRequests(lina, trip).get(0).requestId();
        members.rejectJoinRequest(lina, request);
        assertDenied(() -> wallets.details(rami, trip), "rejected");
        assertThrows(ValidationException.class, () -> members.approveJoinRequest(lina, request), "already rejected");
        members.requestToJoin(rami, code);
        assertEquals(1, members.pendingJoinRequests(lina, trip).size());
    }

    @Test
    void regeneratedOrTurnedOffCodesStopWorking() throws Exception {
        String first = members.newInviteCode(lina, trip);
        String second = members.newInviteCode(lina, trip);
        assertNotEquals(first, second);
        assertMessage(SharedWalletMembershipService.INVALID_CODE_MESSAGE, () -> members.requestToJoin(rami, first));

        members.turnOffInviteCode(lina, trip);
        assertNull(members.currentInviteCode(lina, trip));
        assertMessage(SharedWalletMembershipService.INVALID_CODE_MESSAGE, () -> members.requestToJoin(rami, second));
        assertMessage(SharedWalletMembershipService.INVALID_CODE_MESSAGE, () -> members.previewInvitation(rami, second));
        assertThrows(ValidationException.class, () -> members.requestToJoin(rami, "not-a-code"));
        assertThrows(ValidationException.class, () -> members.requestToJoin(rami, ""));
        assertEquals("0", value("SELECT COUNT(*) FROM shared_wallet_join_requests"), "invalid codes create nothing");
    }

    @Test
    void qrRulesForOwnerInviteesAndNonOwners() throws Exception {
        String code = members.newInviteCode(lina, trip);
        assertMessage(SharedWalletMembershipService.YOU_ARE_MEMBER_MESSAGE, () -> members.requestToJoin(lina, code),
                "the owner never has a request");
        members.inviteByPhone(lina, trip, "70 123 456");
        assertMessage(SharedWalletMembershipService.YOU_ARE_INVITED_MESSAGE, () -> members.requestToJoin(rami, code));

        members.requestToJoin(nour, code);
        assertMessage(SharedWalletMembershipService.HAS_PENDING_REQUEST_MESSAGE,
                () -> members.inviteByPhone(lina, trip, "+1 514 555 0100"));
        assertDenied(() -> members.currentInviteCode(nour, trip), "stranger");
        assertDenied(() -> members.newInviteCode(nour, trip), "stranger");
        assertDenied(() -> members.pendingJoinRequests(nour, trip), "stranger");
        long request = members.pendingJoinRequests(lina, trip).get(0).requestId();
        assertDenied(() -> members.approveJoinRequest(nour, request), "cannot approve own request");

        members.acceptInvitation(rami, members.incomingInvitations(rami).get(0).invitationId());
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE, () -> members.approveJoinRequest(rami, request));
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE, () -> members.newInviteCode(rami, trip));
    }

    @Test
    void concurrentApprovalsCreateExactlyOneMembership() throws Exception {
        members.requestToJoin(rami, members.newInviteCode(lina, trip));
        long request = members.pendingJoinRequests(lina, trip).get(0).requestId();

        List<Callable<Void>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> {
                members.approveJoinRequest(lina, request);
                return null;
            });
        }
        int ok = 0;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            for (Future<Void> f : pool.invokeAll(calls)) {
                try {
                    f.get();
                    ok++;
                } catch (ExecutionException e) {
                    assertEquals(SharedWalletMembershipService.REQUEST_CLOSED_MESSAGE, e.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, ok);
        assertEquals("1", value("SELECT COUNT(*) FROM shared_wallet_members WHERE user_id = " + rami));
    }

    @Test
    void concurrentAcceptAndCancelEndConsistently() throws Exception {
        for (int round = 0; round < 10; round++) {
            members.inviteByPhone(lina, trip, "70 123 456");
            long invitation = members.incomingInvitations(rami).get(0).invitationId();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Void>> results = pool.invokeAll(List.of(
                        () -> {
                            members.acceptInvitation(rami, invitation);
                            return null;
                        },
                        () -> {
                            members.cancelInvitation(lina, invitation);
                            return null;
                        }));
                int ok = 0;
                for (Future<Void> f : results) {
                    try {
                        f.get();
                        ok++;
                    } catch (ExecutionException expected) {
                        // the loser sees "no longer open"
                    }
                }
                assertEquals(1, ok, "exactly one of accept/cancel wins");
            } finally {
                pool.shutdown();
            }
            String status = value("SELECT status FROM shared_wallet_invitations WHERE invitation_id = " + invitation);
            String isMember = value("SELECT COUNT(*) FROM shared_wallet_members WHERE user_id = " + rami);
            assertTrue(("ACCEPTED".equals(status) && "1".equals(isMember))
                    || ("CANCELLED".equals(status) && "0".equals(isMember)), status + "/" + isMember);
            if ("1".equals(isMember)) {
                members.removeMember(lina, trip, rami);
            }
        }
    }

    // ---------------- roles, removal, leaving ----------------

    @Test
    void ownerPromotesAndDemotesAndChangesPersist() throws Exception {
        addMember(rami);
        members.changeRole(lina, trip, rami, MemberRole.SPENDER);
        assertEquals(MemberRole.SPENDER, wallets.details(rami, trip).wallet().myRole());
        members.changeRole(lina, trip, rami, MemberRole.CONTRIBUTOR);

        DatabaseConfig c = database.config();
        SharedWalletService afterRestart = new SharedWalletService(new Database(new DatabaseConfig(c.url(), c.user(), c.password())));
        assertEquals(MemberRole.CONTRIBUTOR, afterRestart.details(rami, trip).wallet().myRole());
        assertEquals(List.of("Lina:OWNER", "Rami:CONTRIBUTOR"), roles(afterRestart.details(lina, trip)));
    }

    @Test
    void roleRules() throws Exception {
        addMember(rami);
        addMember(nour);
        assertMessage(SharedWalletMembershipService.ROLE_NOT_ALLOWED_MESSAGE,
                () -> members.changeRole(lina, trip, rami, MemberRole.OWNER));
        assertMessage(SharedWalletMembershipService.OWNER_FIXED_MESSAGE,
                () -> members.changeRole(lina, trip, lina, MemberRole.CONTRIBUTOR), "owner can't demote themself");
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE,
                () -> members.changeRole(rami, trip, nour, MemberRole.SPENDER));
        members.changeRole(lina, trip, rami, MemberRole.SPENDER);
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE,
                () -> members.changeRole(rami, trip, nour, MemberRole.SPENDER), "spenders don't administer");
        assertMessage(SharedWalletMembershipService.NOT_A_MEMBER_MESSAGE,
                () -> members.changeRole(lina, trip, 999_999, MemberRole.SPENDER));
    }

    @Test
    void removalAndLeavingRevokeAccessAndAllowReinvitation() throws Exception {
        addMember(rami);
        addMember(nour);
        assertMessage(SharedWalletMembershipService.OWNER_ONLY_MESSAGE, () -> members.removeMember(rami, trip, nour));
        assertMessage(SharedWalletMembershipService.OWNER_FIXED_MESSAGE, () -> members.removeMember(lina, trip, lina));
        assertMessage(SharedWalletMembershipService.OWNER_FIXED_MESSAGE, () -> members.leave(lina, trip));

        members.removeMember(lina, trip, rami);
        assertDenied(() -> wallets.details(rami, trip), "removed");
        assertTrue(wallets.listForMember(rami).isEmpty());
        assertDenied(() -> members.leave(rami, trip), "no longer a member");

        members.leave(nour, trip);
        assertDenied(() -> wallets.details(nour, trip), "left");
        assertEquals(List.of("Lina:OWNER"), roles(wallets.details(lina, trip)));
        assertEquals("3", value("SELECT COUNT(*) FROM users"), "users are never deleted");

        members.inviteByPhone(lina, trip, "70 123 456");
        members.acceptInvitation(rami, members.incomingInvitations(rami).get(0).invitationId());
        assertEquals(MemberRole.CONTRIBUTOR, wallets.details(rami, trip).wallet().myRole(), "re-invited as Contributor");
    }

    @Test
    void membershipChangesDoNotTouchMoneyOrPersonalWallets() throws Exception {
        new TopUpService(database).cashTopUp(lina, new BigDecimal("40.00"), "cash");
        addMember(rami);
        members.changeRole(lina, trip, rami, MemberRole.SPENDER);
        members.removeMember(lina, trip, rami);
        new TransferService(database).send(lina, rami, new BigDecimal("5.00"), "t");

        WalletService personal = new WalletService(database);
        assertEquals(new BigDecimal("35.00"), personal.loadPersonalWallet(lina).balance());
        assertEquals(new BigDecimal("5.00"), personal.loadPersonalWallet(rami).balance());
        assertEquals("3", value("SELECT COUNT(*) FROM wallet_transactions"), "history intact");
        assertEquals(0, BigDecimal.ZERO.compareTo(wallets.details(lina, trip).wallet().balance()));
    }

    // ---------------- rollback ----------------

    @Test
    void failureWhileAddingTheMemberLeavesInvitationAndRequestPending() throws Exception {
        SharedWalletMembershipService failing = new SharedWalletMembershipService(database) {
            @Override
            protected void insertMember(Connection conn, long userId, long walletId, MemberRole role,
                                        LocalDateTime joinedAt) throws SQLException {
                throw new SQLException("Simulated failure");
            }
        };
        members.inviteByPhone(lina, trip, "70 123 456");
        long invitation = members.incomingInvitations(rami).get(0).invitationId();
        assertThrows(DatabaseException.class, () -> failing.acceptInvitation(rami, invitation));
        assertEquals("PENDING", value("SELECT status FROM shared_wallet_invitations"));

        members.requestToJoin(nour, members.newInviteCode(lina, trip));
        long request = members.pendingJoinRequests(lina, trip).get(0).requestId();
        assertThrows(DatabaseException.class, () -> failing.approveJoinRequest(lina, request));
        assertEquals("PENDING", value("SELECT status FROM shared_wallet_join_requests"));
        assertEquals("1", value("SELECT COUNT(*) FROM shared_wallet_members"), "only the owner");

        members.acceptInvitation(rami, invitation); // both still work afterwards
        members.approveJoinRequest(lina, request);
        assertEquals("3", value("SELECT COUNT(*) FROM shared_wallet_members"));
    }

    // ---------------- helpers ----------------

    private void addMember(long userId) throws Exception {
        String phone = value("SELECT phone_number FROM users WHERE user_id = " + userId);
        members.inviteByPhone(lina, trip, "+" + phone);
        long invitation = members.incomingInvitations(userId).get(0).invitationId();
        members.acceptInvitation(userId, invitation);
    }

    private static List<String> roles(SharedWallet.Details d) {
        return d.members().stream().map(m -> m.firstName() + ":" + m.role()).toList();
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertDenied(Action action, String why) {
        assertMessage(SharedWalletService.NOT_AVAILABLE_MESSAGE, action, why);
    }

    private static void assertMessage(String expected, Action action) {
        assertMessage(expected, action, "");
    }

    private static void assertMessage(String expected, Action action, String why) {
        ValidationException e = assertThrows(ValidationException.class, action::run, why);
        assertEquals(expected, e.getMessage(), why);
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
