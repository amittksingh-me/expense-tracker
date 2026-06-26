package com.expensetracker.aggregate;

/** How a transaction contributes to the monthly figures. */
public enum Bucket {
    EXPENSE("Expense"),                 // a true-expense debit → counts toward Net Expense
    CREDITS_TRANSFERS("Credits/Transfers"), // excluded from Net Expense (investment/cc-payment/self-transfer debit, or refund credit)
    IGNORED("Ignored");                 // salary/interest/self-transfer/investment credit — in no column

    private final String label;

    Bucket(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
