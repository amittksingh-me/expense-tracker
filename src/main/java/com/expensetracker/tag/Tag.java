package com.expensetracker.tag;

/** Classification of a bank transaction. {@code Sign} + {@code Tag} together decide treatment. */
public enum Tag {
    EXPENSE,
    INVESTMENT,
    CC_PAYMENT,
    SELF_TRANSFER,
    REFUND,
    SALARY,
    INTEREST
}
