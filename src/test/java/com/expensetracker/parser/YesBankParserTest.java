package com.expensetracker.parser;

import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.parser.model.RawBankTxn;
import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YesBankParserTest {

    private final ParsedBankStatement stmt =
            new YesBankParser().parse(Fixtures.lines("yes-sample.txt"));

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
    void excludesBroughtForwardAndCountsTransactions() {
        assertEquals(28, stmt.transactions().size());
    }

    @Test
    void allTransactionsAreDebits() {
        long debits = stmt.transactions().stream().filter(t -> t.sign() == Sign.DEBIT).count();
        long credits = stmt.transactions().stream().filter(t -> t.sign() == Sign.CREDIT).count();
        assertEquals(28, debits);
        assertEquals(0, credits);
    }

    @Test
    void reconcilesAgainstPrintedTotals() {
        assertTrue(StatementBalanceValidator.balances(stmt),
                "opening - withdrawals should equal closing balance");
    }

    @Test
    void parsedSumsMatchSummaryRow() {
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.DEBIT)
                .compareTo(stmt.printedTotals().totalDebits()));
        assertEquals(0, StatementBalanceValidator.sum(stmt, Sign.CREDIT)
                .compareTo(stmt.printedTotals().totalCredits()));
    }

    @Test
    void slicesFirstTransactionByColumn() {
        RawBankTxn t = stmt.transactions().get(0);
        assertEquals(LocalDate.of(2026, 5, 1), t.txnDate());
        assertEquals(Sign.DEBIT, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal("50.00")));
        assertTrue(t.description().contains("qpayee0007@ybl"));
    }

    @Test
    void stitchesAutopayDescriptionAboveInlineAndBelow() {
        RawBankTxn t = stmt.transactions().get(2);   // the 500 credit-card autopay
        assertEquals(Sign.DEBIT, t.sign());
        assertEquals(0, t.amount().compareTo(new BigDecimal("500.00")));
        assertTrue(t.description().contains("AUTOPAY"), "above fragment");
        assertTrue(t.description().contains("CREDIT CARD"), "below fragment");
    }

    @Test
    void parsesLargeMidStatementDebit() {
        boolean has15000 = stmt.transactions().stream()
                .anyMatch(t -> t.amount().compareTo(new BigDecimal("15000.00")) == 0
                        && t.sign() == Sign.DEBIT);
        assertTrue(has15000, "the 15,000 UPI debit should be parsed");
    }

    @Test
    void parsesLastTransaction() {
        RawBankTxn last = stmt.transactions().get(stmt.transactions().size() - 1);
        assertEquals(LocalDate.of(2026, 5, 31), last.txnDate());
        assertEquals(Sign.DEBIT, last.sign());
        assertEquals(0, last.amount().compareTo(new BigDecimal("750.00")));
        assertTrue(last.description().contains("paytm-51955531"));
    }
}
