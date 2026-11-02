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
 *
 * <p>Two statement renderings are supported. The older layout leaves the Chq/Ref column blank and
 * prints amounts with exactly two decimals; the newer layout prints {@code -} in Chq/Ref and
 * strips trailing zeros from amounts ({@code 250.50 -> 250.5}, {@code 41.00 -> 41}). The newer
 * period line also uses slashes ({@code 01/06/2026 ... to ...}) and its Total row's third value is
 * the <b>net movement</b>, not the closing balance. Dates are normalised before parsing; amounts
 * accept 0–2 decimals; transaction rows are parsed from the right (the final three tokens are
 * always withdrawal / deposit / balance), with a trailing {@code -} Chq/Ref token dropped.
 */
public final class NiyoBankParser implements BankStatementParser {

    // ── Niyo / SBM statement format — edit here if the layout changes ─────────────────
    private static final List<String> HEADER_WORDS = List.of("Narration", "Withdrawal", "Deposit", "Balance");
    private static final String PERIOD_LABEL    = "Statement from";
    private static final String ACCOUNT_LABEL   = "Savings Account No";
    private static final String TOTAL_LABEL     = "Total";
    private static final String BROUGHT_FORWARD = "B/F";
    private static final String EMPTY_AMOUNT    = "-";
    private static final String AMOUNT_TOKEN    = "-|[\\d,]+(?:\\.\\d{1,2})?";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu");
    // ──────────────────────────────────────────────────────────────────────────────────

    /** Date at the start of a transaction row; the rest is split into tokens. */
    private static final Pattern TXN_DATE = Pattern.compile("^\\s*(\\d{2}[-/]\\d{2}[-/]\\d{4})\\s+(.*)$");

    /** One amount column: {@code -} or a number with optional 1–2 decimal places. */
    private static final Pattern AMOUNT = Pattern.compile(AMOUNT_TOKEN);

    private static final Pattern PERIOD = Pattern.compile(
            Pattern.quote(PERIOD_LABEL)
                    + "\\s+(\\d{2}[-/]\\d{2}[-/]\\d{4})\\s+to\\s+(\\d{2}[-/]\\d{2}[-/]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCOUNT = Pattern.compile(Pattern.quote(ACCOUNT_LABEL) + "\\.?\\s*:\\s*(\\d+)");

    private static final Pattern TOTAL = Pattern.compile(
            "^\\s*" + Pattern.quote(TOTAL_LABEL) + "\\s+(" + AMOUNT_TOKEN + ")\\s+(" + AMOUNT_TOKEN
                    + ")\\s+(" + AMOUNT_TOKEN + ")\\s*$");

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
        BigDecimal totalClosingOrNet = null;
        BigDecimal lastBalance = null;

        List<Draft> drafts = new ArrayList<>();
        List<String> buffer = new ArrayList<>();   // narration fragments since the last date row
        Draft prev = null;
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (periodStart == null) {
                Matcher m = PERIOD.matcher(line);
                if (m.find()) {
                    periodStart = date(m.group(1));
                    periodEnd = date(m.group(2));
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
                // Older layout: this is the closing balance. Newer layout: it is net movement;
                // the closing balance is then taken from the final transaction row instead.
                totalClosingOrNet = amount(total.group(3));
                if (prev != null) {
                    prev.appendAll(buffer);   // remaining fragments are the last txn's tail
                }
                buffer.clear();
                break;
            }

            if (containsAll(line, HEADER_WORDS)) {   // header repeats on a new page — skip, do not buffer
                continue;
            }

            Row row = row(line);
            if (row != null) {
                boolean wEmpty = EMPTY_AMOUNT.equals(row.withdrawal());
                boolean dEmpty = EMPTY_AMOUNT.equals(row.deposit());
                lastBalance = amount(row.balance());

                if (row.inlineNarration().contains(BROUGHT_FORWARD) || (wEmpty && dEmpty)) {
                    opening = amount(row.balance());   // brought-forward row → opening balance, not a txn
                    if (prev != null) {
                        prev.appendAll(buffer);
                    }
                    buffer.clear();
                    continue;
                }

                // Split the buffer between the previous txn's tail and this txn's head. When this
                // date row carries inline narration (newer layout), every buffered fragment is the
                // previous txn's tail. When it does not (older layout), the last fragment is this
                // txn's head and the rest is the previous txn's tail.
                String head = "";
                if (!row.inlineNarration().isEmpty()) {
                    if (prev != null) {
                        prev.appendAll(buffer);
                    }
                } else if (!buffer.isEmpty()) {
                    head = buffer.remove(buffer.size() - 1);
                    if (prev != null) {
                        prev.appendAll(buffer);
                    }
                }
                buffer.clear();

                Sign sign = wEmpty ? Sign.CREDIT : Sign.DEBIT;
                BigDecimal value = wEmpty ? amount(row.deposit()) : amount(row.withdrawal());
                Draft cur = new Draft(row.date(), value, sign, combine(row.inlineNarration(), head));
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

        // Prefer the final transaction row's balance: it is the closing balance in both layouts
        // (the newer Total row's third column is net movement, not closing).
        closing = lastBalance != null ? lastBalance : totalClosingOrNet;

        List<RawBankTxn> txns = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            txns.add(draft.toTxn());
        }
        PrintedTotals printedTotals = new PrintedTotals(opening, closing, totalDebits, totalCredits);
        return new ParsedBankStatement(accountLast4, periodStart, periodEnd, txns, printedTotals);
    }

    /**
     * Parses a transaction row from the right: the final three tokens are always withdrawal,
     * deposit and balance, so the older layout (no Chq/Ref token) and the newer layout (usually a
     * {@code -} Chq/Ref token) share one path. Everything before them is the inline narration.
     */
    private static Row row(String line) {
        Matcher m = TXN_DATE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(2).trim();
        if (rest.isEmpty()) {
            return null;
        }
        String[] tokens = rest.split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        int n = tokens.length;
        String withdrawal = tokens[n - 3];
        String deposit = tokens[n - 2];
        String balance = tokens[n - 1];
        if (!isAmount(withdrawal) || !isAmount(deposit) || !isAmount(balance)) {
            return null;
        }
        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < n - 3; i++) {
            prefix.add(tokens[i]);
        }
        // The newer layout fills the otherwise-blank Chq/Ref column with "-"; drop that token so
        // it does not leak into the narration. The older layout leaves the column blank.
        if (!prefix.isEmpty() && EMPTY_AMOUNT.equals(prefix.get(prefix.size() - 1))) {
            prefix.remove(prefix.size() - 1);
        }
        return new Row(date(m.group(1)), String.join(" ", prefix), withdrawal, deposit, balance);
    }

    private static boolean isAmount(String token) {
        return AMOUNT.matcher(token).matches();
    }

    private static LocalDate date(String token) {
        return LocalDate.parse(token.replace('/', '-'), DATE);
    }

    private static String combine(String inline, String head) {
        if (inline.isEmpty()) {
            return head;
        }
        return head.isEmpty() ? inline : inline + " " + head;
    }

    /** One parsed transaction row before narration stitching. */
    private record Row(LocalDate date, String inlineNarration,
                       String withdrawal, String deposit, String balance) {
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
