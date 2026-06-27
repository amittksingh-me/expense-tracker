package com.expensetracker.card;

import static com.expensetracker.parser.ParserText.amount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Axis Bank credit-card statements.
 *
 * <p>The PAYMENT SUMMARY block prints a header row
 * ({@code Total Payment Due ... Statement Period ... Payment Due Date ... Statement Generation Date})
 * with the values on the following line, e.g.
 * {@code 35,000.00 Dr   8,750.00 Dr   22/04/2026 - 20/05/2026   09/06/2026   20/05/2026}. The system
 * takes the <b>first amount</b> (Total Payment Due — the mandate-debited figure) and the <b>last
 * date</b> (Statement Generation Date — the billing date) on that value line.
 *
 * <p><b>Why "Total Payment Due", not "Total Amount Due":</b> Axis prints the mandate figure under the
 * label {@code Total Payment Due}. A later illustrative <i>Minimum Amount Due Calculation</i> example
 * prints a {@code Total Amount Due} figure (a worked example, not this statement's total), so keying
 * off {@code Total Amount Due} would pick up the wrong number. We anchor on the PAYMENT SUMMARY
 * header line (the only line carrying both {@code Total Payment Due} and {@code Statement Generation
 * Date}) to avoid that trap.
 */
public final class AxisCardParser implements CardStatementParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Pattern AMOUNT = Pattern.compile("[\\d,]+\\.\\d{2}");
    private static final Pattern DMY = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");

    @Override
    public String cardLabel() {
        return "AXIS CC";
    }

    @Override
    public ParsedCardStatement parse(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("Total Payment Due") && line.contains("Statement Generation Date")) {
                String values = nextNonBlank(lines, i + 1);
                if (values == null) {
                    break;
                }
                return new ParsedCardStatement(generationDate(values), totalPaymentDue(values));
            }
        }
        throw new IllegalStateException(
                "Axis: PAYMENT SUMMARY header (Total Payment Due / Statement Generation Date) not found");
    }

    private static String nextNonBlank(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i);
            }
        }
        return null;
    }

    /** First money token on the value line — the Total Payment Due. */
    private static BigDecimal totalPaymentDue(String valueLine) {
        Matcher m = AMOUNT.matcher(valueLine);
        if (!m.find()) {
            throw new IllegalStateException("Axis: no Total Payment Due amount on value line: " + valueLine);
        }
        return amount(m.group());
    }

    /** Last {@code dd/MM/yyyy} token on the value line — the Statement Generation Date. */
    private static LocalDate generationDate(String valueLine) {
        Matcher m = DMY.matcher(valueLine);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        if (last == null) {
            throw new IllegalStateException("Axis: no Statement Generation Date on value line: " + valueLine);
        }
        return LocalDate.parse(last, DATE);
    }
}
