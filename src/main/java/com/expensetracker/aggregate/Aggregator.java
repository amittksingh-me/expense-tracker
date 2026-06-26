package com.expensetracker.aggregate;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.TaggedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces tagged transactions to per-account, per-calendar-month figures.
 *
 * <p>Per the business rules (each sum scoped to one bank within one calendar month):
 * <ul>
 *   <li>{@code Bank Debits} = Σ amount of all debits (gross);
 *   <li>{@code Credits/Transfers} = Σ debits tagged {@code investment}/{@code cc-payment}/
 *       {@code self-transfer} + Σ credits tagged {@code refund};
 *   <li>credits tagged {@code salary}/{@code interest}/{@code self-transfer}/{@code investment}
 *       are ignored entirely.
 * </ul>
 */
public final class Aggregator {

    public List<MonthlyFigure> aggregate(List<TaggedTxn> tagged) {
        Map<Key, BigDecimal[]> sums = new HashMap<>();   // key -> [bankDebits, creditsTransfers]

        for (TaggedTxn tt : tagged) {
            BankTxn t = tt.txn();
            Key key = new Key(t.bank(), YearMonth.from(t.date()));
            BigDecimal[] s = sums.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});

            if (t.sign() == Sign.DEBIT) {
                s[0] = s[0].add(t.amount());                       // all debits, gross
            }
            if (Treatment.of(tt) == Bucket.CREDITS_TRANSFERS) {
                s[1] = s[1].add(t.amount());                       // netted out of expense (debit transfer or refund credit)
            }
            // Bucket.IGNORED contributes to neither column
        }

        List<MonthlyFigure> out = new ArrayList<>();
        sums.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Key, BigDecimal[]> e) -> e.getKey().bank())
                        .thenComparing(e -> e.getKey().month()))
                .forEach(e -> out.add(new MonthlyFigure(
                        e.getKey().bank(), e.getKey().month(), e.getValue()[0], e.getValue()[1])));
        return out;
    }

    private record Key(String bank, YearMonth month) {
    }
}
