package com.expensetracker.parser;

import java.math.BigDecimal;
import java.util.List;

/** Shared text helpers used by the statement parsers. */
public final class ParserText {

    private ParserText() {
    }

    /** True if {@code line} contains every one of {@code words}. */
    public static boolean containsAll(String line, List<String> words) {
        for (String w : words) {
            if (!line.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** Parses an Indian-grouped amount like {@code "5,59,546.07"} into a {@link BigDecimal}. */
    public static BigDecimal amount(String token) {
        return new BigDecimal(token.replace(",", ""));
    }

    /** Number of leading spaces (the indent) of a line. */
    public static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Substring that clamps {@code from}/{@code to} to the string bounds instead of throwing. */
    public static String safeSubstring(String s, int from, int to) {
        if (from < 0) {
            from = 0;
        }
        if (to > s.length()) {
            to = s.length();
        }
        if (from > s.length() || to < from) {
            return "";
        }
        return s.substring(from, to);
    }
}
