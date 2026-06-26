package com.expensetracker.tag;

import com.expensetracker.parser.model.Sign;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns a {@link Tag} to each transaction in a unified, bank-labelled list.
 *
 * <p>Rules are evaluated in configuration order — the first match wins. When no rule matches,
 * defaults apply: a debit is an {@code EXPENSE}, a credit is a {@code REFUND}. (Pinned overrides,
 * applied last by transaction identity, will be added when that storage is built.)
 */
public final class TaggingEngine {

    private final List<Rule> rules;

    public TaggingEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<TaggedTxn> tag(List<BankTxn> txns) {
        List<TaggedTxn> out = new ArrayList<>(txns.size());
        for (BankTxn t : txns) {
            out.add(new TaggedTxn(t, tagFor(t)));
        }
        return out;
    }

    private Tag tagFor(BankTxn txn) {
        for (Rule rule : rules) {
            if (rule.matches(txn)) {
                return rule.tag();
            }
        }
        return txn.sign() == Sign.DEBIT ? Tag.EXPENSE : Tag.REFUND;   // defaults
    }
}
