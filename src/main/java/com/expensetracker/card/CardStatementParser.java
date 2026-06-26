package com.expensetracker.card;

import java.util.List;

/**
 * Parses one credit-card statement's text (pdftotext -layout) into its billing date and
 * Total Amount Due. One implementation per card layout.
 */
public interface CardStatementParser {

    /** The card label this parser handles (e.g. "HDFC CC"). */
    String cardLabel();

    ParsedCardStatement parse(List<String> lines);
}
