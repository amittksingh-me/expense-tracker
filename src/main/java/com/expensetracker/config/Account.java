package com.expensetracker.config;

/**
 * A configured account. {@code pdfPattern} matches its statement file; for credit cards,
 * {@code mandateBank} is the bank that pays it and {@code paymentPattern} identifies its
 * cc-payment debit in that bank's statement. {@code skip} = recognised but not processed
 * (e.g. an image-only statement).
 *
 * <p>{@code format} selects the statement parser and is intentionally <b>separate from the
 * label</b> — several accounts can share one statement format (e.g. two HDFC bank accounts),
 * so the parser is chosen by format, not by display name. Falls back to the label if unset.
 */
public record Account(
        String label,
        AccountType type,
        String format,
        String pdfPattern,
        String mandateBank,
        String paymentPattern,
        boolean skip) {

    public boolean isBank() {
        return type == AccountType.BANK;
    }

    public boolean isCard() {
        return type == AccountType.CREDIT_CARD;
    }

    /** Parser-selection key: the configured {@code format}, or the label if none was given. */
    public String formatKey() {
        return format == null || format.isBlank() ? label : format;
    }
}
