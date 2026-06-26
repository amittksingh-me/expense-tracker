package com.expensetracker.card;

import static com.expensetracker.parser.ParserText.amount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for HDFC credit-card statements (HDFC CC and HDFC Rupay share this layout).
 *
 * <p>The summary box prints an equation
 * {@code <prev> _ <payments> + <purchases> + <finance> = <TOTAL AMOUNT DUE>} — the Total Amount
 * Due is the amount after {@code =}. Statement date looks like {@code 17 May, 2026}. ({@code C} in
 * the text is the ₹ glyph as rendered by pdftotext.)
 */
public final class HdfcCardParser implements CardStatementParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM, uuuu", Locale.ENGLISH);
    private static final Pattern STMT_DATE = Pattern.compile("Statement Date\\s+(\\d{1,2}\\s+[A-Za-z]{3},\\s+\\d{4})");
    private static final Pattern AMOUNT = Pattern.compile("[\\d,]+\\.\\d{2}");

    private final String label;

    public HdfcCardParser(String label) {
        this.label = label;
    }

    @Override
    public String cardLabel() {
        return label;
    }

    @Override
    public ParsedCardStatement parse(List<String> lines) {
        LocalDate billingDate = null;
        BigDecimal total = null;

        for (String line : lines) {
            if (billingDate == null) {
                Matcher m = STMT_DATE.matcher(line);
                if (m.find()) {
                    billingDate = LocalDate.parse(m.group(1).replaceAll("\\s+", " ").trim(), DATE);
                }
            }
            if (total == null && line.contains("=")) {
                Matcher m = AMOUNT.matcher(line.substring(line.indexOf('=') + 1));
                if (m.find()) {
                    total = amount(m.group());
                }
            }
        }
        return new ParsedCardStatement(billingDate, total);
    }
}
