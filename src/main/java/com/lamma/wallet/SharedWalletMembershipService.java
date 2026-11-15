package com.lamma.wallet;

import com.lamma.auth.PhoneNumbers;
import com.lamma.auth.ValidationException;
import com.lamma.db.Database;
import com.lamma.db.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Who belongs to a shared wallet: direct invitations, QR join requests, roles, removal and
 * leaving.
 *
 * <p>Every change runs in one transaction that first locks the wallet's
 * {@code shared_wallets} row ({@code SELECT ... FOR UPDATE}). Membership changes to one wallet
 * therefore happen one at a time, and each one re-checks, in the database, the caller's role
 * and the current state of the invitation or request. Screens may be out of date; this class
 * never trusts them. A user who is not a member gets the same "not available" answer as for a
 * wallet that does not exist.
 *
 * <p>Rules: new members join as CONTRIBUTOR; only the OWNER invites, approves, changes roles
 * (CONTRIBUTOR and SPENDER only) and removes members; the OWNER cannot leave, be removed or be
 * re-roled; pending invitations and requests give no access.
 */
public class SharedWalletMembershipService {

    static final String OWNER_ONLY_MESSAGE = "Only the wallet's Owner can do this.";
    static final String INVITATION_NOT_AVAILABLE_MESSAGE = "This invitation isn't available.";
    static final String INVITATION_CLOSED_MESSAGE =
            "This invitation is no longer open. It may have been cancelled, declined or already accepted.";
    static final String REQUEST_NOT_AVAILABLE_MESSAGE = "This join request isn't available.";
    static final String REQUEST_CLOSED_MESSAGE = "This join request has already been handled.";
    static final String INVALID_CODE_MESSAGE =
            "This invitation code isn't valid. It may have been turned off or replaced by the Owner.";
    static final String NO_ACCOUNT_MESSAGE = "No LAMMA account uses this phone number. Check the number and try again.";
    static final String ALREADY_MEMBER_MESSAGE = "This person is already a member of the wallet.";
    static final String ALREADY_INVITED_MESSAGE = "This person already has a pending invitation to this wallet.";
    static final String HAS_PENDING_REQUEST_MESSAGE =
            "This person has already asked to join with the QR invitation. Review their request instead.";
    static final String YOU_ARE_MEMBER_MESSAGE = "You're already a member of this wallet.";
    static final String YOU_REQUESTED_MESSAGE = "You've already asked to join this wallet. The Owner will review your request.";
    static final String YOU_ARE_INVITED_MESSAGE =
            "You've already been invited to this wallet. Accept the invitation from Wallets → Invitations.";
    static final String OWNER_FIXED_MESSAGE = "The Owner can't be removed, leave, or change role.";
    static final String NOT_A_MEMBER_MESSAGE = "That person is no longer a member of this wallet.";
    static final String ROLE_NOT_ALLOWED_MESSAGE = "Members can only be Contributors or Spenders.";
    static final String FAILED_MESSAGE =
            "The change could not be saved because of a database problem, so nothing was changed. "
            + "Check that MySQL is running and try again.";

    /** An invitation waiting for the signed-in user's answer. */
    public record IncomingInvitation(long invitationId, long walletId, String walletName, String invitedBy,
                                     LocalDateTime createdAt) {
    }

    /** A pending invitation as the Owner sees it. */
    public record OutgoingInvitation(long invitationId, String inviteeName, String inviteePhone,
                                     LocalDateTime createdAt) {
    }

    /** A pending QR join request as the Owner sees it. */
    public record JoinRequest(long requestId, long requesterUserId, String requesterName, String requesterPhone,
                              LocalDateTime createdAt) {
    }

    /** What a valid invitation code points to, shown before asking to join. */
    public record InvitationPreview(String walletName, String ownerName) {
    }

    private final Database database;
    private final Clock clock;

    public SharedWalletMembershipService(Database database) {
        this(database, Clock.systemUTC());
    }

    public SharedWalletMembershipService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    // =====================================================================
    // Direct invitations
    // =====================================================================

