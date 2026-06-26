package com.expensetracker.tag;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-off, human-pinned tag overrides, keyed by transaction identity. Pure + testable.
 *
 * <p>Identity is normalised per the spec: {@code Description} whitespace-collapsed, {@code Amount}
 * to 2 decimals, {@code Txn Date} date-only. A stored override re-applies to any matching
 * transaction on every run (so it survives re-tagging on regenerate).
 */
public final class PinnedOverrides {

    private PinnedOverrides() {
    }

    public static String identity(BankTxn t) {
        return t.bank()
                + "|" + t.date()
                + "|" + t.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "|" + t.sign()
                + "|" + t.description().trim().replaceAll("\\s+", " ");
    }

    /** Overlay the override tags onto matching transactions. */
    public static List<TaggedTxn> apply(List<TaggedTxn> tagged, List<TaggedTxn> overrides) {
        if (overrides.isEmpty()) {
            return tagged;
        }
        Map<String, Tag> byId = new HashMap<>();
        for (TaggedTxn o : overrides) {
            byId.put(identity(o.txn()), o.tag());
        }
        List<TaggedTxn> out = new ArrayList<>(tagged.size());
        for (TaggedTxn tt : tagged) {
            Tag override = byId.get(identity(tt.txn()));
            out.add(override == null ? tt : new TaggedTxn(tt.txn(), override));
        }
        return out;
    }
}
