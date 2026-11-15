package com.lamma.cards;

import com.lamma.auth.ValidationException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a card entered by the user and reduces it to {@link CardMetadata}.
 *
 * <p>The full card number and CVV are used only inside {@link #validate} (format, Luhn
 * checksum, brand) and are never returned, stored or logged. Any number that passes these
 * structural checks is accepted; there is no list of special demo cards and no real
 * authorisation, because LAMMA's payments are simulated.
 */
public final class CardValidator {

    static final int MAX_CARDHOLDER_LENGTH = 60;
    /** Expiry dates further ahead than this are treated as typos. */
    static final int MAX_YEARS_AHEAD = 20;

    private CardValidator() {
    }

    public static CardMetadata validate(String cardholderName, String cardNumber, String expiry, String cvv,
                                        YearMonth currentMonth) throws ValidationException {
        List<String> problems = new ArrayList<>();

        String holder = cardholderName == null ? "" : cardholderName.strip().replaceAll("\\s+", " ");
        if (holder.isEmpty()) {
            problems.add("Cardholder name is required.");
        } else if (holder.length() < 2 || holder.length() > MAX_CARDHOLDER_LENGTH
                || !holder.matches("[\\p{L} .'-]+") || !holder.matches(".*\\p{L}.*")) {
            problems.add("Cardholder name must be 2 to " + MAX_CARDHOLDER_LENGTH
                    + " characters and contain only letters, spaces, apostrophes, dots or dashes.");
        }

        CardBrand brand = null;
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
        if (digits.isEmpty()) {
            problems.add("Card number is required.");
        } else if (!digits.matches("[0-9]{12,19}")) {
            problems.add("Card number must be 12 to 19 digits.");
        } else {
            brand = detectBrand(digits);
            if (!hasValidLength(brand, digits.length())) {
                problems.add("This " + brand.displayName() + " card number has the wrong number of digits.");
                brand = null;
            } else if (!passesLuhn(digits)) {
                problems.add("Card number is not valid. Please check it for typos.");
                brand = null;
            }
        }

        int[] monthYear = null;
        if (expiry == null || expiry.isBlank()) {
            problems.add("Expiration date is required.");
        } else {
            monthYear = parseExpiry(expiry.strip());
            if (monthYear == null) {
                problems.add("Expiration date must look like MM/YY, for example 08/29.");
            } else {
                YearMonth expires = YearMonth.of(monthYear[1], monthYear[0]);
                if (expires.isBefore(currentMonth)) {
                    problems.add("This card has expired.");
                    monthYear = null;
                } else if (expires.isAfter(currentMonth.plusYears(MAX_YEARS_AHEAD))) {
                    problems.add("Expiration date is too far in the future. Please check it.");
                    monthYear = null;
                }
            }
        }

        String code = cvv == null ? "" : cvv.strip();
        if (code.isEmpty()) {
            problems.add("CVV is required.");
        } else if (brand == CardBrand.AMEX ? !code.matches("[0-9]{4}")
                : brand == CardBrand.OTHER ? !code.matches("[0-9]{3,4}") : !code.matches("[0-9]{3}")) {
            problems.add(brand == CardBrand.AMEX ? "CVV must be 4 digits for American Express."
                    : "CVV must be 3 digits.");
        }

        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return new CardMetadata(holder, brand, digits.substring(digits.length() - 4), monthYear[0], monthYear[1]);
    }

    static CardBrand detectBrand(String digits) {
        int first2 = Integer.parseInt(digits.substring(0, 2));
        int first3 = Integer.parseInt(digits.substring(0, 3));
        int first4 = Integer.parseInt(digits.substring(0, 4));
        if (digits.startsWith("4")) {
            return CardBrand.VISA;
        }
        if ((first2 >= 51 && first2 <= 55) || (first4 >= 2221 && first4 <= 2720)) {
            return CardBrand.MASTERCARD;
        }
        if (first2 == 34 || first2 == 37) {
            return CardBrand.AMEX;
        }
        if (first4 == 6011 || first2 == 65 || (first3 >= 644 && first3 <= 649)) {
            return CardBrand.DISCOVER;
        }
        return CardBrand.OTHER;
    }

    private static boolean hasValidLength(CardBrand brand, int length) {
        return switch (brand) {
            case VISA -> length == 13 || length == 16 || length == 19;
            case MASTERCARD -> length == 16;
            case AMEX -> length == 15;
            case DISCOVER -> length >= 16 && length <= 19;
            case OTHER -> length >= 12 && length <= 19;
        };
    }

    /** Luhn (mod 10) checksum used by payment card numbers to catch typos. */
    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** Accepts MM/YY, MM/YYYY (also with "-" or spaces). Returns {month, year} or null. */
    private static int[] parseExpiry(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]{1,2})\\s*[/-]\\s*([0-9]{2}|[0-9]{4})").matcher(text);
        if (!m.matches()) {
            return null;
        }
        int month = Integer.parseInt(m.group(1));
        int year = Integer.parseInt(m.group(2));
        if (m.group(2).length() == 2) {
            year += 2000;
        }
        if (month < 1 || month > 12 || year < 2000 || year > 2099) {
            return null;
        }
        return new int[] {month, year};
    }
}
