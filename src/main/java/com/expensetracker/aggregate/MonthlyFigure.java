package com.expensetracker.aggregate;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The system-owned figures for one bank account within one calendar month — the raw inputs
 * written to that month's matrix row. {@code Net Expenses} is a derived Excel formula, but is
 * exposed here for validation.
 */
public record MonthlyFigure(
        String bank,
        YearMonth month,
        BigDecimal bankDebits,
        BigDecimal creditsTransfers) {

    public BigDecimal netExpenses() {
        return bankDebits.subtract(creditsTransfers);
    }
}
