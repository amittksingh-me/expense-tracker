package com.expensetracker.workbook;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and caches the matrix cell styles and detects the verified-green fill — the single place
 * that knows the card-cell colour conventions:
 * <ul>
 *   <li><b>base</b> — mirrors the reference row's font, number format, borders, and fill (for bank figures + formulas);
 *   <li><b>unverified</b> — light yellow (fresh card amount, not yet reconciled);
 *   <li><b>amber</b> — light orange (a previously-verified value overwritten by a newer statement);
 *   <li><b>verified</b> — green (confirmed against the bank debit), cloned from an existing green
 *       cell so the exact shade/font match, with a fixed fallback.
 * </ul>
 * Package-private collaborator of {@link WorkbookService}; not part of any public API.
 */
final class MatrixCellStyles {

    static final String VERIFIED_GREEN = "9BBB59";   // matches the existing verified-CC fill

    private final Workbook wb;
    private final Sheet sheet;
    private final int refRow;                         // existing data row to copy fonts/formats from (or -1)
    private final Map<Integer, CellStyle> baseCache = new HashMap<>();
    private final Map<Integer, CellStyle> unverifiedCache = new HashMap<>();
    private final Map<Integer, CellStyle> amberCache = new HashMap<>();
    private final CellStyle verifiedStyle;

    MatrixCellStyles(Workbook wb, Sheet sheet, int refRow, int firstDataRow) {
        this.wb = wb;
        this.sheet = sheet;
        this.refRow = refRow;
        this.verifiedStyle = buildVerified(firstDataRow);
    }

    /**
     * Style for {@code col} cloned from the reference row's cell in that column — so an appended row
     * keeps the existing rows' **font, number format, borders, and background fill** (column shading).
     * (Card columns never use this directly; they overlay yellow/amber/green on top.)
     */
    CellStyle base(int col) {
        return baseCache.computeIfAbsent(col, c -> {
            CellStyle s = wb.createCellStyle();
            Row rr = refRow >= 0 ? sheet.getRow(refRow) : null;
            Cell ref = rr == null ? null : rr.getCell(c);
            if (ref != null) {
                s.cloneStyleFrom(ref.getCellStyle());   // preserves fill/borders/format of the row above
            }
            s.setWrapText(false);
            return s;
        });
    }

    /** Base style + light-yellow fill — the unverified card state. */
    CellStyle unverified(int col) {
        return unverifiedCache.computeIfAbsent(col, c -> withFill(base(c), IndexedColors.LIGHT_YELLOW));
    }

    /** Base style + amber fill — the revised card state (previously green, overwritten anew). */
    CellStyle amber(int col) {
        return amberCache.computeIfAbsent(col, c -> withFill(base(c), IndexedColors.LIGHT_ORANGE));
    }

    /** The verified-CC (green) style. */
    CellStyle verified() {
        return verifiedStyle;
    }

    private CellStyle withFill(CellStyle baseStyle, IndexedColors colour) {
        CellStyle s = wb.createCellStyle();
        s.cloneStyleFrom(baseStyle);
        s.setFillForegroundColor(colour.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle buildVerified(int firstDataRow) {
        for (int r = firstDataRow; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 1; c <= 6; c++) {                 // scan the card columns for an existing green cell
                Cell cell = row.getCell(c);
                if (cell != null && isGreen(cell)) {
                    CellStyle s = wb.createCellStyle();
                    s.cloneStyleFrom(cell.getCellStyle());
                    s.setWrapText(false);
                    return s;
                }
            }
        }
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();   // fallback if none found
        s.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 0x9B, (byte) 0xBB, (byte) 0x59}, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    /** True if {@code cell} carries the verified-green fill. */
    static boolean isGreen(Cell cell) {
        CellStyle st = cell.getCellStyle();
        if (st.getFillPattern() != FillPatternType.SOLID_FOREGROUND) {
            return false;
        }
        return st instanceof XSSFCellStyle xs
                && xs.getFillForegroundColorColor() instanceof XSSFColor xc
                && xc.getARGBHex() != null
                && xc.getARGBHex().toUpperCase().endsWith(VERIFIED_GREEN);
    }
}
