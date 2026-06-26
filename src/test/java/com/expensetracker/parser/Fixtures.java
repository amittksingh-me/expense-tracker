package com.expensetracker.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads statement text fixtures from src/test/resources/fixtures/. */
public final class Fixtures {

    private Fixtures() {
    }

    /** Reads a fixture (e.g. "hdfc-sample.txt") as layout-preserved lines. */
    public static List<String> lines(String fixtureName) {
        URL url = Fixtures.class.getClassLoader().getResource("fixtures/" + fixtureName);
        if (url == null) {
            throw new IllegalArgumentException("Fixture not found: fixtures/" + fixtureName);
        }
        try {
            return Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
