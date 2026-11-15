package com.lamma.wallet;

import java.math.BigDecimal;

/** A user's PERSONAL wallet as stored in the wallets table. */
public record PersonalWallet(long walletId, long ownerUserId, BigDecimal balance) {
}
