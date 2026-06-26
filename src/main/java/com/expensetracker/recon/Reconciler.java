package com.expensetracker.recon;

import com.expensetracker.config.Account;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches each {@code cc-payment} debit in the (tagged) bank transactions to the card it pays,
 * using the mandate (paying bank) + the card's payment-identification pattern. Produces the
 * correction to apply to that card's <b>immediately prior month</b> column (the bill it settles).
 *
 * <p>Aborts on an ambiguous (multiple-card) match. A debit matching no configured card is
 * returned as "unmatched" (logged, skipped) — e.g. while AXIS CC is ignored.
 */
public final class Reconciler {

    public record Action(String cardLabel, LocalDate priorEom, BigDecimal actual) {
    }

    public record Result(List<Action> actions, List<String> unmatched) {
    }

    public Result reconcile(List<TaggedTxn> bankTxns, List<Account> cards) {
        CardPaymentMatcher matcher = new CardPaymentMatcher(cards);
        List<Action> actions = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();

        for (TaggedTxn tt : bankTxns) {
            if (tt.tag() != Tag.CC_PAYMENT) {
                continue;
            }
            BankTxn t = tt.txn();
            Account card = matcher.match(t.bank(), t.description());   // shared logic; fails loud on >1
            if (card != null) {
                LocalDate priorEom = YearMonth.from(t.date()).minusMonths(1).atDay(1);
                actions.add(new Action(card.label(), priorEom, t.amount()));
            } else {
                unmatched.add(t.bank() + " " + t.amount() + " \"" + brief(t.description()) + "\"");
            }
        }

        // Exactly one mandate debit is expected per card/month. Two debits matching the same card
        // for the same prior month (double payment / reversal / duplicate) is an ambiguity → abort.
        Set<String> seen = new HashSet<>();
        for (Action a : actions) {
            if (!seen.add(a.cardLabel() + "|" + a.priorEom())) {
                throw new IllegalStateException("Multiple cc-payment debits matched " + a.cardLabel()
                        + " for " + a.priorEom() + " — ambiguous (double payment / reversal / duplicate); "
                        + "aborting reconciliation.");
            }
        }
        return new Result(actions, unmatched);
    }

    private static String brief(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
