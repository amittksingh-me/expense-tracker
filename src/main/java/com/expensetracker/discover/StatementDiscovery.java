package com.expensetracker.discover;

import com.expensetracker.config.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Matches the PDF files in the working directory to configured accounts by their {@code pdfPattern}.
 * A PDF matching <b>more than one</b> account is ambiguous and <b>aborts</b> the run; a PDF matching
 * <b>no</b> account is <b>logged as a warning and skipped</b> (so unrelated files can sit in the
 * working directory without blocking a run).
 */
public final class StatementDiscovery {

    private static final Logger log = LoggerFactory.getLogger(StatementDiscovery.class);

    public record Match(Account account, Path file) {
    }

    public List<Match> discover(Path workingDir, List<Account> accounts) {
        List<Path> pdfs;
        try (Stream<Path> s = Files.list(workingDir)) {
            pdfs = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Match> matches = new ArrayList<>();
        for (Path pdf : pdfs) {
            String name = pdf.getFileName().toString();
            List<Account> hits = accounts.stream()
                    .filter(a -> Pattern.compile(a.pdfPattern()).matcher(name).find())
                    .toList();
            if (hits.isEmpty()) {
                log.warn("ignoring unknown statement file (matches no configured account): {}", name);
                continue;
            }
            if (hits.size() > 1) {
                throw new IllegalStateException("Statement file matches multiple accounts: " + name
                        + " -> " + hits.stream().map(Account::label).toList());
            }
            matches.add(new Match(hits.get(0), pdf));
        }
        return matches;
    }
}
