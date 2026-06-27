package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.PrintedTotals;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;

import static com.expensetracker.parser.ParserText.amount;
import static com.expensetracker.parser.ParserText.containsAll;
import static com.expensetracker.parser.ParserText.leadingSpaces;
import static com.expensetracker.parser.ParserText.safeSubstring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for ICICI savings-account statements ({@code pdftotext -layout} output).
 *
 * <p><b>Column-by-column.</b> The table has separate {@code DEPOSITS} / {@code WITHDRAWALS} /
 * {@code BALANCE} columns; sign is read from <i>which column</i> the amount lands in. The column
 * x-positions are taken from each page's own header row, because ICICI <b>shifts the columns
 * between pages</b> (the {@code MODE}/{@code PARTICULARS} widths differ page to page).
 *
 * <p><b>Multi-line narration.</b> A transaction's narration wraps across physical lines with the
 * date + amount on the <i>middle</i> one: the payee usually sits on the line <i>above</i> the date
 * (the "head", a new {@code UPI/…} narration) and a short ref fragment on the line(s) <i>below</i>
 * (the "tail", a continuation like {@code 8/}). A head begins the <i>next</i> transaction; a tail
 * belongs to the <i>current</i> one — they're told apart by whether the fragment starts a new
 * narration ({@link #isNarrationStart}). So a transaction's description is
 * {@code head + dated-line PARTICULARS + tail}.
 *
 * <p><b>No statement-wide total.</b> Each page prints its own {@code Total:} subtotal; the statement
 * totals are the <b>sum of the per-page {@code Total:} rows</b>. Opening = the first {@code B/F}
 * balance; closing = the last running balance.
 *
 * <p><b>Validation.</b> Each row's running balance must chain ({@code prev ± amount == balance}), and
 * the summed page totals must equal the extracted credit/debit sums — either mismatch fails loud
 * (a dropped/misread row).
 */
public final class IciciBankParser implements BankStatementParser {

    private static final List<String> HEADER_WORDS =
            List.of("DATE", "MODE", "PARTICULARS", "DEPOSITS", "WITHDRAWALS", "BALANCE");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");
    private static final DateTimeFormatter PERIOD_DATE = DateTimeFormatter.ofPattern("MMMM dd, uuuu", Locale.ENGLISH);

    private static final Pattern PERIOD = Pattern.compile(
            "for the period\\s+([A-Z][a-z]+ \\d{2}, \\d{4})\\s*-\\s*([A-Z][a-z]+ \\d{2}, \\d{4})");
    private static final Pattern ACCOUNT = Pattern.compile("Savings A(?:ccount|/c)\\s+([0-9Xx]+)");
    private static final Pattern DATED = Pattern.compile("^\\s*(\\d{2}-\\d{2}-\\d{4})\\b");
    private static final Pattern MONEY = Pattern.compile("[\\d,]+\\.\\d{2}");
    private static final Pattern PAGE_MARKER = Pattern.compile("Page\\s+\\d+\\s+of\\s+\\d+");

    /** A narration fragment that <i>starts</i> a new transaction (the head) rather than continuing one. */
    private static final Pattern NARR_START = Pattern.compile(
            "^(UPI|MMT|NEFT|RTGS|IMPS|BIL|TOP|ACH|NACH|EBA|ISEC|VPS|IPS|VAT|MAT|NFS|INF|POS|CMS|ATW|ATM|INB|TPT"
            + "|MOBILE BANKING|INTERNET BANKING)\\b");

    @Override
    public String bankLabel() {
        return "ICICI";
    }

    @Override
    public ParsedBankStatement parse(List<String> lines) {
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        String accountLast4 = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;

        int partCol = -1;
        int depCol = -1;
        int wdCol = -1;                       // amounts ending left of this x are deposits, at/right are withdrawals
        boolean inTable = false;
        BigDecimal prevBalance = null;
        StringBuilder head = new StringBuilder();   // narration head pending for the NEXT dated row
        Draft current = null;                       // the txn currently accumulating its tail
        List<RawBankTxn> txns = new ArrayList<>();

        for (String line : lines) {
            if (periodStart == null) {
                Matcher m = PERIOD.matcher(line);
                if (m.find()) {
                    periodStart = LocalDate.parse(m.group(1), PERIOD_DATE);
                    periodEnd = LocalDate.parse(m.group(2), PERIOD_DATE);
                }
            }
            if (accountLast4 == null) {
                Matcher m = ACCOUNT.matcher(line);
                if (m.find()) {
                    String a = m.group(1);
                    accountLast4 = a.length() >= 4 ? a.substring(a.length() - 4) : a;
                }
            }

            if (containsAll(line, HEADER_WORDS)) {           // page header — (re)read this page's columns
                partCol = line.indexOf("PARTICULARS");
                depCol = line.indexOf("DEPOSITS");
                wdCol = line.indexOf("WITHDRAWALS");
                inTable = true;
                head.setLength(0);
                continue;
            }
            if (!inTable) {
                continue;
            }

            String trimmed = line.trim();
            if (trimmed.startsWith("Total:")) {              // page subtotal — accumulate, end the page table
                List<Money> m = money(line);
                if (m.size() >= 3) {
                    totalCredits = totalCredits.add(m.get(m.size() - 3).value);   // DEPOSITS subtotal
                    totalDebits = totalDebits.add(m.get(m.size() - 2).value);     // WITHDRAWALS subtotal
                    closing = m.get(m.size() - 1).value;                          // running balance
                }
                current = finalize(current, txns);
                head.setLength(0);
                inTable = false;
                continue;
            }
            if (PAGE_MARKER.matcher(line).find() || trimmed.isEmpty()) {
                continue;
            }

            Matcher d = DATED.matcher(line);
            boolean dated = d.find();
            if (dated && line.contains("B/F")) {             // opening balance row
                if (opening == null) {
                    List<Money> m = money(line);
                    if (!m.isEmpty()) {
                        opening = m.get(m.size() - 1).value;
                        prevBalance = opening;
                    }
                }
                head.setLength(0);
                continue;
            }
            if (dated) {
                List<Money> m = money(line);
                if (m.size() < 2) {
                    continue;                                // a dated line with no amount/balance — skip defensively
                }
                Money balance = m.get(m.size() - 1);
                Money amt = m.get(m.size() - 2);
                Sign sign = amt.end < wdCol ? Sign.CREDIT : Sign.DEBIT;   // column → deposit (credit) vs withdrawal (debit)

                if (prevBalance != null) {                   // balance must chain (cross-checks the column sign)
                    BigDecimal expected = sign == Sign.CREDIT
                            ? prevBalance.add(amt.value) : prevBalance.subtract(amt.value);
                    if (expected.subtract(balance.value).abs().compareTo(new BigDecimal("0.01")) > 0) {
                        throw new IllegalStateException("ICICI: balance does not chain at " + d.group(1)
                                + " — expected " + expected + " but statement shows " + balance.value
                                + " (a row was dropped or its sign/amount misread)");
                    }
                }
                prevBalance = balance.value;

                current = finalize(current, txns);           // close the previous txn (with its tail)
                StringBuilder desc = new StringBuilder(head); // head = narration from the line(s) above the date
                head.setLength(0);
                String particulars = safeSubstring(line, partCol, depCol).trim();
                if (!particulars.isEmpty()) {
                    append(desc, particulars);
                }
                current = new Draft(LocalDate.parse(d.group(1), DATE), amt.value, sign, desc);
                continue;
            }

            if (isContinuation(line)) {
                if (head.length() == 0 && current != null && !isNarrationStart(trimmed)) {
                    current.append(trimmed);                 // a continuation → tail of the CURRENT txn
                } else {
                    append(head, trimmed);                   // a new narration (or its continuation) → next txn's head
                }
            }
        }
        current = finalize(current, txns);

        if (closing == null) {
            closing = prevBalance;
        }
        // the per-page Total: sums must equal what we actually extracted
        BigDecimal exCredits = sum(txns, Sign.CREDIT);
        BigDecimal exDebits = sum(txns, Sign.DEBIT);
        if (totalCredits.compareTo(exCredits) != 0 || totalDebits.compareTo(exDebits) != 0) {
            throw new IllegalStateException("ICICI: extracted txns (" + exCredits + " cr / " + exDebits
                    + " dr) do not match the summed page Total: rows (" + totalCredits + " cr / " + totalDebits
                    + " dr) — extraction dropped or added a row");
        }

        return new ParsedBankStatement(accountLast4, periodStart, periodEnd, txns,
                new PrintedTotals(opening, closing, totalDebits, totalCredits));
    }

    private static Draft finalize(Draft current, List<RawBankTxn> txns) {
        if (current != null) {
            txns.add(current.toTxn());
        }
        return null;
    }

    private static boolean isNarrationStart(String frag) {
        return NARR_START.matcher(frag).find();
    }

    private static boolean isContinuation(String line) {
        return leadingSpaces(line) >= 12;   // narration wraps under the PARTICULARS column, never at col 0
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(s);
    }

    private static BigDecimal sum(List<RawBankTxn> txns, Sign sign) {
        return txns.stream().filter(t -> t.sign() == sign)
                .map(RawBankTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Money tokens on a line, each with its end x-position (for column classification). */
    private static List<Money> money(String line) {
        List<Money> out = new ArrayList<>();
        Matcher m = MONEY.matcher(line);
        while (m.find()) {
            out.add(new Money(amount(m.group()), m.end()));
        }
        return out;
    }

    private record Money(BigDecimal value, int end) {
    }

    /** Mutable accumulator for a transaction whose narration spans several lines (head + middle + tail). */
    private static final class Draft {
        private final LocalDate date;
        private final BigDecimal amount;
        private final Sign sign;
        private final StringBuilder desc;

        Draft(LocalDate date, BigDecimal amount, Sign sign, StringBuilder desc) {
            this.date = date;
            this.amount = amount;
            this.sign = sign;
            this.desc = desc;
        }

        void append(String s) {
            IciciBankParser.append(desc, s);
        }

        RawBankTxn toTxn() {
            return new RawBankTxn(date, desc.toString().trim().replaceAll("\\s+", " "), amount, sign);
        }
    }
}
