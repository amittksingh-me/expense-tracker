package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;

import java.util.List;

/**
 * A tagging rule: a <b>case- and whitespace-insensitive</b> substring match on the {@code Description},
 * optionally scoped to an account and/or {@code Sign}, assigning a {@link Tag}. A {@code null}
 * account or sign means "any". Rules are evaluated in order; the first match wins.
 *
 * <p>A rule matches by either a single {@code pattern} <b>or</b> {@code allOf} — a list of substrings
 * that must <b>all</b> be present (in any order). {@code allOf} expresses an AND condition that a
 * single substring cannot, e.g. a UPI self-transfer where one VPA is the sender and another is the
 * recipient — matching both ends, in either direction, regardless of the {@code @app} suffix.
 *
 * <p><b>Whitespace-insensitive</b> because {@code pdftotext -layout} frequently splits a word with
 * stray spaces at column boundaries (e.g. {@code SAMPLE} → {@code "SAMP LE"}), and the break points
 * move between statements. Matching after removing all whitespace from both the pattern and the
 * description makes a readable pattern like {@code "SAMPLE TEST USER"} match regardless of how the
 * narration was wrapped. (Keep patterns distinctive — squashing whitespace slightly widens what a
 * short pattern can hit.)
 */
public record Rule(String pattern, List<String> allOf, String account, Sign sign, Tag tag) {

    public Rule {
        allOf = allOf == null ? List.of() : List.copyOf(allOf);
    }

    /** Matches any account/sign whose description contains {@code pattern}. */
    public static Rule when(String pattern, Tag tag) {
        return new Rule(pattern, List.of(), null, null, tag);
    }

    /** Matches only within {@code account}. */
    public static Rule inAccount(String account, String pattern, Tag tag) {
        return new Rule(pattern, List.of(), account, null, tag);
    }

    /** Matches only when the description contains <b>all</b> of {@code substrings} (any order). */
    public static Rule allOf(Tag tag, String... substrings) {
        return new Rule(null, List.of(substrings), null, null, tag);
    }

    public boolean matches(BankTxn txn) {
        if (account != null && !account.equalsIgnoreCase(txn.bank())) {
            return false;
        }
        if (sign != null && sign != txn.sign()) {
            return false;
        }
        String d = squash(txn.description());
        boolean hasPattern = pattern != null && !pattern.isBlank();
        if (hasPattern && !d.contains(squash(pattern))) {
            return false;
        }
        for (String s : allOf) {
            if (!d.contains(squash(s))) {
                return false;
            }
        }
        return hasPattern || !allOf.isEmpty();   // a rule with no criteria never matches
    }

    /** Lower-case and strip all whitespace, so column-wrap spaces don't defeat a substring match. */
    private static String squash(String s) {
        return s.toLowerCase().replaceAll("\\s+", "");
    }
}
