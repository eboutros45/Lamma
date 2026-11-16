package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.wallet.WalletTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Text of transaction rows (no display needed). */
class TransactionLabelsTest {

    private static final LocalDateTime UTC = LocalDateTime.of(2026, 11, 15, 18, 41);

    private static WalletTransaction transfer(String type, String direction, String transferId, String counterparty,
                                              String kind, String by, String expense) {
        return new WalletTransaction(1, 1, type, direction, new BigDecimal("15.00"), BigDecimal.ZERO, "COMPLETED", UTC,
                null, null, transferId, 2L, counterparty, kind, by, expense);
    }

    @Test
    void descriptionsNameTheOtherSideWhoStartedItAndTheExpense() {
        assertEquals("To Sami Aoun · by Rami Khoury", TransactionLabels.description(transfer("TRANSFER_OUT", "DEBIT",
                "t1", "Sami Aoun", "SHARED_SEND", "Rami Khoury", null)));
        assertEquals("From Beirut Trip · sent by Lina Haddad", TransactionLabels.description(transfer("TRANSFER_IN",
                "CREDIT", "gift-delivery-16", "Beirut Trip", "GIFT_DELIVERY", "Lina Haddad", null)));
        assertEquals("From Nour Saad · for “Hotel”", TransactionLabels.description(transfer("TRANSFER_IN",
                "CREDIT", "expense-request-7", "Nour Saad", "EXPENSE_PAYMENT", "Nour Saad", "Hotel")));
        assertEquals("To Rami Khoury", TransactionLabels.description(transfer("TRANSFER_OUT", "DEBIT", "abc", "Rami Khoury",
                null, null, null)), "personal Send Money is unchanged");
        WalletTransaction card = new WalletTransaction(1, 1, "CARD_TOP_UP", "CREDIT", BigDecimal.TEN, BigDecimal.TEN,
                "COMPLETED", UTC, "VISA", "4242");
        assertEquals("Visa •••• 4242", TransactionLabels.description(card));
        WalletTransaction cash = new WalletTransaction(1, 1, "CASH_TOP_UP", "CREDIT", BigDecimal.TEN, BigDecimal.TEN,
                "COMPLETED", UTC);
        assertEquals("", TransactionLabels.description(cash), "a cash top-up needs no description");
        assertEquals(TransactionLabels.dateTime(UTC), TransactionLabels.timeAndReference(cash, false), "no reference");
    }

    @Test
    void referencesAreShortAndUniquePerKind() {
        assertEquals("GIFT-16", TransactionLabels.reference("gift-delivery-16"));
        assertEquals("EXP-7", TransactionLabels.reference("expense-request-7"));
        assertEquals("F96ADC6D", TransactionLabels.reference("f96adc6d-1234-5678-9abc-def012345678"));
        String line = TransactionLabels.timeAndReference(transfer("TRANSFER_OUT", "DEBIT", "expense-request-7", "X",
                "EXPENSE_PAYMENT", "X", "Hotel"), true);
        assertTrue(line.endsWith(" · Ref EXP-7"), line);
        assertEquals(TransactionLabels.time(UTC) + " · Ref EXP-7", line, "time only under a day heading");
    }

    @Test
    void dayHeadingsAreTodayYesterdayOrTheDate() {
        LocalDate localDay = UTC.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        assertEquals("Today", TransactionLabels.day(UTC, localDay));
        assertEquals("Yesterday", TransactionLabels.day(UTC, localDay.plusDays(1)));
        String older = TransactionLabels.day(UTC, localDay.plusDays(5));
        assertTrue(older.matches("[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2}, 2026"), older);
    }
}
