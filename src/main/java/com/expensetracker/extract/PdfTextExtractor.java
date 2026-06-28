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

    /** Single-password convenience. */
    public List<String> extract(Path pdf, String password) {
        return extract(pdf, List.of(password));
    }

    /**
     * Tries each password in order (current first, then rotated-password fallbacks) and returns the
     * text from the first one that decrypts. Fails loud only if none of them produce any text
     * (all wrong, or an image-only/scanned PDF needing OCR).
     */
    public List<String> extract(Path pdf, List<String> passwords) {
        for (String password : passwords) {
            List<String> lines = tryExtract(pdf, password);
            if (lines != null) {
                return lines;
            }
        }
        throw new IllegalStateException("No text extracted from " + pdf.getFileName()
                + " after trying " + passwords.size() + " password(s) "
                + "(wrong password(s) or an image-only/scanned PDF needing OCR)");
    }

    /** One {@code pdftotext} attempt; returns the lines, or {@code null} if this password yields no text. */
    private static List<String> tryExtract(Path pdf, String password) {
        try {
            Process p = new ProcessBuilder("pdftotext", "-upw", password, "-layout", pdf.toString(), "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)   // swallow "Incorrect password" from failed tries
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out.isBlank() ? null : Arrays.asList(out.split("\n", -1));
        } catch (Exception e) {
            throw new RuntimeException("pdftotext failed for " + pdf, e);
        }
    }
}
