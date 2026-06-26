package com.expensetracker.tag;

/** A transaction with its assigned tag. */
public record TaggedTxn(BankTxn txn, Tag tag) {
}
