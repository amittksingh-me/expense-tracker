package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IciciBankParserTest {

    private final ParsedBankStatement stmt =
            new IciciBankParser().parse(Fixtures.lines("icici-sample.txt"));

    @Test
    void extractsStatementPeriod() {
        assertEquals(LocalDate.of(2026, 1, 1), stmt.periodStart());
        assertEquals(LocalDate.of(2026, 1, 31), stmt.periodEnd());
    }

    @Test
    void extractsAccountLast4() {
        assertEquals("0000", stmt.accountLast4());
    }

    @Test
    void extractsAllTransactionsAcrossBothPages() {
        assertEquals(7, stmt.transactions().size());   // 4 on page 1 + 3 on page 2
    }

    @Test
    void signFromColumns_fiveDebitsTwoCredits() {
        long debits = stmt.transactions().stream().filter(t -> t.sign() == Sign.DEBIT).count();
        long credits = stmt.transactions().stream().filter(t -> t.sign() == Sign.CREDIT).count();
        assertEquals(5, debits);
        assertEquals(2, credits);
    }

    @Test
    void summaryTotalsAreSummedAcrossPages() {
        // page1 Total dep 5,000 + page2 Total dep 3,000 = 8,000 credits; wd 1,000 + 3,000 = 4,000 debits
        assertEquals(0, new BigDecimal("8000.00").compareTo(stmt.printedTotals().totalCredits()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(stmt.printedTotals().totalDebits()));
        assertEquals(0, new BigDecimal("10000.00").compareTo(stmt.printedTotals().openingBalance()));
        assertEquals(0, new BigDecimal("14000.00").compareTo(stmt.printedTotals().closingBalance()));
    }

    @Test
    void reconcilesAgainstOpeningAndClosing() {
        assertTrue(StatementBalanceValidator.hasPrintedBalances(stmt));
        assertTrue(StatementBalanceValidator.balances(stmt));   // 10,000 + 8,000 − 4,000 = 14,000
    }

    @Test
    void wrappedNarrationKeepsHeadAndTailWithTheirOwnTxn() {
        // head (line above) + middle (dated line) + tail (line below) all belong to THIS txn
        RawBankTxn first = byAmount("100.00");
        assertEquals(Sign.DEBIT, first.sign());
        assertTrue(first.description().contains("SAMPLE PAYEE A"),
                "payee from the line above the date should be captured: " + first.description());
        assertTrue(first.description().contains("001/"),
                "the ref tail from the line below should stay with this txn: " + first.description());

        // the next txn must NOT inherit the previous txn's tail fragment
        RawBankTxn second = byAmount("400.00");
        assertTrue(second.description().contains("SAMPLE PAYEE B"), second.description());
        assertFalse(second.description().contains("001"),
                "previous txn's tail must not leak into this one: " + second.description());

        RawBankTxn credit = byAmount("5000.00");
        assertEquals(Sign.CREDIT, credit.sign());
        assertTrue(credit.description().contains("MMT/IMPS"), credit.description());
    }

    private RawBankTxn byAmount(String amt) {
        BigDecimal a = new BigDecimal(amt);
        return stmt.transactions().stream().filter(t -> t.amount().compareTo(a) == 0)
                .findFirst().orElseThrow(() -> new AssertionError("no txn with amount " + amt));
    }
}
