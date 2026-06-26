package com.expensetracker.parser.model;

import java.math.BigDecimal;

/**
 * Totals printed on the statement itself, used to validate extraction.
 * Any field may be null if the statement does not print it.
 */
public record PrintedTotals(
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalDebits,
        BigDecimal totalCredits) {
}
