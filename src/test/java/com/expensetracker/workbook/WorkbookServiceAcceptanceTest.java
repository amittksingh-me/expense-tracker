package com.expensetracker.workbook;

import com.expensetracker.aggregate.MonthlyFigure;
import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance tests for the workbook mechanics — the highest-risk surface — driven over a synthetic
 * (unencrypted) matrix so no real workbook, password, or PDF is needed. Builds the same header
 * layout the production sheet uses (so column discovery works), then exercises upsert, the
 * card two-state colour, reconciliation, green-overwrite, and the Transactions/status round-trip.
 */
class WorkbookServiceAcceptanceTest {

    private static final String SHEET = "Expenses";
    private static final int GROUP_ROW = 1, HEADER_ROW = 2, FIRST_DATA = 3;
    private static final int DATE = 0, YESCC = 1, HDFCCC = 2;          // card columns
    private static final int HDFC_DEBITS = 3, YES_DEBITS = 6;           // bank-triplet starts

    /** A minimal matrix sheet with the headers WorkbookService discovers. */
    private static Workbook synthetic() {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet(SHEET);
        Row group = s.createRow(GROUP_ROW);
        group.createCell(HDFC_DEBITS).setCellValue("HDFC");           // bank group labels (row 1)
        group.createCell(YES_DEBITS).setCellValue("YES");
        Row h = s.createRow(HEADER_ROW);                              // headers (row 2)
        h.createCell(DATE).setCellValue("Date");
        h.createCell(YESCC).setCellValue("YES CC");
        h.createCell(HDFCCC).setCellValue("HDFC CC");
        h.createCell(HDFC_DEBITS).setCellValue("Bank Debits");
        h.createCell(HDFC_DEBITS + 1).setCellValue("Credits/Transfers");
        h.createCell(HDFC_DEBITS + 2).setCellValue("Net Expenses");
        h.createCell(YES_DEBITS).setCellValue("Bank Debits");
        h.createCell(YES_DEBITS + 1).setCellValue("Credits/Transfers");
        h.createCell(YES_DEBITS + 2).setCellValue("Net Expenses");
        h.createCell(9).setCellValue("Net Bank Expenses");
        h.createCell(10).setCellValue("CC Expense");
        h.createCell(11).setCellValue("Total Expenses");
        h.createCell(12).setCellValue("Median Expense");
        h.createCell(13).setCellValue("Year");
        h.createCell(14).setCellValue("Average Expense");

        // Seed one real, date-formatted prior data row (like the real workbook) so appended rows
        // clone a date-formatted style and findMonthRow (which requires it) works reliably.
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.createDataFormat().getFormat("dd-mmm-yyyy"));
        Row seed = s.createRow(FIRST_DATA);
        Cell d = seed.createCell(DATE);
        d.setCellValue(LocalDate.of(2026, 1, 1));
        d.setCellStyle(dateStyle);
        return wb;
    }

    private static WorkbookService open(Workbook wb) {
        WorkbookService svc = WorkbookService.forTesting(wb, SHEET);
        svc.registerBank("HDFC");
        svc.registerBank("YES");
        svc.registerCard("YES CC");
        svc.registerCard("HDFC CC");
        return svc;
    }

    private static double num(Workbook wb, LocalDate month, int col) {
        Sheet s = wb.getSheet(SHEET);
        for (int r = FIRST_DATA; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            Cell d = row == null ? null : row.getCell(DATE);
            if (d != null && d.getCellType() == CellType.NUMERIC
                    && d.getLocalDateTimeCellValue().toLocalDate().equals(month)) {
                return row.getCell(col).getNumericCellValue();
            }
        }
        throw new AssertionError("no row for " + month);
    }

    /** Indexed fill colour of a month's cell in {@code col}. */
    private static short fill(Workbook wb, LocalDate month, int col) {
        Sheet s = wb.getSheet(SHEET);
        for (int r = FIRST_DATA; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            Cell d = row == null ? null : row.getCell(DATE);
            if (d != null && d.getCellType() == CellType.NUMERIC
                    && d.getLocalDateTimeCellValue().toLocalDate().equals(month)) {
                return row.getCell(col).getCellStyle().getFillForegroundColor();
            }
        }
        throw new AssertionError("no row for " + month);
    }

    @Test
    void writesCardsAndBanks_andUpsertsOneRowPerMonth() throws Exception {
        LocalDate may = LocalDate.of(2026, 5, 1);
        try (Workbook wb = synthetic()) {
            WorkbookService svc = open(wb);
            svc.writeCard("YES CC", may, new BigDecimal("499.00"));
            svc.writeBank("HDFC", may, new MonthlyFigure("HDFC", YearMonth.of(2026, 5),
                    new BigDecimal("1000.00"), new BigDecimal("300.00")));
            // re-process the same month: still one row, latest value wins
            svc.writeCard("YES CC", may, new BigDecimal("520.00"));

            assertEquals(520.0, num(wb, may, YESCC));
            assertEquals(1000.0, num(wb, may, HDFC_DEBITS));
            assertEquals(300.0, num(wb, may, HDFC_DEBITS + 1));
            assertEquals(1, rowsForMonth(wb, may));   // upsert, not append
        }
    }

    @Test
    void reconciliation_greens_thenSkips_thenNewerStatementReopens() throws Exception {
        LocalDate apr = LocalDate.of(2026, 4, 1);
        try (Workbook wb = synthetic()) {
            WorkbookService svc = open(wb);
            svc.writeCard("YES CC", apr, new BigDecimal("499.00"));   // provisional (unverified)

            // reconcile against the actual bank debit → corrects + verifies (green)
            BigDecimal old = svc.reconcileCard("YES CC", apr, new BigDecimal("480.00"));
            assertEquals(0, new BigDecimal("499.00").compareTo(old));
            assertEquals(480.0, num(wb, apr, YESCC));

            // already green → reconciliation leaves it untouched (returns null)
            assertNull(svc.reconcileCard("YES CC", apr, new BigDecimal("470.00")));
            assertEquals(480.0, num(wb, apr, YESCC));

            // a newer statement is authoritative — overwrites even the green cell; because it WAS
            // verified, the revised value lands as amber (not yellow) to flag "a trusted value changed"
            svc.writeCard("YES CC", apr, new BigDecimal("450.00"));
            assertEquals(450.0, num(wb, apr, YESCC));
            assertEquals((int) IndexedColors.LIGHT_ORANGE.getIndex(), (int) fill(wb, apr, YESCC));
            // ...and because it is unverified again, reconciliation now acts on it
            BigDecimal old2 = svc.reconcileCard("YES CC", apr, new BigDecimal("440.00"));
            assertNotNull(old2);
            assertEquals(440.0, num(wb, apr, YESCC));
        }
    }

    @Test
    void detectsDuplicateMonthRows() throws Exception {
        try (Workbook wb = synthetic()) {
            WorkbookService svc = open(wb);
            assertTrue(svc.duplicateMonthRows().isEmpty());   // only the single seed row

            // add a second data row with the same Month Key as the seed (2026-01-01)
            Sheet s = wb.getSheet(SHEET);
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("dd-mmm-yyyy"));
            Row dup = s.createRow(FIRST_DATA + 1);
            Cell d = dup.createCell(DATE);
            d.setCellValue(LocalDate.of(2026, 1, 1));
            d.setCellStyle(dateStyle);

            assertEquals(List.of(LocalDate.of(2026, 1, 1)), svc.duplicateMonthRows());
        }
    }

    @Test
    void transactionsAndStatusRoundTrip() throws Exception {
        try (Workbook wb = synthetic()) {
            WorkbookService svc = open(wb);
            assertFalse(svc.hasTransactionsSheet());

            TaggedTxn a = new TaggedTxn(new BankTxn("HDFC", LocalDate.of(2026, 5, 4),
                    "UPI/x", new BigDecimal("100.00"), Sign.DEBIT), Tag.EXPENSE);
            TaggedTxn b = new TaggedTxn(new BankTxn("YES", LocalDate.of(2026, 5, 6),
                    "ACH CAMS", new BigDecimal("50000.00"), Sign.DEBIT), Tag.INVESTMENT);
            svc.writeTransactionsSheet(List.of(a, b), t -> "", t -> t.tag() == Tag.INVESTMENT);

            assertTrue(svc.hasTransactionsSheet());
            assertEquals(2, svc.readTransactionsSheet().size());
            assertEquals(1, svc.readPinned().size());                 // only the pinned (INVESTMENT) row
            assertEquals(Tag.INVESTMENT, svc.readPinned().get(0).tag());

            svc.setStatus("pending");
            assertEquals("pending", svc.getStatus());

            svc.deleteTransactionsSheet();
            assertFalse(svc.hasTransactionsSheet());
        }
    }

    private static int rowsForMonth(Workbook wb, LocalDate month) {
        Sheet s = wb.getSheet(SHEET);
        int n = 0;
        for (int r = FIRST_DATA; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            Cell d = row == null ? null : row.getCell(DATE);
            if (d != null && d.getCellType() == CellType.NUMERIC
                    && d.getLocalDateTimeCellValue().toLocalDate().equals(month)) {
                n++;
            }
        }
        return n;
    }
}
