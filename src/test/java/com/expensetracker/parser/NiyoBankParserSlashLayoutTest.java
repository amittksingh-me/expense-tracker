package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The newer Niyo/SBM rendering: slash-separated period, {@code -} in Chq/Ref, stripped trailing
 * zeros (integer / one-decimal amounts), and a Total row whose third value is net movement.
 * The fixture is entirely artificial.
 */
class NiyoBankParserSlashLayoutTest {

    private final ParsedBankStatement stmt =
            new NiyoBankParser().parse(Fixtures.lines("niyo-slash-layout-sample.txt"));

    @Test
    void extractsSlashDelimitedPeriod() {
        assertEquals(LocalDate.of(2026, 6, 1), stmt.periodStart());
        assertEquals(LocalDate.of(2026, 6, 30), stmt.periodEnd());
    }

    @Test
    void extractsAccountLast4() {
        assertEquals("1234", stmt.accountLast4());
    }

    @Test
    void parsesRowsWithChqRefColumnAndVariableDecimals() {
        assertEquals(4, stmt.transactions().size());
        long debits = stmt.transactions().stream().filter(t -> t.sign() == Sign.DEBIT).count();
        long credits = stmt.transactions().stream().filter(t -> t.sign() == Sign.CREDIT).count();
        assertEquals(2, debits);
        assertEquals(2, credits);
    }

    @Test
    void parsesIntegerAndOneDecimalAmounts() {
        assertTxn(0, LocalDate.of(2026, 6, 1), Sign.DEBIT, "250.5");
        assertTxn(1, LocalDate.of(2026, 6, 2), Sign.CREDIT, "25.25");
        assertTxn(2, LocalDate.of(2026, 6, 3), Sign.CREDIT, "1000");
        assertTxn(3, LocalDate.of(2026, 6, 4), Sign.DEBIT, "50");
    }

    @Test
    void stitchesInlineAndContinuationNarration() {
        RawBankTxn first = stmt.transactions().get(0);
        assertTrue(first.description().contains("SAMPLE SHOP"));
        assertTrue(first.description().contains("A/YESB0TEST/Payment"));

        RawBankTxn interest = stmt.transactions().get(1);
        assertTrue(interest.description().contains("Int.Pd"));
        assertTrue(interest.description().contains("6 to 31-05-2026"));

        RawBankTxn last = stmt.transactions().get(3);
        assertTrue(last.description().contains("SAMPLE CAFE"));
        assertTrue(last.description().contains("C/HDFC0TEST/UPI"));
    }

    @Test
    void usesDebitAndCreditTotalsAndLastRowBalanceAsClosing() {
        assertEquals(0, stmt.printedTotals().totalDebits().compareTo(new BigDecimal("300.5")));
        assertEquals(0, stmt.printedTotals().totalCredits().compareTo(new BigDecimal("1025.25")));
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.DEBIT)
                .compareTo(stmt.printedTotals().totalDebits()));
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.CREDIT)
                .compareTo(stmt.printedTotals().totalCredits()));

        assertNull(stmt.printedTotals().openingBalance(), "no B/F row in this layout");
        assertEquals(0, stmt.printedTotals().closingBalance().compareTo(new BigDecimal("10724.75")),
                "closing comes from the last transaction row, not the Total row's net value");
        assertEquals(1, stmt.printedTotals().closingBalance().compareTo(new BigDecimal("724.75")));
    }

    private void assertTxn(int index, LocalDate date, Sign sign, String amount) {
        RawBankTxn t = stmt.transactions().get(index);
        assertEquals(date, t.txnDate());
        assertEquals(sign, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal(amount)));
    }
}