    /** The Owner invites a registered user by phone number. Returns the invitee's full name. */
    public String inviteByPhone(long ownerId, long walletId, String phone) throws ValidationException, DatabaseException {
        String normalized = PhoneNumbers.normalize(phone);
        return inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            long inviteeId;
            String inviteeName;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, CONCAT(first_name, ' ', last_name) FROM users WHERE phone_number = ?")) {
                ps.setString(1, normalized);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ValidationException(NO_ACCOUNT_MESSAGE);
                    }
                    inviteeId = rs.getLong(1);
                    inviteeName = rs.getString(2);
                }
            }
            if (inviteeId == ownerId) {
                throw new ValidationException("You're the Owner of this wallet, so you can't invite yourself.");
            }
            if (roleOf(conn, inviteeId, walletId) != null) {
                throw new ValidationException(ALREADY_MEMBER_MESSAGE);
            }
            if (exists(conn, "SELECT 1 FROM shared_wallet_invitations WHERE shared_wallet_id = ? AND invitee_user_id = ?"
                    + " AND status = 'PENDING'", walletId, inviteeId)) {
                throw new ValidationException(ALREADY_INVITED_MESSAGE);
            }
            if (exists(conn, "SELECT 1 FROM shared_wallet_join_requests WHERE shared_wallet_id = ?"
                    + " AND requester_user_id = ? AND status = 'PENDING'", walletId, inviteeId)) {
                throw new ValidationException(HAS_PENDING_REQUEST_MESSAGE);
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO shared_wallet_invitations"
                    + " (shared_wallet_id, invitee_user_id, invited_by_user_id, status, created_at)"
                    + " VALUES (?, ?, ?, 'PENDING', ?)")) {
                ps.setLong(1, walletId);
                ps.setLong(2, inviteeId);
                ps.setLong(3, ownerId);
                ps.setObject(4, now());
                ps.executeUpdate();
            }
            return inviteeName;
        });
    }

    /** Pending invitations addressed to this user (only theirs). */
    public List<IncomingInvitation> incomingInvitations(long userId) throws DatabaseException {
        String sql = "SELECT i.invitation_id, w.wallet_id, w.name, CONCAT(u.first_name, ' ', u.last_name), i.created_at"
                + " FROM shared_wallet_invitations i"
                + " JOIN wallets w ON w.wallet_id = i.shared_wallet_id AND w.status = 'ACTIVE'"
                + " JOIN users u ON u.user_id = i.invited_by_user_id"
                + " WHERE i.invitee_user_id = ? AND i.status = 'PENDING' ORDER BY i.created_at, i.invitation_id";
        return query(sql, ps -> ps.setLong(1, userId), rs -> new IncomingInvitation(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getObject(5, LocalDateTime.class)));
    }

    /** The invitee accepts: they become a CONTRIBUTOR. Returns the wallet id. */
    public long acceptInvitation(long userId, long invitationId) throws ValidationException, DatabaseException {
        return respond(userId, invitationId, true);
    }

    public void declineInvitation(long userId, long invitationId) throws ValidationException, DatabaseException {
        respond(userId, invitationId, false);
    }

    private long respond(long userId, long invitationId, boolean accept) throws ValidationException, DatabaseException {
        return inTransaction(conn -> {
            Long walletId = walletOf(conn, "SELECT shared_wallet_id FROM shared_wallet_invitations WHERE invitation_id = ?",
                    invitationId);
            if (walletId == null) {
                throw new ValidationException(INVITATION_NOT_AVAILABLE_MESSAGE);
            }
            lockWallet(conn, walletId, INVITATION_NOT_AVAILABLE_MESSAGE);
            long invitee;
            String status;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT invitee_user_id, status FROM shared_wallet_invitations WHERE invitation_id = ? FOR UPDATE")) {
                ps.setLong(1, invitationId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    invitee = rs.getLong(1);
                    status = rs.getString(2);
                }
            }
            if (invitee != userId) {
                throw new ValidationException(INVITATION_NOT_AVAILABLE_MESSAGE); // only the intended recipient
            }
            if (!"PENDING".equals(status)) {
                throw new ValidationException(INVITATION_CLOSED_MESSAGE);
            }
            if (accept && roleOf(conn, userId, walletId) == null) {
                insertMember(conn, userId, walletId, MemberRole.defaultForNewMembers(), now());
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE shared_wallet_invitations SET status = ?,"
                    + " responded_at = ? WHERE invitation_id = ?")) {
                ps.setString(1, accept ? "ACCEPTED" : "DECLINED");
                ps.setObject(2, now());
                ps.setLong(3, invitationId);
                ps.executeUpdate();
            }
            return walletId;
        });
    }

    /** Pending invitations of a wallet (Owner only). */
    public List<OutgoingInvitation> pendingInvitations(long ownerId, long walletId)
            throws ValidationException, DatabaseException {
        requireOwnerReadOnly(ownerId, walletId);
        String sql = "SELECT i.invitation_id, CONCAT(u.first_name, ' ', u.last_name), u.phone_number, i.created_at"
                + " FROM shared_wallet_invitations i JOIN users u ON u.user_id = i.invitee_user_id"
                + " WHERE i.shared_wallet_id = ? AND i.status = 'PENDING' ORDER BY i.created_at, i.invitation_id";
        return query(sql, ps -> ps.setLong(1, walletId), rs -> new OutgoingInvitation(rs.getLong(1), rs.getString(2),
                PhoneNumbers.format(rs.getString(3)), rs.getObject(4, LocalDateTime.class)));
    }

    /** The Owner withdraws a pending invitation; it can no longer be accepted. */
    public void cancelInvitation(long ownerId, long invitationId) throws ValidationException, DatabaseException {
        inTransaction(conn -> {
            Long walletId = walletOf(conn, "SELECT shared_wallet_id FROM shared_wallet_invitations WHERE invitation_id = ?",
                    invitationId);
            if (walletId == null) {
                throw new ValidationException(INVITATION_NOT_AVAILABLE_MESSAGE);
            }
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            int updated;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE shared_wallet_invitations SET status = 'CANCELLED',"
                    + " responded_at = ? WHERE invitation_id = ? AND status = 'PENDING'")) {
                ps.setObject(1, now());
                ps.setLong(2, invitationId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                throw new ValidationException(INVITATION_CLOSED_MESSAGE);
            }
            return null;
        });
    }

    // =====================================================================
    // QR invitations and join requests
    // =====================================================================

    /** The wallet's current invitation code, or null if QR invitations are off (Owner only). */
    public String currentInviteCode(long ownerId, long walletId) throws ValidationException, DatabaseException {
        return inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            return inviteCode(conn, walletId);
        });
    }

    /** Turns QR invitations on with a brand-new code; any previous code stops working (Owner only). */
    public String newInviteCode(long ownerId, long walletId) throws ValidationException, DatabaseException {
        return inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            String code = InvitationCodes.newCode();
            setInviteCode(conn, walletId, code);
            return code;
        });
    }

    /** Turns QR invitations off; the current code stops working (Owner only). */
    public void turnOffInviteCode(long ownerId, long walletId) throws ValidationException, DatabaseException {
        inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            setInviteCode(conn, walletId, null);
            return null;
        });
    }

    /** Checks a code and says which wallet it is for, without changing anything. */
    public InvitationPreview previewInvitation(long userId, String codeOrLink) throws ValidationException, DatabaseException {
        String code = InvitationCodes.parse(codeOrLink);
        return inTransaction(conn -> {
            long walletId = walletForCode(conn, code);
            checkCanRequest(conn, userId, walletId);
            String sql = "SELECT w.name, CONCAT(u.first_name, ' ', u.last_name) FROM wallets w"
                    + " JOIN shared_wallet_members m ON m.shared_wallet_id = w.wallet_id AND m.member_role = 'OWNER'"
                    + " JOIN users u ON u.user_id = m.user_id WHERE w.wallet_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new InvitationPreview(rs.getString(1), rs.getString(2));
                }
            }
        });
    }

    /** Asks to join using a QR invitation code. Creates a PENDING request; gives no access. */
    public String requestToJoin(long userId, String codeOrLink) throws ValidationException, DatabaseException {
        String code = InvitationCodes.parse(codeOrLink);
        return inTransaction(conn -> {
            long walletId = walletForCode(conn, code);
            lockWallet(conn, walletId, INVALID_CODE_MESSAGE);
            if (!code.equals(inviteCode(conn, walletId))) {
                throw new ValidationException(INVALID_CODE_MESSAGE); // replaced or turned off meanwhile
            }
            checkCanRequest(conn, userId, walletId);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO shared_wallet_join_requests"
                    + " (shared_wallet_id, requester_user_id, status, created_at) VALUES (?, ?, 'PENDING', ?)")) {
                ps.setLong(1, walletId);
                ps.setLong(2, userId);
                ps.setObject(3, now());
                ps.executeUpdate();
            }
            return walletName(conn, walletId);
        });
    }

    /** Pending join requests of a wallet (Owner only). */
    public List<JoinRequest> pendingJoinRequests(long ownerId, long walletId) throws ValidationException, DatabaseException {
        requireOwnerReadOnly(ownerId, walletId);
        String sql = "SELECT r.request_id, u.user_id, CONCAT(u.first_name, ' ', u.last_name), u.phone_number, r.created_at"
                + " FROM shared_wallet_join_requests r JOIN users u ON u.user_id = r.requester_user_id"
                + " WHERE r.shared_wallet_id = ? AND r.status = 'PENDING' ORDER BY r.created_at, r.request_id";
        return query(sql, ps -> ps.setLong(1, walletId), rs -> new JoinRequest(rs.getLong(1), rs.getLong(2),
                rs.getString(3), PhoneNumbers.format(rs.getString(4)), rs.getObject(5, LocalDateTime.class)));
    }

    /** The Owner approves a request: the requester becomes a CONTRIBUTOR. */
    public void approveJoinRequest(long ownerId, long requestId) throws ValidationException, DatabaseException {
        decide(ownerId, requestId, true);
    }

    public void rejectJoinRequest(long ownerId, long requestId) throws ValidationException, DatabaseException {
        decide(ownerId, requestId, false);
    }

    private void decide(long ownerId, long requestId, boolean approve) throws ValidationException, DatabaseException {
        inTransaction(conn -> {
            Long walletId = walletOf(conn, "SELECT shared_wallet_id FROM shared_wallet_join_requests WHERE request_id = ?",
                    requestId);
            if (walletId == null) {
                throw new ValidationException(REQUEST_NOT_AVAILABLE_MESSAGE);
            }
            lockWallet(conn, walletId, REQUEST_NOT_AVAILABLE_MESSAGE);
            requireOwner(conn, ownerId, walletId);
            long requester;
            String status;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT requester_user_id, status FROM shared_wallet_join_requests WHERE request_id = ? FOR UPDATE")) {
                ps.setLong(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    requester = rs.getLong(1);
                    status = rs.getString(2);
                }
            }
            if (!"PENDING".equals(status)) {
                throw new ValidationException(REQUEST_CLOSED_MESSAGE); // never processed twice
            }
            if (approve && roleOf(conn, requester, walletId) == null) {
                insertMember(conn, requester, walletId, MemberRole.defaultForNewMembers(), now());
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE shared_wallet_join_requests SET status = ?,"
                    + " decided_at = ?, decided_by_user_id = ? WHERE request_id = ?")) {
                ps.setString(1, approve ? "APPROVED" : "REJECTED");
                ps.setObject(2, now());
                ps.setLong(3, ownerId);
                ps.setLong(4, requestId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // =====================================================================
    // Roles, removal, leaving
    // =====================================================================

    /** The Owner makes a member a CONTRIBUTOR or a SPENDER. */
    public void changeRole(long ownerId, long walletId, long memberId, MemberRole newRole)
            throws ValidationException, DatabaseException {
        if (newRole != MemberRole.CONTRIBUTOR && newRole != MemberRole.SPENDER) {
            throw new ValidationException(ROLE_NOT_ALLOWED_MESSAGE);
        }
        inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            MemberRole current = roleOf(conn, memberId, walletId);
            if (current == null) {
                throw new ValidationException(NOT_A_MEMBER_MESSAGE);
            }
            if (current == MemberRole.OWNER) {
                throw new ValidationException(OWNER_FIXED_MESSAGE);
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE shared_wallet_members SET member_role = ?"
                    + " WHERE user_id = ? AND shared_wallet_id = ?")) {
                ps.setString(1, newRole.name());
                ps.setLong(2, memberId);
                ps.setLong(3, walletId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** The Owner removes a member; their access ends immediately. They can be invited again later. */
    public void removeMember(long ownerId, long walletId, long memberId) throws ValidationException, DatabaseException {
        inTransaction(conn -> {
            lockWallet(conn, walletId);
            requireOwner(conn, ownerId, walletId);
            MemberRole current = roleOf(conn, memberId, walletId);
            if (current == null) {
                throw new ValidationException(NOT_A_MEMBER_MESSAGE);
            }
            if (current == MemberRole.OWNER) {
                throw new ValidationException(OWNER_FIXED_MESSAGE);
            }
            deleteMember(conn, memberId, walletId);
            return null;
        });
    }

    /** A member leaves; their access ends immediately. The Owner cannot leave. */
    public void leave(long userId, long walletId) throws ValidationException, DatabaseException {
        inTransaction(conn -> {
            lockWallet(conn, walletId);
            MemberRole role = roleOf(conn, userId, walletId);
            if (role == null) {
                throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
            }
            if (role == MemberRole.OWNER) {
                throw new ValidationException(OWNER_FIXED_MESSAGE);
            }
            deleteMember(conn, userId, walletId);
            return null;
        });
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Adds a membership row. Protected only so a test can simulate a failure here and check that
     * the invitation or request is not marked as accepted or approved.
     */
    protected void insertMember(Connection conn, long userId, long walletId, MemberRole role, LocalDateTime joinedAt)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO shared_wallet_members"
                + " (user_id, shared_wallet_id, member_role, joined_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            ps.setString(3, role.name());
            ps.setObject(4, joinedAt);
            ps.executeUpdate();
        }
    }

    private static void deleteMember(Connection conn, long userId, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM shared_wallet_members WHERE user_id = ? AND shared_wallet_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            ps.executeUpdate();
        }
    }

    /** Locks the active shared wallet's row; membership changes to one wallet run one at a time. */
    private static void lockWallet(Connection conn, long walletId) throws SQLException, ValidationException {
        lockWallet(conn, walletId, SharedWalletService.NOT_AVAILABLE_MESSAGE);
    }

    private static void lockWallet(Connection conn, long walletId, String notFound) throws SQLException, ValidationException {
        String sql = "SELECT s.shared_wallet_id FROM shared_wallets s JOIN wallets w ON w.wallet_id = s.shared_wallet_id"
                + " WHERE s.shared_wallet_id = ? AND w.wallet_type = 'SHARED' AND w.status = 'ACTIVE' FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ValidationException(notFound);
                }
            }
        }
    }

    private static MemberRole roleOf(Connection conn, long userId, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT member_role FROM shared_wallet_members WHERE user_id = ? AND shared_wallet_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? MemberRole.valueOf(rs.getString(1)) : null;
            }
        }
    }

    /** Non-members learn nothing (same answer as a missing wallet); members learn it's Owner-only. */
    private static void requireOwner(Connection conn, long userId, long walletId) throws SQLException, ValidationException {
        MemberRole role = roleOf(conn, userId, walletId);
        if (role == null) {
            throw new ValidationException(SharedWalletService.NOT_AVAILABLE_MESSAGE);
        }
        if (!role.canAdminister()) {
            throw new ValidationException(OWNER_ONLY_MESSAGE);
        }
    }

    private void requireOwnerReadOnly(long userId, long walletId) throws ValidationException, DatabaseException {
        try (Connection conn = connect()) {
            requireOwner(conn, userId, walletId);
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private static void checkCanRequest(Connection conn, long userId, long walletId) throws SQLException, ValidationException {
        if (roleOf(conn, userId, walletId) != null) {
            throw new ValidationException(YOU_ARE_MEMBER_MESSAGE);
        }
        if (exists(conn, "SELECT 1 FROM shared_wallet_join_requests WHERE shared_wallet_id = ? AND requester_user_id = ?"
                + " AND status = 'PENDING'", walletId, userId)) {
            throw new ValidationException(YOU_REQUESTED_MESSAGE);
        }
        if (exists(conn, "SELECT 1 FROM shared_wallet_invitations WHERE shared_wallet_id = ? AND invitee_user_id = ?"
                + " AND status = 'PENDING'", walletId, userId)) {
            throw new ValidationException(YOU_ARE_INVITED_MESSAGE);
        }
    }

    private static long walletForCode(Connection conn, String code) throws SQLException, ValidationException {
        String sql = "SELECT s.shared_wallet_id FROM shared_wallets s JOIN wallets w ON w.wallet_id = s.shared_wallet_id"
                + " WHERE s.invite_token = ? AND w.status = 'ACTIVE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ValidationException(INVALID_CODE_MESSAGE);
                }
                return rs.getLong(1);
            }
        }
    }

    private static String inviteCode(Connection conn, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT invite_token FROM shared_wallets WHERE shared_wallet_id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setInviteCode(Connection conn, long walletId, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE shared_wallets SET invite_token = ? WHERE shared_wallet_id = ?")) {
            ps.setString(1, code);
            ps.setLong(2, walletId);
            ps.executeUpdate();
        }
    }

    private static String walletName(Connection conn, long walletId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM wallets WHERE wallet_id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Long walletOf(Connection conn, String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static boolean exists(Connection conn, String sql, long a, long b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---------- transaction and query plumbing ----------

    private interface Work<T> {
        T run(Connection conn) throws SQLException, ValidationException, DatabaseException;
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** Runs the work in one transaction: commit on success, roll back on any failure. */
    private <T> T inTransaction(Work<T> work) throws ValidationException, DatabaseException {
        Connection conn = connect();
        try (conn) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | ValidationException | DatabaseException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private <T> List<T> query(String sql, Binder binder, Reader<T> reader) throws DatabaseException {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(reader.read(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }

    private Connection connect() throws DatabaseException {
        try {
            return database.getConnection();
        } catch (DatabaseException e) {
            throw new DatabaseException(FAILED_MESSAGE, e);
        }
    }
}
