package com.expensetracker.secret;

import java.nio.charset.StandardCharsets;

/** Fetches passwords from the macOS Keychain via the {@code security} CLI. Never logs values. */
public final class SecretsProvider {

    private final String service;

    public SecretsProvider(String service) {
        this.service = service;
    }

    /** The password stored under (service, account); fails loud if missing. */
    public String password(String account) {
        try {
            Process p = new ProcessBuilder(
                    "security", "find-generic-password", "-s", service, "-a", account, "-w").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) {
                throw new IllegalStateException(
                        "No Keychain password for account '" + account + "' (service '" + service + "')");
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Keychain lookup failed for '" + account + "'", e);
        }
    }
}
