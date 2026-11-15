package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PinHasherTest {

    private final PinHasher hasher = new PinHasher(1_000); // low work factor keeps tests fast

    @Test
    void hashIsNotThePlaintextPin() {
        String hash = hasher.hash("123456");
        assertFalse(hash.contains("123456"));
        assertTrue(hash.startsWith("pbkdf2-sha256$1000$"));
        assertTrue(hash.length() >= 32, "must satisfy the database pin_hash length check");
    }

    @Test
    void samePinGetsDifferentHashesBecauseOfRandomSalt() {
        assertNotEquals(hasher.hash("123456"), hasher.hash("123456"));
    }

    @Test
    void verifiesCorrectPinAndRejectsWrongPin() {
        String hash = hasher.hash("123456");
        assertTrue(hasher.verify("123456", hash));
        assertFalse(hasher.verify("123457", hash));
        assertFalse(hasher.verify("", hash));
    }

    @Test
    void verifyUsesIterationCountStoredInTheHash() {
        String hashFromOtherSetting = new PinHasher(2_000).hash("654321");
        assertTrue(hasher.verify("654321", hashFromOtherSetting));
    }

    @Test
    void defaultWorkFactorIsUsedByDefault() {
        assertTrue(new PinHasher().hash("123456").startsWith("pbkdf2-sha256$" + PinHasher.DEFAULT_ITERATIONS + "$"));
    }

    @Test
    void malformedStoredHashesAreRejectedNotThrown() {
        assertFalse(hasher.verify("123456", null));
        assertFalse(hasher.verify("123456", "123456"));
        assertFalse(hasher.verify("123456", "pbkdf2-sha256$abc$xx$yy"));
        assertFalse(hasher.verify("123456", "pbkdf2-sha256$1000$***$***"));
        assertFalse(hasher.verify(null, hasher.hash("123456")));
    }

    @Test
    void pinFormatIsExactlySixAsciiDigits() {
        assertTrue(Pins.isValidFormat("000000"));
        assertTrue(Pins.isValidFormat("123456"));
        assertFalse(Pins.isValidFormat("12345"));
        assertFalse(Pins.isValidFormat("1234567"));
        assertFalse(Pins.isValidFormat("12345a"));
        assertFalse(Pins.isValidFormat("12 456"));
        assertFalse(Pins.isValidFormat("١٢٣٤٥٦")); // Arabic-Indic digits
        assertFalse(Pins.isValidFormat(null));
    }
}
