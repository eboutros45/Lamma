package com.lamma.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Formats simulated balances for display. */
public final class Money {

    private Money() {
    }

    /** 0 -> "$0.00", 1234.5 -> "$1,234.50". */
    public static String formatUsd(BigDecimal amount) {
        NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);
        return usd.format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
