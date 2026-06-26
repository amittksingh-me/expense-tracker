package com.expensetracker.recon;

import com.expensetracker.config.Account;
import com.expensetracker.config.AccountType;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReconcilerTest {

    private static final List<Account> CARDS = List.of(
            new Account("YES CC", AccountType.CREDIT_CARD, "YES_CARD", "x", "YES", "CREDIT CARD", false));

    private static TaggedTxn ccPayment(LocalDate date, String amount) {
        return new TaggedTxn(new BankTxn("YES", date, "AUTOPAY ... CREDIT CARD",
                new BigDecimal(amount), com.expensetracker.parser.model.Sign.DEBIT), Tag.CC_PAYMENT);
    }

    @Test
    void abortsWhenTwoDebitsMatchSameCardAndMonth() {
        List<TaggedTxn> txns = List.of(
                ccPayment(LocalDate.of(2026, 5, 7), "499.00"),
                ccPayment(LocalDate.of(2026, 5, 9), "499.00"));   // same card, same prior month (Apr)
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Reconciler().reconcile(txns, CARDS));
        assertEquals(true, e.getMessage().contains("YES CC"));
    }

    @Test
    void allowsSameCardInDifferentMonths() {
        List<TaggedTxn> txns = List.of(
                ccPayment(LocalDate.of(2026, 5, 7), "499.00"),    // → prior month Apr
                ccPayment(LocalDate.of(2026, 6, 7), "510.00"));   // → prior month May
        Reconciler.Result r = new Reconciler().reconcile(txns, CARDS);
        assertEquals(2, r.actions().size());
    }
}
