package com.lamma.wallet;

/**
 * Roles of a shared-wallet member (stored by name in shared_wallet_members.member_role).
 *
 * <p>The permissions below are the approved product rules. In P01 only OWNER is ever
 * assigned (to the creator) and no money can move in or out of a shared wallet yet; later
 * phases check these methods before allowing contributions, transfers or administration.
 */
public enum MemberRole {
    /** Full wallet administration and financial permissions. */
    OWNER("Owner"),
    /** Default role for future members: can contribute, cannot withdraw. */
    CONTRIBUTOR("Contributor"),
    /** Can contribute and perform outgoing transfers. */
    SPENDER("Spender");

    private final String displayName;

    MemberRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean canContribute() {
        return true;
    }

    public boolean canSendOut() {
        return this == OWNER || this == SPENDER;
    }

    public boolean canAdminister() {
        return this == OWNER;
    }

    /** The role new members will get by default (invitations come in a later phase). */
    public static MemberRole defaultForNewMembers() {
        return CONTRIBUTOR;
    }
}
