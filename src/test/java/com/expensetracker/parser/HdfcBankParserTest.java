package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HdfcBankParserTest {

    private final ParsedBankStatement stmt =
            new HdfcBankParser().parse(Fixtures.lines("hdfc-sample.txt"));

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
    void extractsAllTransactions() {
        assertEquals(9, stmt.transactions().size());
    }

    @Test
    void hasSevenDebitsAndTwoCredits() {
        long debits = stmt.transactions().stream().filter(t -> t.sign() == Sign.DEBIT).count();
        long credits = stmt.transactions().stream().filter(t -> t.sign() == Sign.CREDIT).count();
        assertEquals(7, debits);
        assertEquals(2, credits);
    }

    @Test
    void reconcilesAgainstPrintedTotals() {
        assertTrue(StatementBalanceValidator.balances(stmt),
                "opening + credits - debits should equal printed closing balance");
    }

    @Test
    void parsedSumsMatchPrintedSummary() {
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.DEBIT)
                .compareTo(stmt.printedTotals().totalDebits()));
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.CREDIT)
                .compareTo(stmt.printedTotals().totalCredits()));
    }

    @Test
    void parsesFirstTransaction() {
        RawBankTxn t = stmt.transactions().get(0);
        assertEquals(LocalDate.of(2026, 5, 1), t.txnDate());
        assertEquals(Sign.DEBIT, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal("50000.00")));
        assertTrue(t.description().contains("ACH D- FUNDAB 23232223"));
    }

    @Test
    void parsesMultiLineSalaryCredit() {
        RawBankTxn t = stmt.transactions().get(8);
        assertEquals(LocalDate.of(2026, 5, 29), t.txnDate());
        assertEquals(Sign.CREDIT, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal("500000.00")));
        assertTrue(t.description().contains("AAAASALARY"),
                "multi-line narration should be stitched together");
    }
}
