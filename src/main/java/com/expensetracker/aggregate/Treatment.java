package com.expensetracker.aggregate;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;

/**
 * The single source of truth for how a {@code (Sign, Tag)} pair contributes to the monthly
 * figures. Used both by the {@link Aggregator} (to sum) and by the Transactions review sheet's
 * {@code Effect} column (to show the human what each row will do to Net Expense).
 *
 * <p>Note: a {@code CREDITS_TRANSFERS} <b>debit</b> is still part of gross {@code Bank Debits};
 * the bucket only says it is netted back out (excluded from Net Expense).
 */
public final class Treatment {

    private Treatment() {
    }

    public static Bucket of(Sign sign, Tag tag) {
        if (sign == Sign.DEBIT) {
            return (tag == Tag.INVESTMENT || tag == Tag.CC_PAYMENT || tag == Tag.SELF_TRANSFER)
                    ? Bucket.CREDITS_TRANSFERS
                    : Bucket.EXPENSE;
        }
        // credit: only a refund lands in Credits/Transfers; salary/interest/self-transfer/investment are ignored
        return tag == Tag.REFUND ? Bucket.CREDITS_TRANSFERS : Bucket.IGNORED;
    }

    public static Bucket of(TaggedTxn tt) {
        return of(tt.txn().sign(), tt.tag());
    }
}
