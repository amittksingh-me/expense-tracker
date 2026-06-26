package com.expensetracker.workbook;

import com.expensetracker.aggregate.MonthlyFigure;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the password-protected matrix workbook with Apache POI.
 *
 * <p>Columns are discovered from the header rows (row 1 = bank-group labels; row 2 = "Date",
 * card labels, and the per-bank Bank Debits/Credits/Net sub-headers), so the layout is not
 * hard-coded. Month rows are keyed by end-of-month date. The system writes only its own input
 * cells (card totals + each bank's Bank Debits/Credits) and keeps the derived columns as live
 * Excel formulas; overwrites get a highlight cue.
 */
public final class WorkbookService implements AutoCloseable {

    private static final int BANK_GROUP_ROW = 1;   // row with YES / HDFC / NIYO / ICICI
    private static final int HEADER_ROW = 2;        // row with Date / card labels / Bank Debits...
    private static final int FIRST_DATA_ROW = 3;
    private static final int YEAR_RANGE_END = 500;  // matches the existing median/average ranges

    private final Workbook wb;
    private final Sheet sheet;
    private final int dateCol;
    private final Map<String, Integer> cardCol = new HashMap<>();          // label -> column
    private final Map<String, int[]> bankCols = new HashMap<>();           // label -> {debits, credits, net}
    private final int[] allBankNetCols;                                    // for Net Bank Expenses
    private final int colNetBank;
    private final int colTotal;
    private final int colCcExpense;
    private final int colMedian;
    private final int colYear;
    private final int colAverage;
    private final MatrixCellStyles styles;                   // builds/caches base/yellow/amber/green + green detection

    private WorkbookService(Workbook wb, String masterSheet) {
        this.wb = wb;
        this.sheet = sheetByName(wb, masterSheet);
        Row groups = sheet.getRow(BANK_GROUP_ROW);
        Row header = sheet.getRow(HEADER_ROW);

        this.dateCol = findInRow(header, "Date");
        this.colNetBank = findInRow(header, "Net Bank Expenses");
        this.colTotal = findInRow(header, "Total Expenses");
        this.colCcExpense = findInRow(header, "CC Expense");
        this.colMedian = findInRow(header, "Median Expense");
        this.colYear = findInRow(header, "Year");
        this.colAverage = findInRow(header, "Average Expense");

        // all bank triplets (by "Bank Debits" sub-headers) → net cols for the Net Bank formula
        java.util.List<Integer> nets = new java.util.ArrayList<>();
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            if ("Bank Debits".equalsIgnoreCase(cellString(header, c).trim())) {
                nets.add(c + 2);
            }
        }
        this.allBankNetCols = nets.stream().mapToInt(Integer::intValue).toArray();

        // existing data row to copy fonts/number formats from; styler also detects the verified-green fill
        this.styles = new MatrixCellStyles(wb, sheet, lastDataRow(), FIRST_DATA_ROW);
    }

    /** Opens (decrypts) the workbook. */
    public static WorkbookService open(Path path, String password, String masterSheet) {
        try {
            POIFSFileSystem fs = new POIFSFileSystem(new File(path.toString()));
            EncryptionInfo info = new EncryptionInfo(fs);
            Decryptor d = Decryptor.getInstance(info);
            if (!d.verifyPassword(password)) {
                throw new IllegalStateException("Workbook password (MASTER) did not verify");
            }
            try (InputStream is = d.getDataStream(fs)) {
                Workbook wb = new XSSFWorkbook(is);
                fs.close();
                return new WorkbookService(wb, masterSheet);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to open workbook " + path, e);
        }
    }

    /** Test hook: wrap an already-open (decrypted) workbook, bypassing encryption I/O. */
    static WorkbookService forTesting(Workbook wb, String masterSheet) {
        return new WorkbookService(wb, masterSheet);
    }

    /** Register a credit-card column by label (must match a row-2 header). */
    public void registerCard(String label) {
        cardCol.put(label, findInRow(sheet.getRow(HEADER_ROW), label));
    }

    /** Register a bank triplet by label (must match a row-1 group label). */
    public void registerBank(String label) {
        int debits = findInRow(sheet.getRow(BANK_GROUP_ROW), label);
        bankCols.put(label, new int[]{debits, debits + 1, debits + 2});
    }

    /**
     * Month Keys that appear in more than one matrix data row. The matrix must hold at most one row
     * per month; duplicates mean the workbook was hand-edited/corrupted, and writing would silently
     * update only one of them — callers should abort (fail-loud).
     */
    public List<LocalDate> duplicateMonthRows() {
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Cell c = row.getCell(dateCol);
            if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                counts.merge(c.getLocalDateTimeCellValue().toLocalDate(), 1, Integer::sum);
            }
        }
        List<LocalDate> dups = new ArrayList<>();
        counts.forEach((month, n) -> {
            if (n > 1) {
                dups.add(month);
            }
        });
        return dups;
    }

    /** Find the data row for an end-of-month date, or -1. */
    public int findMonthRow(LocalDate eom) {
        for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Cell c = row.getCell(dateCol);
            if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                LocalDate d = c.getLocalDateTimeCellValue().toLocalDate();
                if (d.equals(eom)) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** Write a card's provisional total into its column for the given month (upsert + highlight). */
    public void writeCard(String cardLabel, LocalDate eom, BigDecimal total) {
        Integer col = cardCol.get(cardLabel);
        if (col == null) {
            throw new IllegalStateException("Card column not registered: " + cardLabel);
        }
        int r = ensureRow(eom);
        // A newly processed statement is authoritative for that card/month: it overwrites any prior
        // value. If the prior value was verified (green), reprocessing it lands as **amber**
        // ("a trusted value changed — review me"); otherwise it lands as unverified (yellow).
        // Either way reconciliation can later confirm it against the bank debit (→ green).
        boolean wasVerified = isVerified(r, col);
        Cell c = cell(r, col);
        c.setCellValue(total.doubleValue());
        c.setCellStyle(wasVerified ? styles.amber(col) : styles.unverified(col));
    }

    /** Write a bank's monthly figures (Bank Debits, Credits/Transfers, Net formula). */
    public void writeBank(String bankLabel, LocalDate eom, MonthlyFigure f) {
        int[] cols = bankCols.get(bankLabel);
        if (cols == null) {
            throw new IllegalStateException("Bank columns not registered: " + bankLabel);
        }
        int r = ensureRow(eom);
        setNumber(r, cols[0], f.bankDebits());
        setNumber(r, cols[1], f.creditsTransfers());
        // Net Expenses as a live formula = debits - credits
        Cell net = cell(r, cols[2]);
        net.setCellFormula(colLetter(cols[0]) + (r + 1) + "-" + colLetter(cols[1]) + (r + 1));
        net.setCellStyle(styles.base(cols[2]));
    }

    /** Reconciliation: set a prior month's card column to the actual amount, highlighted. Returns old value or null. */
    public BigDecimal reconcileCard(String cardLabel, LocalDate priorEom, BigDecimal actual) {
        Integer col = cardCol.get(cardLabel);
        if (col == null) {
            return null;
        }
        int r = findMonthRow(priorEom);
        if (r < 0) {
            return null;
        }
        if (isVerified(r, col)) {
            return null;   // already green → verified earlier, trust it and skip
        }
        Cell c = cell(r, col);
        BigDecimal old = c.getCellType() == CellType.NUMERIC ? BigDecimal.valueOf(c.getNumericCellValue()) : null;
        c.setCellValue(actual.doubleValue());   // correct down if different; confirm if equal
        c.setCellStyle(styles.verified());          // mark verified (green)
        return old;
    }

    public void setStatus(String status) {
        Sheet control = wb.getSheet("Control");
        if (control == null) {
            control = wb.createSheet("Control");
            control.createRow(0).createCell(0).setCellValue("verification");
        }
        control.getRow(0).createCell(1).setCellValue(status);
    }

    public String getStatus() {
        Sheet control = wb.getSheet("Control");
        if (control == null || control.getRow(0) == null || control.getRow(0).getCell(1) == null) {
            return null;
        }
        return control.getRow(0).getCell(1).getStringCellValue();
    }

    /** (Re)create the Transactions review sheet; {@code note}/{@code pinned} fill those columns. */
    public void writeTransactionsSheet(java.util.List<com.expensetracker.tag.TaggedTxn> txns,
            java.util.function.Function<com.expensetracker.tag.TaggedTxn, String> note,
            java.util.function.Predicate<com.expensetracker.tag.TaggedTxn> pinned) {
        deleteTransactionsSheet();
        TransactionsSheet.write(wb.createSheet(TransactionsSheet.NAME), txns, note, pinned);
    }

    /** Read the (human-reviewed) Transactions sheet back. */
    public java.util.List<com.expensetracker.tag.TaggedTxn> readTransactionsSheet() {
        Sheet s = wb.getSheet(TransactionsSheet.NAME);
        if (s == null) {
            throw new IllegalStateException("No Transactions sheet to read (nothing to finalize)");
        }
        return TransactionsSheet.read(s);
    }

    public void deleteTransactionsSheet() {
        int idx = wb.getSheetIndex(TransactionsSheet.NAME);
        if (idx >= 0) {
            wb.removeSheetAt(idx);
        }
    }

    public boolean hasTransactionsSheet() {
        return wb.getSheet(TransactionsSheet.NAME) != null;
    }

    /** Rows the human marked Pinned=TRUE in the current Transactions sheet (empty if none). */
    public java.util.List<com.expensetracker.tag.TaggedTxn> readPinned() {
        Sheet s = wb.getSheet(TransactionsSheet.NAME);
        return s == null ? java.util.List.of() : TransactionsSheet.readPinned(s);
    }

    /** Save as an encrypted workbook to the given path. */
    public void save(Path out, String password) {
        try {
            wb.setForceFormulaRecalculation(true);
            POIFSFileSystem fs = new POIFSFileSystem();
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            try (OutputStream os = enc.getDataStream(fs)) {
                wb.write(os);
            }
            try (OutputStream fileOut = Files.newOutputStream(out)) {
                fs.writeFilesystem(fileOut);
            }
            fs.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save workbook " + out, e);
        }
    }

    @Override
    public void close() throws Exception {
        wb.close();
    }

    // ── internals ──────────────────────────────────────────────────────────────────────

    private int ensureRow(LocalDate eom) {
        int r = findMonthRow(eom);
        if (r >= 0) {
            return r;
        }
        r = lastDataRow() + 1;
        Cell date = cell(r, dateCol);
        date.setCellValue(eom);
        date.setCellStyle(styles.base(dateCol));
        writeDerivedFormulas(r);
        return r;
    }

    private int lastDataRow() {
        int last = FIRST_DATA_ROW - 1;
        for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Cell c = row.getCell(dateCol);
            if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                last = r;
            }
        }
        return last;
    }

    private void writeDerivedFormulas(int r) {
        int e = r + 1; // 1-based Excel row
        // Net Bank Expenses = sum of bank net cols
        StringBuilder net = new StringBuilder();
        for (int i = 0; i < allBankNetCols.length; i++) {
            net.append(i == 0 ? "" : "+").append(colLetter(allBankNetCols[i])).append(e);
        }
        cell(r, colNetBank).setCellFormula(net.toString());
        // CC Expense = SUM(firstCard:lastCard)
        int[] span = cardSpan();
        cell(r, colCcExpense).setCellFormula("SUM(" + colLetter(span[0]) + e + ":" + colLetter(span[1]) + e + ")");
        // Total = NetBank + CC
        cell(r, colTotal).setCellFormula(colLetter(colNetBank) + e + "+" + colLetter(colCcExpense) + e);
        // Year = YEAR(date)
        cell(r, colYear).setCellFormula("YEAR(" + colLetter(dateCol) + e + ")");
        // Median / Average over the calendar year (array formulas, matching the existing layout)
        String yr = "$" + colLetter(colYear) + "$" + (FIRST_DATA_ROW + 1) + ":$" + colLetter(colYear) + "$" + YEAR_RANGE_END;
        String tot = "$" + colLetter(colTotal) + "$" + (FIRST_DATA_ROW + 1) + ":$" + colLetter(colTotal) + "$" + YEAR_RANGE_END;
        setArray(r, colMedian, "MEDIAN(IF(" + yr + "=" + colLetter(colYear) + e + "," + tot + "))");
        setArray(r, colAverage, "AVERAGE(IF(" + yr + "=" + colLetter(colYear) + e + "," + tot + "))");

        cell(r, colNetBank).setCellStyle(styles.base(colNetBank));
        cell(r, colCcExpense).setCellStyle(styles.base(colCcExpense));
        cell(r, colTotal).setCellStyle(styles.base(colTotal));
        cell(r, colMedian).setCellStyle(styles.base(colMedian));
        cell(r, colAverage).setCellStyle(styles.base(colAverage));
        cell(r, colYear).setCellStyle(styles.base(colYear));
    }

    private int[] cardSpan() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int c : cardCol.values()) {
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        return new int[]{min, max};
    }

    private void setArray(int r, int col, String formula) {
        sheet.setArrayFormula(formula, new CellRangeAddress(r, r, col, col));
    }

    private void setNumber(int r, int col, BigDecimal value) {
        Cell c = cell(r, col);
        c.setCellValue(value.doubleValue());
        c.setCellStyle(styles.base(col));
    }

    private boolean isVerified(int r, int col) {
        Row row = sheet.getRow(r);
        if (row == null) {
            return false;
        }
        Cell c = row.getCell(col);
        return c != null && MatrixCellStyles.isGreen(c);
    }

    private Cell cell(int r, int col) {
        Row row = sheet.getRow(r);
        if (row == null) {
            row = sheet.createRow(r);
        }
        Cell c = row.getCell(col);
        return c != null ? c : row.createCell(col);
    }

    private static boolean isBlankRow(Row row) {
        for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static int findInRow(Row row, String header) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (header.equalsIgnoreCase(cellString(row, c).trim())) {
                return c;
            }
        }
        throw new IllegalStateException("Header not found in row " + row.getRowNum() + ": '" + header + "'");
    }

    private static String cellString(Row row, int c) {
        Cell cell = row.getCell(c);
        return cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
    }

    private static String colLetter(int col) {
        return CellReference.convertNumToColString(col);
    }

    private static Sheet sheetByName(Workbook wb, String name) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (wb.getSheetName(i).equalsIgnoreCase(name)) {
                return wb.getSheetAt(i);
            }
        }
        throw new IllegalStateException("Master sheet not found: " + name);
    }
}
