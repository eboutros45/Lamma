package com.lamma.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.ValidationException;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CardValidatorTest {

    private static final YearMonth NOW = YearMonth.of(2026, 11);

    private static CardMetadata valid(String number, String cvv) throws ValidationException {
        return CardValidator.validate("Lina Haddad", number, "08/29", cvv, NOW);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "4242 4242 4242 4242, VISA, 123, 4242",
            "4111-1111-1111-1111, VISA, 123, 1111",
            "4222222222222, VISA, 123, 2222",
            "5555555555554444, MASTERCARD, 123, 4444",
            "2223003122003222, MASTERCARD, 123, 3222",
            "378282246310005, AMEX, 1234, 0005",
            "6011111111111117, DISCOVER, 123, 1117",
            "3530111333300000, OTHER, 123, 0000",
    })
    void acceptsStructurallyValidCardsAndKeepsOnlyLastFour(String number, CardBrand brand, String cvv, String last4)
            throws ValidationException {
        CardMetadata card = valid(number, cvv);
        assertEquals(brand, card.brand());
        assertEquals(last4, card.last4());
        assertEquals("Lina Haddad", card.cardholderName());
        assertEquals(8, card.expMonth());
        assertEquals(2029, card.expYear());
        String digits = number.replaceAll("[ -]", "");
        assertFalse(card.toString().contains(digits), "metadata must not contain the full number");
        assertFalse(card.toString().contains(digits.substring(0, 8)));
    }

    @ParameterizedTest(name = "rejects number {0}")
    @ValueSource(strings = {"4242424242424241", "1234 5678 9012 3456", "4242", "42424242424242424242",
            "4242-4242-4242-424x", "37828224631000", "555555555555444"})
    void rejectsInvalidNumbers(String number) {
        assertThrows(ValidationException.class, () -> valid(number, "123"));
    }

    @Test
    void expiryRules() throws ValidationException {
        assertEquals(2026, CardValidator.validate("A B", "4242424242424242", "11/26", "123", NOW).expYear(),
                "valid through the end of the current month");
        assertEquals(2030, CardValidator.validate("A B", "4242424242424242", "1/2030", "123", NOW).expYear());
        assertEquals(12, CardValidator.validate("A B", "4242424242424242", "12 - 27", "123", NOW).expMonth());
        ValidationException expired = assertThrows(ValidationException.class,
                () -> CardValidator.validate("A B", "4242424242424242", "10/26", "123", NOW));
        assertTrue(expired.problems().contains("This card has expired."));
        for (String bad : new String[] {"13/28", "00/28", "0828", "08/2", "aa/bb", "08/50", ""}) {
            assertThrows(ValidationException.class,
                    () -> CardValidator.validate("A B", "4242424242424242", bad, "123", NOW), bad);
        }
    }

    @Test
    void cvvRules() throws ValidationException {
        assertThrows(ValidationException.class, () -> valid("4242424242424242", "12"));
        assertThrows(ValidationException.class, () -> valid("4242424242424242", "1234"));
        assertThrows(ValidationException.class, () -> valid("4242424242424242", "12a"));
        assertThrows(ValidationException.class, () -> valid("378282246310005", "123"), "Amex needs 4 digits");
        valid("3530111333300000", "1234"); // unknown brand: 3 or 4 digits
    }

    @Test
    void cardholderNameRules() throws ValidationException {
        assertEquals("Lina Haddad", CardValidator.validate("  Lina   Haddad ", "4242424242424242", "08/29", "123", NOW)
                .cardholderName());
        assertEquals("Renée O'Brien-Khoury", CardValidator.validate("Renée O'Brien-Khoury",
                "4242424242424242", "08/29", "123", NOW).cardholderName());
        for (String bad : new String[] {"", " ", "A", "Lina123", "x".repeat(61), "---"}) {
            assertThrows(ValidationException.class,
                    () -> CardValidator.validate(bad, "4242424242424242", "08/29", "123", NOW), bad);
        }
    }

    @Test
    void reportsAllProblemsAtOnce() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> CardValidator.validate("", "", "", "", NOW));
        assertEquals(4, e.problems().size());
    }

    @Test
    void displayHelpers() throws ValidationException {
        CardMetadata card = valid("4242424242424242", "123");
        assertEquals("Visa •••• 4242", card.masked());
        assertEquals("08/29", card.expiry());
        assertFalse(card.isExpired(YearMonth.of(2029, 8)));
        assertTrue(card.isExpired(YearMonth.of(2029, 9)));
    }
}
