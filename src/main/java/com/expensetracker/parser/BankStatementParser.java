package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;

import java.util.List;

/**
 * Parses one bank's statement text into structured transactions.
 *
 * <p>Input is the {@code pdftotext -layout} output of a (decrypted) statement, supplied as
 * lines. The parser is a pure function over text — no PDF, password, or I/O — which is what
 * makes it unit-testable against plain-text fixtures.
 *
 * <p>One implementation per institution (its header/date/column layout differs).
 */
public interface BankStatementParser {

    /** Display label of the bank this parser handles (e.g. "HDFC Savings"). */
    String bankLabel();

    /** Parse the layout-preserved statement text lines into a structured result. */
    ParsedBankStatement parse(List<String> lines);
}
