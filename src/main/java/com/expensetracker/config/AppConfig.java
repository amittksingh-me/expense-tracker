package com.expensetracker.config;

import com.expensetracker.tag.Rule;

import java.nio.file.Path;
import java.util.List;

/** The full run configuration. */
public record AppConfig(
        String keychainService,
        String masterSheet,
        Path inputWorkbook,
        Path outputWorkbook,
        Path workingDir,
        List<Account> accounts,
        List<Rule> rules) {

    public List<Account> banks() {
        return accounts.stream().filter(Account::isBank).toList();
    }

    public List<Account> cards() {
        return accounts.stream().filter(Account::isCard).toList();
    }
}
