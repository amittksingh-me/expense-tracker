package com.expensetracker.discover;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementOverlapTest {

    private static StatementOverlap.Period p(String acct, String start, String end) {
        return new StatementOverlap.Period(acct, LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void overlapPredicate() {
        assertTrue(StatementOverlap.overlaps(
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"),
                LocalDate.parse("2026-04-15"), LocalDate.parse("2026-05-15")));
        assertTrue(StatementOverlap.overlaps(   // touching at a single day counts (inclusive)
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"),
                LocalDate.parse("2026-04-30"), LocalDate.parse("2026-05-30")));
        assertFalse(StatementOverlap.overlaps(  // adjacent, no shared day
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"),
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")));
    }

    @Test
    void abortsOnSameAccountOverlap() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                StatementOverlap.check(List.of(
                        p("HDFC", "2026-04-01", "2026-04-30"),
                        p("HDFC", "2026-04-20", "2026-05-20"))));
        assertTrue(e.getMessage().contains("HDFC"));
    }

    @Test
    void allowsDifferentAccountsAndDistinctPeriods() {
        assertDoesNotThrow(() -> StatementOverlap.check(List.of(
                p("HDFC", "2026-04-01", "2026-04-30"),
                p("YES", "2026-04-01", "2026-04-30"),          // different account, same period — fine
                p("HDFC", "2026-05-01", "2026-05-31"))));       // same account, non-overlapping — fine
    }
}
