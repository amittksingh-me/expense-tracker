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
 * Parser for Niyo / SBM Bank savings-account statements ({@code pdftotext -layout} output).
 *
 * <p>Layout differs markedly from HDFC: dd-MM-yyyy dates, empty amounts shown as {@code -},
 * an extra Chq/Ref column, a {@code B/F} opening-balance row, a {@code Total} row instead of a
 * summary block, and — critically — the narration is rendered <b>above and below</b> the
 * date/amount line (the date row sits in the middle of its own description). Attribution rule:
 * each transaction's narration = the fragment line directly above its date row + the
 * fragment(s) directly below it. For a 2-line gap between date rows, the upper line is the
 * previous transaction's tail and the lower line is the next transaction's head.
 */
public final class NiyoBankParser implements BankStatementParser {

    // ── Niyo / SBM statement format — edit here if the layout changes ─────────────────
    private static final List<String> HEADER_WORDS = List.of("Narration", "Withdrawal", "Deposit", "Balance");
    private static final String PERIOD_LABEL    = "Statement from";
    private static final String ACCOUNT_LABEL   = "Savings Account No";
    private static final String TOTAL_LABEL     = "Total";
    private static final String BROUGHT_FORWARD = "B/F";
    private static final String EMPTY_AMOUNT    = "-";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");
    // ──────────────────────────────────────────────────────────────────────────────────

    /** date + (usually empty) narration + withdrawal + deposit + balance; amounts may be "-". */
    private static final Pattern TXN = Pattern.compile(
            "^\\s*(\\d{2}-\\d{2}-\\d{4})\\s+(.*?)\\s+(-|[\\d,]+\\.\\d{2})\\s+(-|[\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$");

    private static final Pattern PERIOD = Pattern.compile(
            Pattern.quote(PERIOD_LABEL) + "\\s+(\\d{2}-\\d{2}-\\d{4})\\s+To\\s+(\\d{2}-\\d{2}-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCOUNT = Pattern.compile(Pattern.quote(ACCOUNT_LABEL) + "\\.?\\s*:\\s*(\\d+)");

    private static final Pattern TOTAL = Pattern.compile(
            "^\\s*" + Pattern.quote(TOTAL_LABEL) + "\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$");

    @Override
    public String bankLabel() {
        return "Niyo";
    }

    @Override
    public ParsedBankStatement parse(List<String> lines) {
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        String accountLast4 = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        BigDecimal totalDebits = null;
        BigDecimal totalCredits = null;

        List<Draft> drafts = new ArrayList<>();
        List<String> buffer = new ArrayList<>();   // narration fragments since the last date row
        Draft prev = null;
        boolean inTable = false;

        for (String line : lines) {
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
                    String a = m.group(1);
                    accountLast4 = a.length() >= 4 ? a.substring(a.length() - 4) : a;
                }
            }

            if (!inTable) {
                if (containsAll(line, HEADER_WORDS)) {
                    inTable = true;
                }
                continue;
            }

            Matcher total = TOTAL.matcher(line);
            if (total.matches()) {
                totalDebits = amount(total.group(1));
                totalCredits = amount(total.group(2));
                closing = amount(total.group(3));
                if (prev != null) {
                    prev.appendAll(buffer);   // remaining fragments are the last txn's tail
                }
                buffer.clear();
                break;
            }

            if (containsAll(line, HEADER_WORDS)) {   // header repeats on a new page — skip, do not buffer
                continue;
            }

            Matcher m = TXN.matcher(line);
            if (m.matches()) {
                String inlineNarr = m.group(2).trim();
                String w = m.group(3);
                String d = m.group(4);
                String bal = m.group(5);
                boolean wEmpty = EMPTY_AMOUNT.equals(w);
                boolean dEmpty = EMPTY_AMOUNT.equals(d);

                if (inlineNarr.contains(BROUGHT_FORWARD) || (wEmpty && dEmpty)) {
                    opening = amount(bal);           // brought-forward row → opening balance, not a txn
                    if (prev != null) {
                        prev.appendAll(buffer);
                    }
                    buffer.clear();
                    continue;
                }

                // split the buffer: last fragment is this txn's head; the rest is prev txn's tail
                String head = "";
                if (!buffer.isEmpty()) {
                    head = buffer.remove(buffer.size() - 1);
                    if (prev != null) {
                        prev.appendAll(buffer);
                    }
                }
                buffer.clear();

                Sign sign = wEmpty ? Sign.CREDIT : Sign.DEBIT;
                BigDecimal value = wEmpty ? amount(d) : amount(w);
                Draft cur = new Draft(LocalDate.parse(m.group(1), DATE), value, sign, combine(inlineNarr, head));
                drafts.add(cur);
                prev = cur;
                continue;
            }

            if (!trimmed.isEmpty()) {
                buffer.add(trimmed);
            }
        }
        if (prev != null) {
            prev.appendAll(buffer);
        }

        List<RawBankTxn> txns = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            txns.add(draft.toTxn());
        }
        PrintedTotals printedTotals = new PrintedTotals(opening, closing, totalDebits, totalCredits);
        return new ParsedBankStatement(accountLast4, periodStart, periodEnd, txns, printedTotals);
    }

    private static String combine(String inline, String head) {
        if (inline.isEmpty()) {
            return head;
        }
        return head.isEmpty() ? inline : inline + " " + head;
    }

    /** Mutable accumulator: narration is built as head (above) then tail (below) fragments. */
    private static final class Draft {
        final LocalDate date;
        final BigDecimal amount;
        final Sign sign;
        final StringBuilder narration;

        Draft(LocalDate date, BigDecimal amount, Sign sign, String head) {
            this.date = date;
            this.amount = amount;
            this.sign = sign;
            this.narration = new StringBuilder(head);
        }

        void appendAll(List<String> parts) {
            for (String p : parts) {
                if (narration.length() > 0) {
                    narration.append(' ');
                }
                narration.append(p);
            }
        }

        RawBankTxn toTxn() {
            return new RawBankTxn(date, narration.toString().trim(), amount, sign);
        }
    }
}
