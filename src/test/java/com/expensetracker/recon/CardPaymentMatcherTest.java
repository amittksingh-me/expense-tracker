package com.expensetracker.recon;

import com.expensetracker.config.Account;
import com.expensetracker.config.AccountType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardPaymentMatcherTest {

    private static Account card(String label, String bank, String pattern, boolean skip) {
        return new Account(label, AccountType.CREDIT_CARD, "TEST_CARD", "x", bank, pattern, skip);
    }

    private final CardPaymentMatcher matcher = new CardPaymentMatcher(List.of(
            card("HDFC CC", "HDFC", "8339", false),
            card("HDFC RUPAY", "HDFC", "3787", false),
            card("YES CC", "YES", "CREDIT CARD", false),
            card("AXIS CC", "HDFC", "Axis", true)));   // ignored

    @Test
    void matchesByBankAndPattern() {
        assertEquals("HDFC CC", matcher.match("HDFC", "CC 000000000XXXXXX8339 Autopay").label());
        assertEquals("HDFC RUPAY", matcher.match("HDFC", "CC 000000000XXXXXX3787 Autopay").label());
        assertEquals("YES CC", matcher.match("YES", "AUTOPAY YBL ... CREDIT CARD").label());
    }

    @Test
    void unmatchedReturnsNull() {
        // Axis is skipped → its SI debit matches no active card
        assertNull(matcher.match("HDFC", "SI HGALP115BD0136835130 Axis Ba"));
    }

    @Test
    void doesNotMatchAcrossTheWrongBank() {
        assertNull(matcher.match("YES", "CC 000000000XXXXXX8339 Autopay"));   // 8339 is an HDFC card
    }

    @Test
    void ambiguousMatchFailsLoud() {
        CardPaymentMatcher ambiguous = new CardPaymentMatcher(List.of(
                card("Card A", "HDFC", "Autopay", false),
                card("Card B", "HDFC", "Autopay", false)));
        assertThrows(IllegalStateException.class, () -> ambiguous.match("HDFC", "something Autopay"));
    }
}
