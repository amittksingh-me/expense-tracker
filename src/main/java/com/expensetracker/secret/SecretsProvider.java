package com.expensetracker.secret;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fetches passwords from the macOS Keychain via the {@code security} CLI. Never logs values. */
public final class SecretsProvider {

    private final String service;

    public SecretsProvider(String service) {
        this.service = service;
    }

    /** The password stored under (service, account); fails loud if missing. */
    public String password(String account) {
        String pw = fetch(account);
        if (pw == null) {
            throw new IllegalStateException(
                    "No Keychain password for account '" + account + "' (service '" + service + "')");
        }
        return pw;
    }

    /**
     * All passwords to try for an account, <b>current first</b>: the base entry ({@code account})
     * followed by numbered fallbacks ({@code "account 1"}, {@code "account 2"}, …). This lets a
     * statement password rotate over time — keep the previous one as {@code "account 1"}, the one
     * before that as {@code "account 2"}, etc., and the extractor tries them in order.
     *
     * <p>Discovery probes Keychain for {@code account}, then {@code "account 1"}, {@code "account 2"}
     * … and <b>stops at the first missing number</b> (numbering must be contiguous from 1). The base
     * entry (the current password) must exist — fails loud otherwise.
     */
    public List<String> passwords(String account) {
        List<String> out = new ArrayList<>();
        out.add(password(account));                  // current (required)
        for (int i = 1; ; i++) {
            String pw = fetch(account + " " + i);    // e.g. "YES 1", "YES 2", …
            if (pw == null) {
                break;
            }
            out.add(pw);
        }
        return out;
    }

    /** Returns the stored password, or {@code null} if there is no such Keychain entry. */
    private String fetch(String account) {
        try {
            Process p = new ProcessBuilder(
                    "security", "find-generic-password", "-s", service, "-a", account, "-w").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            return (code == 0 && !out.isEmpty()) ? out : null;
        } catch (Exception e) {
            throw new RuntimeException("Keychain lookup failed for '" + account + "'", e);
        }
    }
}
