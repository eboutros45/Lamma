package com.lamma.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashes and verifies six-digit PINs with PBKDF2-HMAC-SHA256 (built into the JDK).
 *
 * <p>Stored format: {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>}.
 * Every PIN gets its own random 16-byte salt, and the iteration count is stored with the
 * hash so it can be raised later without breaking existing accounts.
 *
 * <p>A six-digit PIN has only one million possibilities, so hashing alone cannot protect it
 * if the database file is stolen. The sign-in lockout is what limits guessing in the app.
 * This is adequate for a simulated-money prototype, not for real financial security.
 */
public final class PinHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PinHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** Lower iteration counts are only meant for fast automated tests. */
    public PinHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /** Returns true if {@code pin} matches {@code storedHash}. Never throws for bad input. */
    public boolean verify(String pin, String storedHash) {
        if (pin == null || storedHash == null) {
            return false;
        }
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(pin, salt, storedIterations);
            // Constant-time comparison: does not leak how many bytes matched.
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available in this Java runtime", e);
        } finally {
            spec.clearPassword();
        }
    }
}
