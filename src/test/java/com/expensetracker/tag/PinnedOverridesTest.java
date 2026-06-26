package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PinnedOverridesTest {

    private static TaggedTxn txn(String desc, String amount, Tag tag) {
        return new TaggedTxn(new BankTxn("NIYO", LocalDate.of(2026, 5, 2), desc, new BigDecimal(amount), Sign.CREDIT), tag);
    }

    @Test
    void overrideReplacesTagByIdentity() {
        List<TaggedTxn> tagged = List.of(
                txn("UPI/P2P/612253180018", "4800.00", Tag.REFUND),
                txn("Int.Pd", "39.00", Tag.INTEREST));
        List<TaggedTxn> overrides = List.of(txn("UPI/P2P/612253180018", "4800.00", Tag.SELF_TRANSFER));

        List<TaggedTxn> out = PinnedOverrides.apply(tagged, overrides);

        assertEquals(Tag.SELF_TRANSFER, out.get(0).tag());   // overridden
        assertEquals(Tag.INTEREST, out.get(1).tag());        // untouched
    }

    @Test
    void identityIsNormalised() {
        // override authored with messy whitespace + different decimal scale still matches
        List<TaggedTxn> tagged = List.of(txn("UPI/P2P/612253180018", "4800.00", Tag.REFUND));
        List<TaggedTxn> overrides = List.of(txn("UPI/P2P/612253180018", "4800", Tag.SELF_TRANSFER));
        assertEquals(Tag.SELF_TRANSFER, PinnedOverrides.apply(tagged, overrides).get(0).tag());

        assertEquals(PinnedOverrides.identity(txn("a   b", "10.00", Tag.REFUND).txn()),
                PinnedOverrides.identity(txn("a b", "10.0", Tag.REFUND).txn()));
    }
}
