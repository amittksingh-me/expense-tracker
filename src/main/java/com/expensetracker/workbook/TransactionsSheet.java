package com.expensetracker.workbook;

import com.expensetracker.aggregate.Treatment;
import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The transient review surface: all bank transactions with their best-guess tags. Written on a
 * first/regenerate run; the human reviews/corrects the {@code Tag} column; read back on
 * {@code complete}. Pure read/write over a {@link Sheet} so it is unit-testable.
 */
public final class TransactionsSheet {

    public static final String NAME = "Transactions";
    private static final String[] HEADERS =
            {"Bank", "Txn Date", "Description", "Amount", "Sign", "Tag", "Effect", "Pinned", "System Note"};

    private TransactionsSheet() {
    }

    /**
     * Writes the review sheet. {@code systemNote} supplies the per-transaction auto hint (e.g.
     * "CC payment for HDFC RUPAY 3787 · Apr 2026"); {@code pinned} decides each row's {@code Pinned}
     * cell — {@code FALSE} for fresh rows, {@code TRUE} for rows whose pin is being carried over.
     */
    public static void write(Sheet sheet, List<TaggedTxn> txns,
            Function<TaggedTxn, String> systemNote, Predicate<TaggedTxn> pinned) {
        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        dateStyle.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("dd-mmm-yyyy"));

        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            header.createCell(i).setCellValue(HEADERS[i]);
        }
        int r = 1;
        for (TaggedTxn tt : txns) {
            BankTxn t = tt.txn();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(t.bank());
            Cell date = row.createCell(1);
            date.setCellValue(t.date());
            date.setCellStyle(dateStyle);
            row.createCell(2).setCellValue(t.description());
            row.createCell(3).setCellValue(t.amount().doubleValue());
            row.createCell(4).setCellValue(t.sign().name());
            row.createCell(5).setCellValue(tt.tag().name());
            row.createCell(6).setCellValue(Treatment.of(tt).label());   // Effect: derived hint (Expense / Credits/Transfers / Ignored)
            row.createCell(7).setCellValue(pinned.test(tt));            // Pinned (carried over on regenerate)
            String note = systemNote.apply(tt);
            if (note != null && !note.isEmpty()) {
                row.createCell(8).setCellValue(note);
            }
        }
        for (int c = 0; c < HEADERS.length; c++) {
            sheet.autoSizeColumn(c);
        }
    }

    public static List<TaggedTxn> read(Sheet sheet) {
        List<TaggedTxn> out = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            TaggedTxn tt = rowTxn(sheet.getRow(r));
            if (tt != null) {
                out.add(tt);
            }
        }
        return out;
    }

    /** Only the rows the human marked {@code Pinned = TRUE}, with their reviewed tag. */
    public static List<TaggedTxn> readPinned(Sheet sheet) {
        List<TaggedTxn> out = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (isPinned(row)) {
                TaggedTxn tt = rowTxn(row);
                if (tt != null) {
                    out.add(tt);
                }
            }
        }
        return out;
    }

    private static TaggedTxn rowTxn(Row row) {
        if (row == null || row.getCell(0) == null || str(row.getCell(0)).isBlank()) {
            return null;
        }
        String bank = str(row.getCell(0));
        LocalDate date = row.getCell(1).getLocalDateTimeCellValue().toLocalDate();
        String desc = str(row.getCell(2));
        BigDecimal amount = BigDecimal.valueOf(row.getCell(3).getNumericCellValue());
        Sign sign = Sign.valueOf(str(row.getCell(4)).trim().toUpperCase());
        Tag tag = Tag.valueOf(str(row.getCell(5)).trim().toUpperCase());
        return new TaggedTxn(new BankTxn(bank, date, desc, amount, sign), tag);
    }

    private static boolean isPinned(Row row) {
        if (row == null || row.getCell(7) == null) {
            return false;
        }
        Cell c = row.getCell(7);
        return (c.getCellType() == CellType.BOOLEAN && c.getBooleanCellValue())
                || (c.getCellType() == CellType.STRING && c.getStringCellValue().trim().equalsIgnoreCase("true"));
    }

    private static String str(Cell c) {
        return c != null && c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
    }
}
