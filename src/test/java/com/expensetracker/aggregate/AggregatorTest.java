package com.expensetracker.aggregate;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregatorTest {

    private final Aggregator aggregator = new Aggregator();

    private static TaggedTxn txn(String bank, LocalDate date, String amount, Sign sign, Tag tag) {
        return new TaggedTxn(new BankTxn(bank, date, tag + "/" + amount, new BigDecimal(amount), sign), tag);
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    @Test
    void appliesTheBucketingRulesForEveryTagAndSign() {
        LocalDate may = LocalDate.of(2026, 5, 10);
        List<TaggedTxn> txns = List.of(
                txn("HDFC", may, "100.00", Sign.DEBIT, Tag.EXPENSE),         // true expense
                txn("HDFC", may, "50.00", Sign.DEBIT, Tag.INVESTMENT),       // debit → credits/transfers
                txn("HDFC", may, "30.00", Sign.DEBIT, Tag.CC_PAYMENT),       // debit → credits/transfers
                txn("HDFC", may, "20.00", Sign.DEBIT, Tag.SELF_TRANSFER),    // debit → credits/transfers
                txn("HDFC", may, "10.00", Sign.CREDIT, Tag.REFUND),          // credit → credits/transfers
                txn("HDFC", may, "1000.00", Sign.CREDIT, Tag.SALARY),        // ignored
                txn("HDFC", may, "5.00", Sign.CREDIT, Tag.INTEREST),         // ignored
                txn("HDFC", may, "40.00", Sign.CREDIT, Tag.SELF_TRANSFER),   // ignored (incoming)
                txn("HDFC", may, "200.00", Sign.CREDIT, Tag.INVESTMENT)      // ignored (redemption)
        );

        List<MonthlyFigure> figures = aggregator.aggregate(txns);

        assertEquals(1, figures.size());
        MonthlyFigure f = figures.get(0);
        assertEquals("HDFC", f.bank());
        assertEquals(YearMonth.of(2026, 5), f.month());
        assertEquals(0, f.bankDebits().compareTo(money("200.00")));         // 100+50+30+20
        assertEquals(0, f.creditsTransfers().compareTo(money("110.00")));   // 50+30+20 + 10
        assertEquals(0, f.netExpenses().compareTo(money("90.00")));         // 200 - 110
    }

    @Test
    void groupsByBankAndCalendarMonth() {
        List<TaggedTxn> txns = List.of(
                txn("HDFC", LocalDate.of(2026, 5, 3), "100.00", Sign.DEBIT, Tag.EXPENSE),
                txn("HDFC", LocalDate.of(2026, 6, 4), "200.00", Sign.DEBIT, Tag.EXPENSE),
                txn("NIYO", LocalDate.of(2026, 5, 9), "300.00", Sign.DEBIT, Tag.EXPENSE)
        );

        List<MonthlyFigure> figures = aggregator.aggregate(txns);

        assertEquals(3, figures.size());
        // sorted by bank then month
        assertEquals("HDFC", figures.get(0).bank());
        assertEquals(YearMonth.of(2026, 5), figures.get(0).month());
        assertEquals(0, figures.get(0).bankDebits().compareTo(money("100.00")));
        assertEquals("HDFC", figures.get(1).bank());
        assertEquals(YearMonth.of(2026, 6), figures.get(1).month());
        assertEquals(0, figures.get(1).bankDebits().compareTo(money("200.00")));
        assertEquals("NIYO", figures.get(2).bank());
        assertEquals(0, figures.get(2).bankDebits().compareTo(money("300.00")));
    }

    @Test
    void aRefundOnlyMonthHasNegativeNet() {
        List<TaggedTxn> txns = List.of(
                txn("YES", LocalDate.of(2026, 5, 1), "500.00", Sign.CREDIT, Tag.REFUND)
        );
        MonthlyFigure f = aggregator.aggregate(txns).get(0);
        assertEquals(0, f.bankDebits().compareTo(money("0")));
        assertEquals(0, f.creditsTransfers().compareTo(money("500.00")));
        assertEquals(0, f.netExpenses().compareTo(money("-500.00")));
    }
}
