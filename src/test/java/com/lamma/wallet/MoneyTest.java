package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsAsUsd() {
        assertEquals("$0.00", Money.formatUsd(new BigDecimal("0.00")));
        assertEquals("$0.00", Money.formatUsd(BigDecimal.ZERO));
        assertEquals("$0.05", Money.formatUsd(new BigDecimal("0.05")));
        assertEquals("$1,234.50", Money.formatUsd(new BigDecimal("1234.5")));
        assertEquals("$9,999,999,999,999.99", Money.formatUsd(new BigDecimal("9999999999999.99")));
    }
}
