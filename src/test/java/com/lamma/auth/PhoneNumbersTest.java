package com.lamma.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            // The three required Lebanese examples
            "03 123 456        | 9613123456",
            "+961 3 123 456    | 9613123456",
            "9613123456        | 9613123456",
            // Other ways of writing the same Lebanese number
            "03-123-456        | 9613123456",
            "(03) 123456       | 9613123456",
            "00961 3 123 456   | 9613123456",
            "+961 03 123 456   | 9613123456",
            "  03123456        | 9613123456",
            // Eight-digit Lebanese mobiles, normally written without a leading 0
            "70 123 456        | 96170123456",
            "81123456          | 96181123456",
            "070 123 456       | 96170123456",
            "+961 70 123 456   | 96170123456",
            // Foreign numbers must carry an explicit + or 00 prefix
            "+1 514 555 0100   | 15145550100",
            "0044 20 7946 0958 | 442079460958",
    })
    void normalizesToInternationalDigits(String input, String expected) throws ValidationException {
        assertEquals(expected, PhoneNumbers.normalize(input));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {
            "03 12a 456",        // letters
            "03 12",             // too short for Lebanon
            "03 123 456 789",    // too long for Lebanon
            "+961 3 12",         // too short for Lebanon
            "3123456",           // no 0, no country code: ambiguous
            "72123456",          // 8 digits but not a Lebanese mobile prefix: ambiguous
            "15145550100",       // foreign number without + : ambiguous, not guessed
            "07700 900123",      // UK local format: not silently treated as Lebanese
            "+1 555 0100",       // too short internationally
            "+1234567890123456", // more than 15 digits
            "+0 123 456 7890",   // country code cannot start with 0
            "+",
            "++961 3 123 456",
    })
    void rejectsInvalidOrAmbiguousNumbers(String input) {
        assertThrows(ValidationException.class, () -> PhoneNumbers.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankIsRequiredError(String input) {
        ValidationException e = assertThrows(ValidationException.class, () -> PhoneNumbers.normalize(input));
        assertEquals("Phone number is required.", e.getMessage());
    }

    @ParameterizedTest(name = "{0} displays as {1}")
    @CsvSource(delimiter = '|', value = {
            "9613123456   | +961 3 123 456",
            "96170123456  | +961 70 123 456",
            "15145550100  | +15145550100",
    })
    void formatsStoredNumbersForDisplay(String stored, String expected) {
        assertEquals(expected, PhoneNumbers.format(stored));
    }
}
