package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.PrintedTotals;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks in the runtime parse-correctness guard: abort iff printed balances exist AND don't reconcile. */
class StatementBalanceValidatorTest {

    private static RawBankTxn txn(String amt, Sign sign) {
        return new RawBankTxn(LocalDate.of(2026, 4, 10), "x", new BigDecimal(amt), sign);
    }

    private static ParsedBankStatement stmt(PrintedTotals totals, RawBankTxn... txns) {
        return new ParsedBankStatement("1234", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30),
                List.of(txns), totals);
    }

    @Test
    void balances_whenOpeningPlusCreditsMinusDebitsEqualsClosing() {
        // 100 + 50 (credit) - 30 (debit) = 120
        ParsedBankStatement s = stmt(
                new PrintedTotals(new BigDecimal("100.00"), new BigDecimal("120.00"), null, null),
                txn("50.00", Sign.CREDIT), txn("30.00", Sign.DEBIT));
        assertTrue(StatementBalanceValidator.hasPrintedBalances(s));
        assertTrue(StatementBalanceValidator.balances(s));
    }

    @Test
    void doesNotBalance_whenATransactionIsMissing() {
        // printed closing implies a 50 credit that the extracted rows are missing → mismatch → abortable
        ParsedBankStatement s = stmt(
                new PrintedTotals(new BigDecimal("100.00"), new BigDecimal("120.00"), null, null),
                txn("30.00", Sign.DEBIT));
        assertTrue(StatementBalanceValidator.hasPrintedBalances(s));
        assertFalse(StatementBalanceValidator.balances(s));
    }

    @Test
    void noPrintedBalances_isNotChecked() {
        ParsedBankStatement s = stmt(
                new PrintedTotals(null, null, null, null),
                txn("30.00", Sign.DEBIT));
        assertFalse(StatementBalanceValidator.hasPrintedBalances(s));   // → run skips the check (no abort)
    }
}
