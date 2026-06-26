package com.expensetracker.discover;

import java.time.LocalDate;
import java.util.List;

/**
 * Fail-loud guard against double-counting: two statements for the <b>same account</b> whose
 * declared periods overlap (a duplicate or a re-issued statement left beside the original).
 * Overlap is judged on each statement's declared start/end period, not on transaction dates,
 * per the business requirements.
 */
public final class StatementOverlap {

    /** One statement's declared coverage. */
    public record Period(String account, LocalDate start, LocalDate end) {
    }

    private StatementOverlap() {
    }

    /** Inclusive overlap test: true if [s1,e1] and [s2,e2] share any day. */
    public static boolean overlaps(LocalDate s1, LocalDate e1, LocalDate s2, LocalDate e2) {
        return !s1.isAfter(e2) && !s2.isAfter(e1);
    }

    /** Aborts (fail-loud) if any two periods for the same account overlap. */
    public static void check(List<Period> periods) {
        for (int i = 0; i < periods.size(); i++) {
            for (int j = i + 1; j < periods.size(); j++) {
                Period a = periods.get(i);
                Period b = periods.get(j);
                if (a.account().equals(b.account())
                        && overlaps(a.start(), a.end(), b.start(), b.end())) {
                    throw new IllegalStateException(
                            "Overlapping statements for " + a.account() + ": "
                                    + a.start() + ".." + a.end() + " and " + b.start() + ".." + b.end()
                                    + " — remove the stale/duplicate file and re-run.");
                }
            }
        }
    }
}
