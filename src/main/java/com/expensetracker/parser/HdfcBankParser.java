package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.PrintedTotals;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;

import static com.expensetracker.parser.ParserText.amount;
import static com.expensetracker.parser.ParserText.containsAll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for HDFC savings-account statements ({@code pdftotext -layout} output).
 *
 * <p>Layout: a transaction table with separate {@code Withdrawals}/{@code Deposits} columns
 * (so sign is unambiguous), Indian-grouped amounts, dd/MM/yyyy dates, multi-line narrations,
 * and a closing SUMMARY block with the printed totals used for validation.
 */
public final class HdfcBankParser implements BankStatementParser {

    // ── HDFC statement format — edit here if HDFC changes its layout ──────────────────
    private static final List<String> HEADER_WORDS  = List.of("Txn Date", "Narration", "Withdrawals", "Deposits");
    private static final List<String> SUMMARY_WORDS = List.of("Opening Balance", "Debit Amount", "Credit Amount", "Closing Balance");
    private static final String PERIOD_LABEL  = "Statement From";
    private static final String ACCOUNT_LABEL = "Account Number";
    private static final String END_MARKER    = "End of Statement";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    // ──────────────────────────────────────────────────────────────────────────────────

    /** A transaction row: date + narration + withdrawals + deposits + closing balance. */
    private static final Pattern TXN = Pattern.compile(
            "^\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(.*?)\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$");

    private static final Pattern PERIOD = Pattern.compile(
            Pattern.quote(PERIOD_LABEL) + "\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+To\\s+(\\d{2}/\\d{2}/\\d{4})");

    private static final Pattern ACCOUNT = Pattern.compile(Pattern.quote(ACCOUNT_LABEL) + "\\s*:\\s*([0-9Xx]+)");

    private static final Pattern PAGE_MARKER = Pattern.compile("Page\\s+\\d+\\s+of\\s+\\d+");

    private static final Pattern AMOUNT = Pattern.compile("[\\d,]+\\.\\d{2}");

    @Override
    public String bankLabel() {
        return "HDFC";
    }

    @Override
    public ParsedBankStatement parse(List<String> lines) {
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        String accountLast4 = null;
        PrintedTotals printedTotals = null;

        List<RawBankTxn> txns = new ArrayList<>();
        Draft current = null;
        boolean inTable = false;
        boolean collecting = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (periodStart == null) {
                Matcher m = PERIOD.matcher(line);
                if (m.find()) {
                    periodStart = LocalDate.parse(m.group(1), DATE);
                    periodEnd = LocalDate.parse(m.group(2), DATE);
                }
            }
            if (accountLast4 == null) {
                Matcher m = ACCOUNT.matcher(line);
                if (m.find()) {
                    String acct = m.group(1);
                    accountLast4 = acct.length() >= 4 ? acct.substring(acct.length() - 4) : acct;
                }
            }
            if (printedTotals == null && containsAll(line, SUMMARY_WORDS)) {
                printedTotals = parseSummary(lines, i);
            }

            if (!inTable) {
                if (containsAll(line, HEADER_WORDS)) {
                    inTable = true;
                }
                continue;
            }

            Matcher txn = TXN.matcher(line);
            if (txn.matches()) {
                if (current != null) {
                    txns.add(current.toTxn());
                }
                BigDecimal withdrawals = amount(txn.group(3));
                BigDecimal deposits = amount(txn.group(4));
                Sign sign = withdrawals.signum() > 0 ? Sign.DEBIT : Sign.CREDIT;
                BigDecimal value = withdrawals.signum() > 0 ? withdrawals : deposits;
                current = new Draft(LocalDate.parse(txn.group(1), DATE), value, sign, txn.group(2).trim());
                collecting = true;
                continue;
            }

            if (containsAll(line, HEADER_WORDS)) {   // header repeats on a new page
                collecting = false;
                continue;
            }
            if (trimmed.isEmpty() || PAGE_MARKER.matcher(line).find()) {
                collecting = false;
                continue;
            }
            if (trimmed.contains(END_MARKER)) {
                break;
            }

            if (collecting && current != null && isNarrationContinuation(line)) {
                current.narration.append(' ').append(trimmed);
            } else {
                collecting = false;
            }
        }
        if (current != null) {
            txns.add(current.toTxn());
        }

        return new ParsedBankStatement(accountLast4, periodStart, periodEnd, txns, printedTotals);
    }

    /** Reads the first line after the summary header that carries 4 amounts. */
    private static PrintedTotals parseSummary(List<String> lines, int headerIndex) {
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            List<BigDecimal> amts = new ArrayList<>();
            Matcher m = AMOUNT.matcher(lines.get(i));
            while (m.find()) {
                amts.add(amount(m.group()));
            }
            if (amts.size() >= 4) {
                // order on the statement: Opening, Debit Amount, Credit Amount, Closing
                return new PrintedTotals(amts.get(0), amts.get(3), amts.get(1), amts.get(2));
            }
        }
        return null;
    }

    private static boolean isNarrationContinuation(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        // Real narration wraps under the Narration column (~col 19); page/account noise sits at
        // col 0 or far right, so bound the indent.
        return indent >= 8 && indent <= 45;
    }

    /** Mutable accumulator for a transaction whose narration may span several lines. */
    private static final class Draft {
        final LocalDate date;
        final BigDecimal amount;
        final Sign sign;
        final StringBuilder narration;

        Draft(LocalDate date, BigDecimal amount, Sign sign, String narration) {
            this.date = date;
            this.amount = amount;
            this.sign = sign;
            this.narration = new StringBuilder(narration);
        }

        RawBankTxn toTxn() {
            return new RawBankTxn(date, narration.toString().trim(), amount, sign);
        }
    }
}
