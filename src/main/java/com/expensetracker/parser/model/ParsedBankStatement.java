package com.expensetracker.parser.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Structured result of parsing one bank statement.
 *
 * @param accountLast4  last 4 digits of the account (to confirm correct mapping); may be null
 * @param periodStart   statement period start (used for overlap detection)
 * @param periodEnd     statement period end
 * @param transactions  the extracted transactions, in statement order
 * @param printedTotals totals printed on the statement, for validation
 */
public record ParsedBankStatement(
        String accountLast4,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<RawBankTxn> transactions,
        PrintedTotals printedTotals) {
}
