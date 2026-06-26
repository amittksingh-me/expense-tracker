package com.expensetracker.card;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What the system takes from a credit-card statement: the billing date and the Total Amount Due. */
public record ParsedCardStatement(LocalDate billingDate, BigDecimal totalAmountDue) {
}
