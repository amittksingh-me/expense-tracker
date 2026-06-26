package com.expensetracker.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Up-front configuration validation (fail-loud before processing). */
class ConfigValidationTest {

    private static final String HEAD = """
            keychainService: expense-tracker
            masterSheet: Expenses
            inputWorkbook: /tmp/in.xlsx
            outputWorkbook: /tmp/out.xlsx
            workingDir: /tmp
            rules:
              - { pattern: 'CAMS', tag: INVESTMENT }
            accounts:
            """;

    private static void load(String accountsYaml) {
        String yaml = HEAD + accountsYaml;
        new ConfigLoader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsValidConfig() {
        assertDoesNotThrow(() -> load("""
                  - { label: HDFC, type: bank, format: HDFC_BANK, pdf: 'h\\.pdf' }
                  - { label: HDFC CC, type: credit_card, format: HDFC_CARD, pdf: 'c\\.pdf', mandateBank: HDFC, paymentPattern: '1234' }
                  - { label: AXIS CC, type: credit_card, pdf: 'a\\.pdf', mandateBank: HDFC, skip: true }
                """));
    }

    @Test
    void rejectsDuplicateLabel() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load("""
                  - { label: HDFC, type: bank, pdf: 'h\\.pdf' }
                  - { label: HDFC, type: bank, pdf: 'h2\\.pdf' }
                """));
        assertTrue(e.getMessage().contains("Duplicate account label"));
    }

    @Test
    void rejectsMandateToUnknownBank() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load("""
                  - { label: HDFC, type: bank, pdf: 'h\\.pdf' }
                  - { label: HDFC CC, type: credit_card, pdf: 'c\\.pdf', mandateBank: NOPE, paymentPattern: '1234' }
                """));
        assertTrue(e.getMessage().contains("not a configured bank"));
    }

    @Test
    void rejectsActiveCardMissingPaymentPattern() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load("""
                  - { label: HDFC, type: bank, pdf: 'h\\.pdf' }
                  - { label: HDFC CC, type: credit_card, pdf: 'c\\.pdf', mandateBank: HDFC }
                """));
        assertTrue(e.getMessage().contains("payment-identification pattern"));
    }
}
