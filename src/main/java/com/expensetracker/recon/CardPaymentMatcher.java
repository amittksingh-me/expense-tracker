package com.expensetracker.recon;

import com.expensetracker.config.Account;

import java.util.List;

/**
 * Resolves which credit card a {@code cc-payment} bank debit pays, using the mandate (paying
 * bank) plus each card's payment-identification pattern. Shared by transaction processing (to
 * label the System Note) and by reconciliation — one place for the matching logic.
 */
public final class CardPaymentMatcher {

    private final List<Account> cards;

    public CardPaymentMatcher(List<Account> cards) {
        this.cards = cards;
    }

    /** The matched card, or {@code null} if none; fails loud if it matches more than one. */
    public Account match(String bank, String description) {
        String desc = description.toLowerCase();
        List<Account> hits = cards.stream()
                .filter(c -> !c.skip())
                .filter(c -> c.mandateBank() != null && c.mandateBank().equalsIgnoreCase(bank))
                .filter(c -> c.paymentPattern() != null && desc.contains(c.paymentPattern().toLowerCase()))
                .toList();
        if (hits.size() > 1) {
            throw new IllegalStateException("cc-payment matches multiple cards: \"" + description
                    + "\" -> " + hits.stream().map(Account::label).toList());
        }
        return hits.isEmpty() ? null : hits.get(0);
    }
}
