package com.lamma.wallet;

import com.lamma.auth.ValidationException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Shared-wallet QR invitation codes.
 *
 * <p>A code is 24 random URL-safe characters (18 random bytes), unrelated to the wallet id.
 * The QR code contains {@code lamma://join/<code>}; a future camera scanner reads that text,
 * and users can also type or paste either the full text or just the code.
 */
public final class InvitationCodes {

    public static final String PREFIX = "lamma://join/";
    private static final SecureRandom RANDOM = new SecureRandom();

    private InvitationCodes() {
    }

    static String newCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 24 characters
    }

    /** The text placed in the QR code. */
    public static String payload(String code) {
        return PREFIX + code;
    }

    /** Accepts "lamma://join/CODE" or "CODE" (surrounding spaces ignored). */
    public static String parse(String input) throws ValidationException {
        String text = input == null ? "" : input.strip();
        if (text.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            text = text.substring(PREFIX.length());
        }
        if (text.isEmpty()) {
            throw new ValidationException("Enter the invitation code or paste the QR invitation link.");
        }
        if (!text.matches("[A-Za-z0-9_-]{24}")) {
            throw new ValidationException(SharedWalletMembershipService.INVALID_CODE_MESSAGE);
        }
        return text;
    }
}
