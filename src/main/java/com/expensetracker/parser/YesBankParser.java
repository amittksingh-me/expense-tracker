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
 * EXPERIMENT — header/column-position based parser for YES Bank statements.
 *
 * <p>Unlike the regex-shape parsers (HDFC/Niyo), this one locates the numeric columns from the
 * header row (the index of "Withdrawals") and slices each transaction line <b>by position</b>,
 * then splits the numeric region into withdrawals/deposits/balance. Descriptions wrap above and
 * below the date row (the date row is the vertical centre of its description block), so the
 * narration for a transaction is reconstructed with a <b>symmetric</b> model: a transaction has
 * the same number of description lines below it as above it.
 */
public final class YesBankParser implements BankStatementParser {

    // ── YES Bank statement format — edit here if the layout changes ───────────────────
    private static final List<String> HEADER_WORDS  = List.of("Withdrawals", "Deposits", "Running Balance");
    private static final List<String> SUMMARY_WORDS = List.of("Opening Balance", "Total Withdrawals", "Closing Balance");
    private static final String WITHDRAWALS_COLUMN = "Withdrawals";   // anchors the numeric region
    private static final String OPENING_LABEL      = "Opening Balance";
    private static final String WITHDRAWALS_LABEL  = "Total Withdrawals";
    private static final String DEPOSITS_LABEL     = "Total Deposits";
    private static final String CLOSING_LABEL      = "Closing Balance";
    private static final String PERIOD_PREFIX      = "Period Of";
    private static final String ACCOUNT_LABEL      = "Account No:";
    private static final String FOOTER_MARKER      = "PhoneBanking";
    private static final String BROUGHT_FORWARD    = "B/F";
    private static final DateTimeFormatter TXN_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter PERIOD_DATE = DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH);
    private static final int DESCRIPTION_INDENT = 15;   // description fragments are indented at least this far
    // ──────────────────────────────────────────────────────────────────────────────────

    /** A transaction row begins with two dates (Transaction Date + Value Date). */
    private static final Pattern DATE_ROW =
            Pattern.compile("^\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}/\\d{2}/\\d{4})");

    private static final Pattern PERIOD = Pattern.compile(
            Pattern.quote(PERIOD_PREFIX) + "\\s+(\\d{2}-[A-Za-z]{3}-\\d{4})\\s+to\\s+(\\d{2}-[A-Za-z]{3}-\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCOUNT = Pattern.compile(Pattern.quote(ACCOUNT_LABEL) + "\\s*(\\d+)");

    @Override
    public String bankLabel() {
        return "YES";
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

        int wCol = -1;
        List<Draft> drafts = new ArrayList<>();
        List<String> fragments = new ArrayList<>();   // description-band lines since the last date row
        Draft prev = null;
        int prevAboveCount = 0;
        boolean inTable = false;
        boolean skip = false;

        for (String line : lines) {
            String trimmed = line.trim();

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

            if (isSummary(line)) {                       // end of the transaction table
                opening = labelledAmount(line, OPENING_LABEL);
                totalDebits = labelledAmount(line, WITHDRAWALS_LABEL);
                totalCredits = labelledAmount(line, DEPOSITS_LABEL);
                closing = labelledAmount(line, CLOSING_LABEL);
                if (prev != null) {                      // flush the last txn's below-fragments
                    flushBelow(prev, fragments, prevAboveCount);
                }
                break;
            }

            if (!inTable) {
                if (isMainHeader(line)) {
                    wCol = line.indexOf(WITHDRAWALS_COLUMN);
                    inTable = true;
                }
                continue;
            }

            if (isMainHeader(line)) {                    // header repeats on each page
                wCol = line.indexOf(WITHDRAWALS_COLUMN);
                skip = false;
                continue;
            }
            if (line.contains(FOOTER_MARKER)) {          // page footer begins — skip to next header
                skip = true;
                continue;
            }
            if (skip) {
                continue;
            }

            Matcher row = DATE_ROW.matcher(line);
            if (row.find()) {
                String[] nums = numericRegion(line, wCol);
                String inline = safeSubstring(line, row.end(2), wCol).trim();

                if (inline.contains(BROUGHT_FORWARD) || nums.length < 3) {
                    if (nums.length >= 1) {
                        opening = amount(nums[nums.length - 1]);   // B/F balance = opening (until summary confirms)
                    }
                    fragments.clear();
                    prev = null;
                    prevAboveCount = 0;
                    continue;
                }

                String wStr = nums[nums.length - 3];
                String dStr = nums[nums.length - 2];
                Sign sign = isPositive(wStr) ? Sign.DEBIT : Sign.CREDIT;
                BigDecimal value = isPositive(wStr) ? amount(wStr) : amount(dStr);

                List<String> above;
                if (prev != null) {
                    int belowCount = Math.min(prevAboveCount, fragments.size());
                    flushBelow(prev, fragments, prevAboveCount);
                    above = new ArrayList<>(fragments.subList(belowCount, fragments.size()));
                } else {
                    above = new ArrayList<>(fragments);
                }
                fragments.clear();

                Draft cur = new Draft(LocalDate.parse(row.group(1), TXN_DATE), value, sign);
                cur.addAll(above);
                cur.add(inline);
                cur.aboveCount = above.size();
                drafts.add(cur);
                prev = cur;
                prevAboveCount = cur.aboveCount;
                continue;
            }

            if (!trimmed.isEmpty() && leadingSpaces(line) >= DESCRIPTION_INDENT) {
                fragments.add(trimmed);
            }
        }

        List<RawBankTxn> txns = new ArrayList<>(drafts.size());
        for (Draft d : drafts) {
            txns.add(d.toTxn());
        }
        return new ParsedBankStatement(accountLast4, periodStart, periodEnd, txns,
                new PrintedTotals(opening, closing, totalDebits, totalCredits));
    }

    /** Slice from just before the Withdrawals column to end of line, split into amount tokens. */
    private static String[] numericRegion(String line, int wCol) {
        String region = safeSubstring(line, Math.max(0, wCol - 3), line.length()).trim();
        if (region.isEmpty()) {
            return new String[0];
        }
        return region.split("\\s+");
    }

    private static void flushBelow(Draft txn, List<String> fragments, int aboveCount) {
        int belowCount = Math.min(aboveCount, fragments.size());
        txn.addAll(fragments.subList(0, belowCount));
    }

    private static boolean isMainHeader(String line) {
        return containsAll(line, HEADER_WORDS);
    }

    private static boolean isSummary(String line) {
        return containsAll(line, SUMMARY_WORDS);
    }

    private static BigDecimal labelledAmount(String line, String label) {
        Matcher m = Pattern.compile(Pattern.quote(label) + "\\s*:?\\s*([\\d,]+\\.\\d{2})").matcher(line);
        return m.find() ? amount(m.group(1)) : null;
    }

    private static boolean isPositive(String token) {
        return amount(token).signum() > 0;
    }

    /** Narration parts in vertical order: above lines, then inline, then below lines. */
    private static final class Draft {
        final LocalDate date;
        final BigDecimal amount;
        final Sign sign;
        final List<String> parts = new ArrayList<>();
        int aboveCount;

        Draft(LocalDate date, BigDecimal amount, Sign sign) {
            this.date = date;
            this.amount = amount;
            this.sign = sign;
        }

        void add(String s) {
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }

        void addAll(List<String> ps) {
            parts.addAll(ps);
        }

        RawBankTxn toTxn() {
            return new RawBankTxn(date, String.join(" ", parts).trim(), amount, sign);
        }
    }
}
