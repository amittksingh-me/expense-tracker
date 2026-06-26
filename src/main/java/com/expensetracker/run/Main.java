package com.expensetracker.run;

import com.expensetracker.config.AppConfig;
import com.expensetracker.config.ConfigLoader;
import com.expensetracker.secret.SecretsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * CLI entry point. With no argument it loads {@code config.yaml} from the classpath
 * (src/main/resources); pass a path to override with an external config file.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig config = args.length > 0
                    ? loader.load(Path.of(args[0]))
                    : loadFromClasspath(loader);
            new Orchestrator(config, new SecretsProvider(config.keychainService())).run();
            log.info("DONE.");
        } catch (Exception e) {
            log.error("ABORTED: {}", e.getMessage());
            if (e.getCause() != null) {
                log.error("  cause: {}", e.getCause().getMessage());
            }
            System.exit(1);
        }
    }

    /**
     * Loads config from the classpath, preferring the private {@code config.local.yaml} (gitignored,
     * real values) and falling back to the committed {@code config.yaml} template.
     */
    private static AppConfig loadFromClasspath(ConfigLoader loader) throws Exception {
        for (String name : new String[]{"/config.local.yaml", "/config.yaml"}) {
            try (InputStream in = Main.class.getResourceAsStream(name)) {
                if (in != null) {
                    log.info("loaded config {}", name);
                    return loader.load(in);
                }
            }
        }
        throw new IllegalStateException("No config found on classpath (config.local.yaml / config.yaml)");
    }
}
