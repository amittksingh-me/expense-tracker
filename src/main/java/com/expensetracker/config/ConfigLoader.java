package com.expensetracker.config;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.Rule;
import com.expensetracker.tag.Tag;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads {@link AppConfig} from a YAML file. Fails loud on malformed config. */
public final class ConfigLoader {

    public AppConfig load(Path yamlFile) {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return parse(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + yamlFile, e);
        }
    }

    /** Loads from an already-open stream (e.g. a classpath resource). */
    public AppConfig load(InputStream in) {
        return parse(in);
    }

    @SuppressWarnings("unchecked")
    AppConfig parse(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);

        String service = str(root, "keychainService");
        String masterSheet = str(root, "masterSheet");
        Path input = Path.of(str(root, "inputWorkbook"));
        Path output = Path.of(str(root, "outputWorkbook"));
        Path workingDir = Path.of(str(root, "workingDir"));

        List<Account> accounts = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("accounts", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            accounts.add(new Account(
                    str(m, "label"),
                    "credit_card".equalsIgnoreCase(str(m, "type")) ? AccountType.CREDIT_CARD : AccountType.BANK,
                    optStr(m, "format"),
                    str(m, "pdf"),
                    optStr(m, "mandateBank"),
                    optStr(m, "paymentPattern"),
                    Boolean.TRUE.equals(m.get("skip"))));
        }

        List<Rule> rules = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("rules", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            String sign = optStr(m, "sign");
            rules.add(new Rule(
                    str(m, "pattern"),
                    optStr(m, "account"),
                    sign == null ? null : Sign.valueOf(sign.toUpperCase()),
                    Tag.valueOf(str(m, "tag").toUpperCase())));
        }

        return new AppConfig(service, masterSheet, input, output, workingDir, accounts, rules);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return v.toString();
    }

    private static String optStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
