package com.expensetracker.parser.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single bank transaction as extracted from a statement — raw facts only.
 * No classification (tag) is assigned here; tagging is a separate concern.
 */
public record RawBankTxn(
        LocalDate txnDate,
        String description,
        BigDecimal amount,
        Sign sign) {
}
