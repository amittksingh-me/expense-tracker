package com.expensetracker.aggregate;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TreatmentTest {

    @Test
    void debits() {
        assertEquals(Bucket.EXPENSE, Treatment.of(Sign.DEBIT, Tag.EXPENSE));
        assertEquals(Bucket.CREDITS_TRANSFERS, Treatment.of(Sign.DEBIT, Tag.INVESTMENT));
        assertEquals(Bucket.CREDITS_TRANSFERS, Treatment.of(Sign.DEBIT, Tag.CC_PAYMENT));
        assertEquals(Bucket.CREDITS_TRANSFERS, Treatment.of(Sign.DEBIT, Tag.SELF_TRANSFER));
    }

    @Test
    void credits() {
        assertEquals(Bucket.CREDITS_TRANSFERS, Treatment.of(Sign.CREDIT, Tag.REFUND));
        assertEquals(Bucket.IGNORED, Treatment.of(Sign.CREDIT, Tag.SALARY));
        assertEquals(Bucket.IGNORED, Treatment.of(Sign.CREDIT, Tag.INTEREST));
        assertEquals(Bucket.IGNORED, Treatment.of(Sign.CREDIT, Tag.SELF_TRANSFER));
        assertEquals(Bucket.IGNORED, Treatment.of(Sign.CREDIT, Tag.INVESTMENT));
    }

    @Test
    void labels() {
        assertEquals("Expense", Bucket.EXPENSE.label());
        assertEquals("Credits/Transfers", Bucket.CREDITS_TRANSFERS.label());
        assertEquals("Ignored", Bucket.IGNORED.label());
    }
}
