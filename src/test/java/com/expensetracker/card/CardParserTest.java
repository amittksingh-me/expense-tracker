package com.expensetracker.card;

import com.expensetracker.parser.Fixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardParserTest {

    @Test
    void hdfcCc() {
        ParsedCardStatement s = new HdfcCardParser("HDFC CC").parse(Fixtures.lines("cc-hdfc-sample.txt"));
        assertEquals(LocalDate.of(2026, 5, 17), s.billingDate());
        assertEquals(0, s.totalAmountDue().compareTo(new BigDecimal("50000.00")));
    }

    @Test
    void hdfcRupay() {
        ParsedCardStatement s = new HdfcCardParser("HDFC RUPAY").parse(Fixtures.lines("cc-rupay-sample.txt"));
        assertEquals(LocalDate.of(2026, 5, 17), s.billingDate());
        assertEquals(0, s.totalAmountDue().compareTo(new BigDecimal("25000.00")));
    }

    @Test
    void yesCc() {
        ParsedCardStatement s = new YesCardParser().parse(Fixtures.lines("cc-yes-sample.txt"));
        assertEquals(LocalDate.of(2026, 6, 12), s.billingDate());
        assertEquals(0, s.totalAmountDue().compareTo(new BigDecimal("500.00")));
    }
}
