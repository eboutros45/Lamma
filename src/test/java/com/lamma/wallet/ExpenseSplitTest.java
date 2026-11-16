package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.ValidationException;
import com.lamma.wallet.ExpenseService.Collection;
import com.lamma.wallet.ExpenseService.NewExpense;
import com.lamma.wallet.ExpenseService.SplitMethod;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Equal and custom splits in exact cents (no database). */
class ExpenseSplitTest {

    @Test
    void equalSplitGivesRemainderCentsToTheLowestIdsFirst() throws Exception {
        assertEquals(Map.of(1L, usd("3.34"), 2L, usd("3.33"), 3L, usd("3.33")),
                ExpenseService.equalSplit(usd("10.00"), List.of(3L, 1L, 2L)));
        assertEquals(List.of(1L, 2L, 3L), new ArrayList<>(ExpenseService.equalSplit(usd("10"), List.of(3L, 2L, 1L)).keySet()),
                "the same order whatever the input order");
        Map<Long, BigDecimal> seven = ExpenseService.equalSplit(usd("100"), List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertEquals(usd("100.00"), seven.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(usd("14.29"), seven.get(1L));
        assertEquals(usd("14.28"), seven.get(7L));
        assertEquals(Map.of(5L, usd("0.03")), ExpenseService.equalSplit(usd("0.03"), List.of(5L)));
        assertTrue(ExpenseService.equalSplit(usd("0.02"), List.of(1L, 2L)).values().stream()
                .allMatch(v -> v.equals(usd("0.01"))));
    }

    @Test
    void equalSplitAddsUpExactlyForManyTotals() throws Exception {
        for (int cents = 3; cents < 2000; cents += 7) {
            BigDecimal total = BigDecimal.valueOf(cents).movePointLeft(2);
            for (int n = 1; n <= 3 && n <= cents; n++) {
                List<Long> people = new ArrayList<>();
                for (long i = 1; i <= n; i++) {
                    people.add(i * 10);
                }
                Map<Long, BigDecimal> shares = ExpenseService.equalSplit(total, people);
                assertEquals(total, shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2));
                BigDecimal max = shares.values().stream().max(BigDecimal::compareTo).orElseThrow();
                BigDecimal min = shares.values().stream().min(BigDecimal::compareTo).orElseThrow();
                assertTrue(max.subtract(min).compareTo(usd("0.01")) <= 0, "shares differ by at most one cent");
            }
        }
    }

    @Test
    void tooSmallToSplitOrNobodyChosen() {
        assertThrows(ValidationException.class, () -> ExpenseService.equalSplit(usd("0.02"), List.of(1L, 2L, 3L)));
        assertThrows(ValidationException.class, () -> ExpenseService.equalSplit(usd("5"), List.of()));
    }

    @Test
    void customSharesMustBeCentsAndAddUpExactly() throws Exception {
        assertEquals(Map.of(1L, usd("12.50"), 2L, usd("7.50")),
                ExpenseService.shares(custom("20", Map.of(1L, usd("12.5"), 2L, usd("7.50")))));
        assertMessage("The shares add up to $19.99, but the total is $20.00. They must match exactly.",
                () -> ExpenseService.shares(custom("20", Map.of(1L, usd("12.49"), 2L, usd("7.50")))));
        assertThrows(ValidationException.class, () -> ExpenseService.shares(custom("20", Map.of(1L, usd("12.505"), 2L, usd("7.495")))));
        assertMessage("Each participant's share must be at least $0.01.",
                () -> ExpenseService.shares(custom("20", Map.of(1L, usd("20"), 2L, usd("0")))));
        assertMessage("Enter an amount for every participant.",
                () -> ExpenseService.shares(custom("20", Map.of(1L, usd("20")))));
        assertMessage("Amounts were given for people who aren't participants.",
                () -> ExpenseService.shares(new NewExpense("Hotel", usd("20"), null, List.of(1L, 2L), SplitMethod.CUSTOM,
                        Map.of(1L, usd("10"), 2L, usd("5"), 3L, usd("5")), Collection.SHARED_WALLET, null)));
    }

    @Test
    void expenseFieldsAreValidated() {
        ValidationException e = assertThrows(ValidationException.class, () -> ExpenseService.shares(
                new NewExpense(" ", usd("0"), "x".repeat(501), List.of(), SplitMethod.EQUAL, null,
                        Collection.REIMBURSEMENT, null)));
        assertEquals(5, e.problems().size(), String.valueOf(e.problems()));
        assertThrows(ValidationException.class, () -> ExpenseService.shares(
                new NewExpense("Hotel", usd("10.001"), null, List.of(1L), SplitMethod.EQUAL, null, Collection.SHARED_WALLET, null)));
        assertThrows(ValidationException.class, () -> ExpenseService.shares(
                new NewExpense("Hotel", usd("10"), null, List.of(1L, 1L), SplitMethod.EQUAL, null, Collection.SHARED_WALLET, null)));
    }

    private static NewExpense custom(String total, Map<Long, BigDecimal> shares) {
        return new NewExpense("Hotel", usd(total), null, List.of(1L, 2L), SplitMethod.CUSTOM, shares,
                Collection.SHARED_WALLET, null);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertMessage(String expected, Action action) {
        assertEquals(expected, assertThrows(ValidationException.class, action::run).getMessage());
    }

    private static BigDecimal usd(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return value.scale() > 2 ? value : value.setScale(2); // keep invalid 3-decimal inputs as given
    }
}
