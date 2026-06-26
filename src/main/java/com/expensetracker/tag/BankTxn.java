package com.expensetracker.tag;

import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A bank transaction enriched with its account label — the unified row that tagging,
 * aggregation, and the Transactions sheet all operate on.
 */
public record BankTxn(
        String bank,
        LocalDate date,
        String description,
        BigDecimal amount,
        Sign sign) {

    public static BankTxn of(String bank, RawBankTxn t) {
        return new BankTxn(bank, t.txnDate(), t.description(), t.amount(), t.sign());
    }
}
