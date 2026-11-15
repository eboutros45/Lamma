package com.lamma.auth;

/**
 * Turns a phone number as typed by a person into the single format stored in the
 * database: international digits only, country code first, no "+" (E.164 without "+").
 *
 * <p>Accepted input (spaces, dashes, dots and brackets are ignored):
 * <ul>
 *   <li>{@code +<country code><number>} or {@code 00<country code><number>} - any country.</li>
 *   <li>{@code 0<number>} - a Lebanese local number, e.g. {@code 03 123 456 -> 9613123456}.</li>
 *   <li>{@code 961<number>} - a Lebanese number already in international digits.</li>
 *   <li>An 8-digit Lebanese mobile written without 0, starting with 70, 71, 76, 78, 79 or 81
 *       (e.g. {@code 70 123 456 -> 96170123456}). An 8-digit number cannot be a complete
 *       international number, so this never reinterprets a foreign number.</li>
 * </ul>
 * Any other bare digits (e.g. {@code 3123456} or {@code 15145550100}) are rejected as
 * ambiguous: we do not guess whether they are Lebanese or foreign.
 *
 * <p>The same method is used by registration and sign-in, so every way of writing a
 * number maps to the same account.
 */
public final class PhoneNumbers {

    public static final String LEBANON_COUNTRY_CODE = "961";

    /** Lebanese 8-digit mobile numbers are written locally without a leading 0. */
    private static final String LEBANESE_MOBILE_8_DIGITS = "(70|71|76|78|79|81)[0-9]{6}";

    /** Matches the database CHECK constraint chk_users_phone_number_format. */
    private static final int MIN_DIGITS = 10;
    private static final int MAX_DIGITS = 15;

    private PhoneNumbers() {
    }

    public static String normalize(String input) throws ValidationException {
        if (input == null || input.isBlank()) {
            throw new ValidationException("Phone number is required.");
        }
        String text = input.strip();
        boolean hasPlus = text.startsWith("+");
        if (hasPlus) {
            text = text.substring(1);
        }
        String digits = text.replaceAll("[\\s\\-.()]", "");
        if (!digits.matches("[0-9]+")) {
            throw new ValidationException(
                    "Phone number may only contain digits, spaces, dashes, brackets and a leading +.");
        }

        String international;
        if (hasPlus) {
            international = digits;
        } else if (digits.startsWith("00")) {
            international = digits.substring(2);
        } else if (digits.startsWith("0")) {
            return lebanese(digits.substring(1), true);
        } else if (digits.startsWith(LEBANON_COUNTRY_CODE)) {
            international = digits;
        } else if (digits.matches(LEBANESE_MOBILE_8_DIGITS)) {
            return LEBANON_COUNTRY_CODE + digits;
        } else {
            throw new ValidationException("Phone number is incomplete. Use a Lebanese number such as "
                    + "03 123 456 or 70 123 456, or start with + and the country code.");
        }

        if (international.startsWith(LEBANON_COUNTRY_CODE)) {
            return lebanese(international.substring(LEBANON_COUNTRY_CODE.length()), false);
        }
        if (international.startsWith("0")) {
            throw new ValidationException("A country code cannot start with 0.");
        }
        if (international.length() < MIN_DIGITS || international.length() > MAX_DIGITS) {
            throw new ValidationException("International phone numbers must have " + MIN_DIGITS + " to "
                    + MAX_DIGITS + " digits including the country code.");
        }
        return international;
    }

    /**
     * Lebanese numbers have 7 digits (e.g. 3 123 456, 1 234 567) or 8 digits
     * (e.g. 70 123 456) after the country code / leading 0.
     */
    private static String lebanese(String national, boolean typedAsLocal) throws ValidationException {
        if (!typedAsLocal && national.startsWith("0")) {
            national = national.substring(1); // "+961 03 123 456" is a common way of writing it
        }
        if (national.length() < 7 || national.length() > 8 || national.startsWith("0")) {
            String hint = typedAsLocal
                    ? " Numbers starting with 0 are read as Lebanese local numbers; for another country, "
                      + "start with + and the country code."
                    : "";
            throw new ValidationException("This is not a valid Lebanese phone number "
                    + "(expected 7 or 8 digits after 0 or +961)." + hint);
        }
        return LEBANON_COUNTRY_CODE + national;
    }

    /**
     * Formats a stored (normalized) number for display: Lebanese numbers as
     * "+961 3 123 456" / "+961 70 123 456", others as "+" followed by the digits.
     */
    public static String format(String normalized) {
        if (normalized == null) {
            return "";
        }
        if (normalized.startsWith(LEBANON_COUNTRY_CODE)) {
            String n = normalized.substring(LEBANON_COUNTRY_CODE.length());
            if (n.length() == 7) {
                return "+961 " + n.substring(0, 1) + " " + n.substring(1, 4) + " " + n.substring(4);
            }
            if (n.length() == 8) {
                return "+961 " + n.substring(0, 2) + " " + n.substring(2, 5) + " " + n.substring(5);
            }
        }
        return "+" + normalized;
    }
}
