package com.lamma.cards;

/** Card brands LAMMA can recognise from the number's first digits. Stored by name in MySQL. */
public enum CardBrand {
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    DISCOVER("Discover"),
    OTHER("Card");

    private final String displayName;

    CardBrand(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Reads a stored brand; unknown values are shown as a generic card. */
    public static CardBrand fromStored(String value) {
        try {
            return CardBrand.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return OTHER;
        }
    }
}
