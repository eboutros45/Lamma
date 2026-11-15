package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.auth.ValidationException;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AmountsTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "25        | 25.00",
            "25.5      | 25.50",
            "25.50     | 25.50",
            "$25.50    | 25.50",
            "$ 7       | 7.00",
            " 0.01     | 0.01",
            "10000     | 10000.00",
    })
    void parsesValidAmountsToCents(String input, String expected) throws ValidationException {
        BigDecimal parsed = Amounts.parseUsd(input);
        assertEquals(new BigDecimal(expected), parsed);
        assertEquals(2, parsed.scale());
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {"", "   ", "-5", "$-5", "abc", "12.345", "1,000", "1e3", "12.", ".5", "5 00",
            "NaN", "Infinity", "1234567890"})
    void rejectsInvalidText(String input) {
        assertThrows(ValidationException.class, () -> Amounts.parseUsd(input));
    }

    @Test
    void cashTopUpLimits() throws ValidationException {
        Amounts.checkCashTopUp(new BigDecimal("0.01"));
        Amounts.checkCashTopUp(new BigDecimal("10000.00"));
        assertThrows(ValidationException.class, () -> Amounts.checkCashTopUp(new BigDecimal("0.00")));
        assertThrows(ValidationException.class, () -> Amounts.checkCashTopUp(new BigDecimal("-1.00")));
        assertThrows(ValidationException.class, () -> Amounts.checkCashTopUp(new BigDecimal("10000.01")));
        assertThrows(ValidationException.class, () -> Amounts.checkCashTopUp(new BigDecimal("1.001")));
        assertThrows(ValidationException.class, () -> Amounts.checkCashTopUp(null));
    }

    @Test
    void transferLimitsMatchTopUpLimits() throws ValidationException {
        Amounts.checkTransfer(new BigDecimal("0.01"));
        Amounts.checkTransfer(new BigDecimal("10000.00"));
        assertThrows(ValidationException.class, () -> Amounts.checkTransfer(new BigDecimal("0.00")));
        ValidationException tooMuch = assertThrows(ValidationException.class,
                () -> Amounts.checkTransfer(new BigDecimal("10000.01")));
        assertEquals("A single transfer can be at most $10,000.00.", tooMuch.getMessage());
    }
}
