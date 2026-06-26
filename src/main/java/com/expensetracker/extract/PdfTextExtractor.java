package com.expensetracker.extract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Decrypts and extracts text from a password-protected PDF by shelling out to
 * {@code pdftotext -upw <password> -layout}. Returns the layout-preserved lines that the parsers
 * consume. Fails loud if extraction yields nothing (wrong password or an image-only PDF).
 */
public final class PdfTextExtractor {

    public List<String> extract(Path pdf, String password) {
        try {
            Process p = new ProcessBuilder(
                    "pdftotext", "-upw", password, "-layout", pdf.toString(), "-")
                    .redirectErrorStream(false)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            if (out.isBlank()) {
                throw new IllegalStateException(
                        "No text extracted from " + pdf.getFileName()
                                + " (wrong password or an image-only/scanned PDF needing OCR)");
            }
            return Arrays.asList(out.split("\n", -1));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("pdftotext failed for " + pdf, e);
        }
    }
}
