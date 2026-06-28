package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.expensetracker.tag.Tag.CC_PAYMENT;
import static com.expensetracker.tag.Tag.INTEREST;
import static com.expensetracker.tag.Tag.INVESTMENT;
import static com.expensetracker.tag.Tag.SALARY;
import static com.expensetracker.tag.Tag.SELF_TRANSFER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the {@link TaggingEngine} against the curated answer key in
 * {@code src/test/resources/tags/tags.tsv}. Each row supplies the inputs (bank, sign, amount,
 * description) and the expected tag; the engine runs the ruleset over those inputs and the result
 * must match. Tagging never uses the transaction date, so a fixed placeholder date is used.
 */
class TaggingEngineTest {

    /** The ruleset under test — readable substrings, account/sign scoped, first match wins. */
    private static final List<Rule> RULES = List.of(
            Rule.when("CAMS", INVESTMENT),
            Rule.when("KFINTECH", INVESTMENT),
            Rule.inAccount("HDFC", "Autopay", CC_PAYMENT),
            Rule.inAccount("HDFC", "Axis Ba", CC_PAYMENT),   // "Axis Ba(nk)" SI debit — not UPI "okaxis" handles
            Rule.when("CREDIT CARD", CC_PAYMENT),
            Rule.when("SALARY", SALARY),
            Rule.when("Hly Int", INTEREST),
            Rule.when("Int.Pd", INTEREST),
            Rule.when("SAMPLE TEST USER", SELF_TRANSFER)   // family account — match the payee name, not the sender
    );

    private static final TaggingEngine ENGINE = new TaggingEngine(RULES);
    private static final LocalDate ANY_DATE = LocalDate.of(2026, 5, 1);

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("answerKey")
    void taggedAsExpected(Row row) {
        BankTxn txn = new BankTxn(row.bank(), ANY_DATE, row.description(), row.amount(), row.sign());
        Tag actual = ENGINE.tag(List.of(txn)).get(0).tag();
        assertEquals(row.expected(), actual, row::toString);
    }

    @Test
    void answerKeyIsNonEmpty() {
        assertFalse(answerKey().isEmpty(), "tags.tsv should contain rows");
    }

    static List<Row> answerKey() {
        try {
            Path path = Path.of(
                    TaggingEngineTest.class.getClassLoader().getResource("tags/tags.tsv").toURI());
            List<Row> rows = new ArrayList<>();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                if (c.length != 5) {
                    throw new IllegalStateException("tags.tsv line " + (i + 1)
                            + " must be 5 TAB-separated columns (bank, tag, sign, amount, description) "
                            + "but has " + c.length + " — check for spaces instead of tabs: " + line);
                }
                rows.add(new Row(c[0], Tag.valueOf(c[1]), Sign.valueOf(c[2]), new BigDecimal(c[3]), c[4]));
            }
            return rows;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record Row(String bank, Tag expected, Sign sign, BigDecimal amount, String description) {
        @Override
        public String toString() {
            String d = description.length() > 45 ? description.substring(0, 45) : description;
            return bank + " " + amount.toPlainString() + " \"" + d + "\" → " + expected;
        }
    }
}
