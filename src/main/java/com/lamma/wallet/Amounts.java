package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rules for money amounts typed by the user. Amounts are always {@link BigDecimal} with
 * 2 decimal places (cents); floating-point types are never used for money.
 */
public final class Amounts {

    public static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    /** Largest single cash top-up (simulated). */
    public static final BigDecimal MAX_CASH_TOP_UP = new BigDecimal("10000.00");
    /** Largest value a DECIMAL(15,2) balance column can hold. */
    public static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999.99");

    private Amounts() {
    }

    /**
     * Parses a USD amount such as {@code 25}, {@code 25.5}, {@code 25.50} or {@code $25.50}.
     * Rejects negatives, letters, thousands separators, and more than 2 decimal places.
     */
    public static BigDecimal parseUsd(String input) throws ValidationException {
        if (input == null || input.isBlank()) {
            throw new ValidationException("Enter an amount.");
        }
        String text = input.strip();
        if (text.startsWith("$")) {
            text = text.substring(1).strip();
        }
        if (text.startsWith("-")) {
            throw new ValidationException("The amount must be greater than zero.");
        }
        if (text.matches("[0-9]*\\.[0-9]{3,}")) {
            throw new ValidationException("Amounts can have at most 2 decimal places (cents).");
        }
        if (!text.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) {
            throw new ValidationException("Enter a dollar amount using numbers only, like 25 or 25.50.");
        }
        return new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Checks the limits for one cash top-up. */
    public static void checkCashTopUp(BigDecimal amount) throws ValidationException {
        if (amount == null) {
            throw new ValidationException("Enter an amount.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("Amounts can have at most 2 decimal places (cents).");
        }
        if (amount.compareTo(MIN_AMOUNT) < 0) {
            throw new ValidationException("The amount must be at least $0.01.");
        }
        if (amount.compareTo(MAX_CASH_TOP_UP) > 0) {
            throw new ValidationException("A single cash top-up can be at most " + Money.formatUsd(MAX_CASH_TOP_UP) + ".");
        }
    }
}
