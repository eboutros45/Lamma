package com.lamma.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Shared-wallet data as seen by one member. */
public final class SharedWallet {

    private SharedWallet() {
    }

    /**
     * One shared wallet in a list.
     *
     * @param purpose      optional (null if not given)
     * @param description  optional (null if not given)
     * @param targetAmount optional (null if not given)
     * @param myRole       the viewing user's role in this wallet
     * @param giftRecipientName "First Last" of the gift recipient, or null if this isn't a gift wallet
     */
    public record Summary(long walletId, String name, BigDecimal balance, String purpose, String description,
                          BigDecimal targetAmount, MemberRole myRole, String giftRecipientName) {

        public Summary(long walletId, String name, BigDecimal balance, String purpose, String description,
                       BigDecimal targetAmount, MemberRole myRole) {
            this(walletId, name, balance, purpose, description, targetAmount, myRole, null);
        }

        public boolean isGift() {
            return giftRecipientName != null;
        }
    }

    /** One member of a shared wallet. No PIN, balance or other private data. */
    public record Member(long userId, String firstName, String lastName, MemberRole role, LocalDateTime joinedAt) {

        public String fullName() {
            return firstName + " " + lastName;
        }
    }

    /** A shared wallet with its members (owner first). */
    public record Details(Summary wallet, List<Member> members) {
    }
}
