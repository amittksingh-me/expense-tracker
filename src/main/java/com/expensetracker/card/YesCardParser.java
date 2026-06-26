package com.expensetracker.card;

import static com.expensetracker.parser.ParserText.amount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for YES Bank credit-card statements. The {@code Total Amount Due:} label sits above its
 * value (first amount on the following line); statement date is {@code dd/MM/yyyy}.
 */
public final class YesCardParser implements CardStatementParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Pattern STMT_DATE = Pattern.compile("Statement Date\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern AMOUNT = Pattern.compile("[\\d,]+\\.\\d{2}");

    @Override
    public String cardLabel() {
        return "YES CC";
    }

    @Override
    public ParsedCardStatement parse(List<String> lines) {
        LocalDate billingDate = null;
        int tadLine = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (billingDate == null) {
                Matcher m = STMT_DATE.matcher(line);
                if (m.find()) {
                    billingDate = LocalDate.parse(m.group(1), DATE);
                }
            }
            if (tadLine < 0 && line.toLowerCase().contains("total amount due")) {
                tadLine = i;
            }
        }

        BigDecimal total = null;
        if (tadLine >= 0) {
            for (int i = tadLine; i < lines.size() && total == null; i++) {
                Matcher m = AMOUNT.matcher(lines.get(i));   // label line has no decimal amount; value is on the next line
                if (m.find()) {
                    total = amount(m.group());
                }
            }
        }
        return new ParsedCardStatement(billingDate, total);
    }
}
