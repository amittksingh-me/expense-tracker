package com.expensetracker.workbook;

import com.expensetracker.aggregate.MonthlyFigure;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Option (b): an appended month inherits the previous row's formulas (references shifted), so
 * hand-customized formulas carry forward — while system input cells and Comments are cleared.
 *
 * Layout (0-based): 0 Date · 1 Card · 2 Comments · 3 Bank Debits · 4 Credits/Transfers ·
 * 5 Net Expenses · 6 Net Bank Expenses · 7 Total Expenses · 8 CC Expense · 9 Median · 10 Year · 11 Average
 */
class AppendFormulaInheritanceTest {

    private static final String SHEET = "Expenses";

    private static Workbook headersOnly() {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet(SHEET);
        s.createRow(1).createCell(3).setCellValue("Bank");   // bank-group row
        Row h = s.createRow(2);
        String[] hs = {"Date", "Card", "Comments", "Bank Debits", "Credits/Transfers", "Net Expenses",
                "Net Bank Expenses", "Total Expenses", "CC Expense", "Median Expense", "Year", "Average Expense"};
        for (int i = 0; i < hs.length; i++) {
            h.createCell(i).setCellValue(hs[i]);
        }
        return wb;
    }

    private static WorkbookService open(Workbook wb) {
        WorkbookService svc = WorkbookService.forTesting(wb, SHEET);
        svc.registerBank("Bank");
        svc.registerCard("Card");
        return svc;
    }

    private static CellStyle dateStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("dd-mmm-yyyy"));
        return s;
    }

    private static Cell at(Workbook wb, LocalDate month, int col) {
        Sheet s = wb.getSheet(SHEET);
        for (int r = 3; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            Cell d = row == null ? null : row.getCell(0);
            if (d != null && d.getCellType() == CellType.NUMERIC
                    && d.getLocalDateTimeCellValue().toLocalDate().equals(month)) {
                return row.getCell(col);
            }
        }
        throw new AssertionError("no row for " + month);
    }

    @Test
    void inheritsCustomFormulaShifted_andClearsInputsAndComments() throws Exception {
        try (Workbook wb = headersOnly()) {
            Sheet s = wb.getSheet(SHEET);
            Row seed = s.createRow(3);                                  // Excel row 4
            Cell d = seed.createCell(0);
            d.setCellValue(LocalDate.of(2026, 1, 1));
            d.setCellStyle(dateStyle(wb));
            seed.createCell(1).setCellValue(5000);                      // card value
            seed.createCell(2).setCellValue("old note");               // Comments
            seed.createCell(3).setCellValue(50000);                    // debits
            seed.createCell(4).setCellValue(12000);                    // credits
            seed.createCell(5).setCellFormula("D4-E4-B3+$Z$1");        // CUSTOM net formula (relative + absolute)

            WorkbookService svc = open(wb);
            // append Feb, writing ONLY the bank (card left out → should land blank)
            svc.writeBank("Bank", LocalDate.of(2026, 2, 1), new MonthlyFigure("Bank",
                    YearMonth.of(2026, 2), new BigDecimal("100.00"), new BigDecimal("10.00")));

            LocalDate feb = LocalDate.of(2026, 2, 1);
            assertEquals("D5-E5-B4+$Z$1", at(wb, feb, 5).getCellFormula());   // custom formula carried + shifted
            assertEquals(CellType.BLANK, at(wb, feb, 2).getCellType());        // Comments cleared
            assertEquals(CellType.BLANK, at(wb, feb, 1).getCellType());        // card not processed → blank
            assertEquals(100.0, at(wb, feb, 3).getNumericCellValue());         // debit written
        }
    }

    @Test
    void copyHandlesArrayFormulaRows() throws Exception {
        try (Workbook wb = headersOnly()) {
            Sheet s = wb.getSheet(SHEET);
            Row seed = s.createRow(3);
            Cell d = seed.createCell(0);
            d.setCellValue(LocalDate.of(2026, 1, 1));
            d.setCellStyle(dateStyle(wb));
            seed.createCell(5).setCellFormula("D4-E4");
            seed.createCell(10).setCellFormula("YEAR(A4)");
            s.setArrayFormula("MEDIAN(IF($K$4:$K$500=K4,$H$4:$H$500))", new CellRangeAddress(3, 3, 9, 9));

            WorkbookService svc = open(wb);
            // appending must not choke on the array-formula row it copies
            assertDoesNotThrow(() -> svc.writeBank("Bank", LocalDate.of(2026, 2, 1),
                    new MonthlyFigure("Bank", YearMonth.of(2026, 2), new BigDecimal("200.00"), new BigDecimal("20.00"))));

            LocalDate feb = LocalDate.of(2026, 2, 1);
            assertEquals("D5-E5", at(wb, feb, 5).getCellFormula());   // simple per-bank net shifted
            assertNotNull(at(wb, feb, 9));                            // Median cell carried over
        }
    }
}
