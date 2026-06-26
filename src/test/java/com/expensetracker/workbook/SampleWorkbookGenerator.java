package com.expensetracker.workbook;

import com.expensetracker.aggregate.MonthlyFigure;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regenerates {@code docs/sample/expenses-template.xlsx} — an <b>unencrypted, Expenses-only</b>
 * template documenting the matrix layout, with <b>placeholder data only</b>. The data row is
 * written through the real {@link WorkbookService} (register → writeCard/writeBank), so the
 * template provably matches the column-discovery code.
 *
 * <p>Skipped by default (so a normal {@code mvn test} doesn't rewrite the committed file).
 * Regenerate on demand:
 * <pre>mvn test -Dtest=SampleWorkbookGenerator -DgenSample=true</pre>
 *
 * Layout is generic (real labels live in config): 2 illustrative cards + 2 bank triplets.
 */
class SampleWorkbookGenerator {

    private static final String SHEET = "Expenses";
    private static final Path OUT = Path.of("docs/sample/expenses-template.xlsx");

    @Test
    void generatesTemplate() throws Exception {
        assumeTrue(Boolean.getBoolean("genSample"), "set -DgenSample=true to (re)generate the sample workbook");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET);

            s.createRow(0).createCell(0).setCellValue(
                    "Expenses — sample matrix layout (placeholder data; unencrypted). See docs/sample/README.md.");

            // row 1: bank-group labels sit over each triplet's first (Bank Debits) column
            Row group = s.createRow(1);
            group.createCell(4).setCellValue("Bank 1");
            group.createCell(7).setCellValue("Bank 2");

            // row 2: the headers the system discovers (by name); order mirrors the spec
            Row h = s.createRow(2);
            String[] headers = {
                    "Date", "Card A", "Card B", "Comments",
                    "Bank Debits", "Credits/Transfers", "Net Expenses",   // Bank 1 triplet
                    "Bank Debits", "Credits/Transfers", "Net Expenses",   // Bank 2 triplet
                    "Net Bank Expenses", "Total Expenses", "CC Expense",
                    "Median Expense", "Year", "Average Expense"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }

            // one placeholder month, written through the real WorkbookService so the layout is verified
            WorkbookService svc = WorkbookService.forTesting(wb, SHEET);
            svc.registerBank("Bank 1");
            svc.registerBank("Bank 2");
            svc.registerCard("Card A");
            svc.registerCard("Card B");
            LocalDate month = LocalDate.of(2026, 1, 1);
            svc.writeCard("Card A", month, new BigDecimal("15000.00"));      // → unverified (yellow)
            svc.writeCard("Card B", month, new BigDecimal("8000.00"));
            svc.writeBank("Bank 1", month, new MonthlyFigure("Bank 1", YearMonth.of(2026, 1),
                    new BigDecimal("50000.00"), new BigDecimal("12000.00")));
            svc.writeBank("Bank 2", month, new MonthlyFigure("Bank 2", YearMonth.of(2026, 1),
                    new BigDecimal("30000.00"), new BigDecimal("5000.00")));

            beautify(wb, s);
            wb.setForceFormulaRecalculation(true);

            Files.createDirectories(OUT.getParent());
            try (OutputStream os = Files.newOutputStream(OUT)) {
                wb.write(os);
            }
            System.out.println("wrote " + OUT + " (Expenses-only, unencrypted, placeholder data)");
        }
    }

    /** Make the placeholder row read like a real sheet: date + currency formats (fills preserved). */
    private static void beautify(Workbook wb, Sheet s) {
        DataFormat fmt = wb.createDataFormat();
        short date = fmt.getFormat("dd-mmm-yyyy");
        short money = fmt.getFormat("\"₹\"#,##0.00");
        Row r = s.getRow(3);
        format(wb, r.getCell(0), date);                                       // Date
        for (int c : new int[]{1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15}) { // money cols (not Year)
            format(wb, r.getCell(c), money);
        }
    }

    private static void format(Workbook wb, Cell cell, short dataFormat) {
        if (cell == null) {
            return;
        }
        CellStyle s = wb.createCellStyle();
        s.cloneStyleFrom(cell.getCellStyle());   // keep any fill (e.g. the yellow card cells)
        s.setDataFormat(dataFormat);
        cell.setCellStyle(s);
    }
}
