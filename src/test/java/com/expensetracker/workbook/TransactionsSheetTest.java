package com.expensetracker.workbook;

import com.expensetracker.parser.model.Sign;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.PinnedOverrides;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionsSheetTest {

    @Test
    void roundTrips() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(TransactionsSheet.NAME);
            List<TaggedTxn> in = List.of(
                    new TaggedTxn(new BankTxn("HDFC", LocalDate.of(2026, 5, 1),
                            "ACH D- FUNDAB CAMS", new BigDecimal("50000.00"), Sign.DEBIT), Tag.INVESTMENT),
                    new TaggedTxn(new BankTxn("NIYO", LocalDate.of(2026, 5, 2),
                            "Int.Pd", new BigDecimal("50.00"), Sign.CREDIT), Tag.INTEREST));

            TransactionsSheet.write(sheet, in, t -> "", t -> false);
            List<TaggedTxn> out = TransactionsSheet.read(sheet);

            assertEquals(2, out.size());
            assertEquals("HDFC", out.get(0).txn().bank());
            assertEquals(LocalDate.of(2026, 5, 1), out.get(0).txn().date());
            assertEquals(Sign.DEBIT, out.get(0).txn().sign());
            assertEquals(Tag.INVESTMENT, out.get(0).tag());
            assertEquals(0, out.get(0).txn().amount().compareTo(new BigDecimal("50000.00")));
            assertEquals(Sign.CREDIT, out.get(1).txn().sign());
            assertEquals(Tag.INTEREST, out.get(1).tag());

            // derived Effect column (col 6): investment debit nets out; interest credit is ignored
            assertEquals("Credits/Transfers", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals("Ignored", sheet.getRow(2).getCell(6).getStringCellValue());
        }
    }

    @Test
    void readsHumanEditedTag() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(TransactionsSheet.NAME);
            TransactionsSheet.write(sheet, List.of(new TaggedTxn(
                    new BankTxn("YES", LocalDate.of(2026, 5, 3), "UPI/x", new BigDecimal("100.00"), Sign.DEBIT),
                    Tag.EXPENSE)), t -> "", t -> false);
            // human corrects the Tag cell (col 5) on the data row
            sheet.getRow(1).getCell(5).setCellValue("SELF_TRANSFER");

            assertEquals(Tag.SELF_TRANSFER, TransactionsSheet.read(sheet).get(0).tag());
        }
    }

    @Test
    void pinnedRowSurvivesReTag() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet tx = wb.createSheet(TransactionsSheet.NAME);
            TaggedTxn niyo = new TaggedTxn(new BankTxn("NIYO", LocalDate.of(2026, 5, 2),
                    "UPI/P2P/612253180018 570000/020526", new BigDecimal("4800.00"), Sign.CREDIT), Tag.REFUND);
            TransactionsSheet.write(tx, List.of(niyo), t -> "", t -> false);

            // human review: correct the tag + pin it (Pinned is col 7, after the derived Effect col 6)
            tx.getRow(1).getCell(5).setCellValue("SELF_TRANSFER");
            tx.getRow(1).getCell(7).setCellValue(true);

            java.util.List<TaggedTxn> pins = TransactionsSheet.readPinned(tx);
            assertEquals(1, pins.size());
            assertEquals(Tag.SELF_TRANSFER, pins.get(0).tag());

            // a fresh re-tag defaults this credit to REFUND; the carried-over pin must win
            java.util.List<TaggedTxn> reTagged = java.util.List.of(new TaggedTxn(niyo.txn(), Tag.REFUND));
            assertEquals(Tag.SELF_TRANSFER, PinnedOverrides.apply(reTagged, pins).get(0).tag());
        }
    }
}
