package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;

/**
 * A tagging rule: a <b>case- and whitespace-insensitive</b> substring of the {@code Description},
 * optionally scoped to an account and/or {@code Sign}, assigning a {@link Tag}. A {@code null}
 * account or sign means "any". Rules are evaluated in order; the first match wins.
 *
 * <p><b>Whitespace-insensitive</b> because {@code pdftotext -layout} frequently splits a word with
 * stray spaces at column boundaries (e.g. {@code SAMPLE} → {@code "SAMP LE"}), and the break points
 * move between statements. Matching after removing all whitespace from both the pattern and the
 * description makes a readable pattern like {@code "SAMPLE TEST USER"} match regardless of how the
 * narration was wrapped. (Keep patterns distinctive — squashing whitespace slightly widens what a
 * short pattern can hit.)
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
        return squash(txn.description()).contains(squash(pattern));
    }

    /** Lower-case and strip all whitespace, so column-wrap spaces don't defeat a substring match. */
    private static String squash(String s) {
        return s.toLowerCase().replaceAll("\\s+", "");
    }
}
