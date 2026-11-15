package com.lamma.cards;

import java.time.YearMonth;

/**
 * The only card information LAMMA keeps: who the card belongs to, its brand, last four
 * digits and expiry. There is deliberately no field for the full number or the CVV.
 */
public record CardMetadata(String cardholderName, CardBrand brand, String last4, int expMonth, int expYear) {

    /** e.g. "Visa •••• 4242". */
    public String masked() {
        return brand.displayName() + " •••• " + last4;
    }

    /** e.g. "08/29". */
    public String expiry() {
        return String.format("%02d/%02d", expMonth, expYear % 100);
    }

    /** A card is valid through the last day of its expiry month. */
    public boolean isExpired(YearMonth currentMonth) {
        return YearMonth.of(expYear, expMonth).isBefore(currentMonth);
    }
}
