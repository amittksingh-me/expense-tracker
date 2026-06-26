package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NiyoBankParserTest {

    private final ParsedBankStatement stmt =
            new NiyoBankParser().parse(Fixtures.lines("niyo-sample.txt"));

    @Test
    void extractsStatementPeriod() {
        assertEquals(LocalDate.of(2026, 5, 1), stmt.periodStart());
        assertEquals(LocalDate.of(2026, 5, 31), stmt.periodEnd());
    }

    @Test
    void extractsAccountLast4() {
        assertEquals("0000", stmt.accountLast4());
    }

    @Test
    void excludesBroughtForwardRowAndCountsTransactions() {
        assertEquals(29, stmt.transactions().size());
    }

    @Test
    void hasTwentyFiveDebitsAndFourCredits() {
        long debits = stmt.transactions().stream().filter(t -> t.sign() == Sign.DEBIT).count();
        long credits = stmt.transactions().stream().filter(t -> t.sign() == Sign.CREDIT).count();
        assertEquals(25, debits);
        assertEquals(4, credits);
    }

    @Test
    void reconcilesAgainstPrintedTotals() {
        assertTrue(StatementBalanceValidator.balances(stmt),
                "B/F opening + credits - debits should equal Total-row closing balance");
    }

    @Test
    void parsedSumsMatchTotalRow() {
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.DEBIT)
                .compareTo(stmt.printedTotals().totalDebits()));
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.CREDIT)
                .compareTo(stmt.printedTotals().totalCredits()));
    }

    @Test
    void stitchesInterestCreditNarrationAcrossTheDateRow() {
        RawBankTxn first = stmt.transactions().get(0);
        assertEquals(LocalDate.of(2026, 5, 1), first.txnDate());
        assertEquals(Sign.CREDIT, first.sign());
        assertEquals(0, first.amount().compareTo(new BigDecimal("50.00")));
        assertTrue(first.description().contains("Int.Pd"),
                "narration above the date row should be captured");
        assertTrue(first.description().contains("2026 to 30-04-2026"),
                "narration below the date row should be stitched in");
    }

    @Test
    void parsesADebitWithStitchedNarration() {
        RawBankTxn t = stmt.transactions().get(3);   // the 1,500 UPI debit
        assertEquals(Sign.DEBIT, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal("1500.00")));
        assertTrue(t.description().contains("PAYEEA"));
        assertTrue(t.description().contains("CNRB"));
    }

    @Test
    void parsesLastTransaction() {
        RawBankTxn last = stmt.transactions().get(stmt.transactions().size() - 1);
        assertEquals(LocalDate.of(2026, 5, 30), last.txnDate());
        assertEquals(Sign.DEBIT, last.sign());
        assertEquals(0, last.amount().compareTo(new BigDecimal("750.00")));
        assertTrue(last.description().contains("PAYXX"));
    }
}
