package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;

/**
 * A tagging rule: a case-insensitive substring of the {@code Description}, optionally scoped to
 * an account and/or {@code Sign}, assigning a {@link Tag}. A {@code null} account or sign means
 * "any". Rules are evaluated in order; the first match wins.
 */
public record Rule(String pattern, String account, Sign sign, Tag tag) {

    /** Matches any account/sign whose description contains {@code pattern}. */
    public static Rule when(String pattern, Tag tag) {
        return new Rule(pattern, null, null, tag);
    }

    /** Matches only within {@code account}. */
    public static Rule inAccount(String account, String pattern, Tag tag) {
        return new Rule(pattern, account, null, tag);
    }

    public boolean matches(BankTxn txn) {
        if (account != null && !account.equalsIgnoreCase(txn.bank())) {
            return false;
        }
        if (sign != null && sign != txn.sign()) {
            return false;
        }
        return txn.description().toLowerCase().contains(pattern.toLowerCase());
    }
}
