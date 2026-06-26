package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.PrintedTotals;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;

import java.math.BigDecimal;

/**
 * Extraction backstop: confirms the extracted transactions are consistent with the statement's
 * own printed totals ({@code opening + credits − debits == closing}). If this balances, the table
 * was extracted completely and without stray rows.
 *
 * <p>Distinct from credit-card <b>reconciliation</b> (matching a card's bill against the bank
 * debit) — that lives in {@code com.expensetracker.recon}. This is purely a parse-quality check.
 */
public final class StatementBalanceValidator {

    private StatementBalanceValidator() {
    }

    /** True if the statement printed both an opening and a closing balance (so {@link #balances}
     *  is meaningful — a statement without printed balances cannot be checked and is not failed). */
    public static boolean hasPrintedBalances(ParsedBankStatement stmt) {
        PrintedTotals t = stmt.printedTotals();
        return t != null && t.openingBalance() != null && t.closingBalance() != null;
    }

    public static BigDecimal sum(ParsedBankStatement stmt, Sign sign) {
        return stmt.transactions().stream()
                .filter(t -> t.sign() == sign)
                .map(RawBankTxn::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * True if {@code opening + credits - debits == closing} within one paisa.
     * Returns false if the statement did not print opening/closing balances.
     */
    public static boolean balances(ParsedBankStatement stmt) {
        PrintedTotals t = stmt.printedTotals();
        if (t == null || t.openingBalance() == null || t.closingBalance() == null) {
            return false;
        }
        BigDecimal expectedClosing = t.openingBalance()
                .add(sum(stmt, Sign.CREDIT))
                .subtract(sum(stmt, Sign.DEBIT));
        return expectedClosing.subtract(t.closingBalance()).abs()
                .compareTo(new BigDecimal("0.01")) <= 0;
    }
}
