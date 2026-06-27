package com.expensetracker.run;

import com.expensetracker.aggregate.Aggregator;
import com.expensetracker.aggregate.MonthlyFigure;
import com.expensetracker.card.AxisCardParser;
import com.expensetracker.card.CardStatementParser;
import com.expensetracker.card.HdfcCardParser;
import com.expensetracker.card.ParsedCardStatement;
import com.expensetracker.card.YesCardParser;
import com.expensetracker.config.Account;
import com.expensetracker.config.AppConfig;
import com.expensetracker.discover.StatementDiscovery;
import com.expensetracker.discover.StatementOverlap;
import com.expensetracker.extract.PdfTextExtractor;
import com.expensetracker.parser.BankStatementParser;
import com.expensetracker.parser.HdfcBankParser;
import com.expensetracker.parser.IciciBankParser;
import com.expensetracker.parser.NiyoBankParser;
import com.expensetracker.parser.StatementBalanceValidator;
import com.expensetracker.parser.YesBankParser;
import com.expensetracker.parser.model.ParsedBankStatement;
import com.expensetracker.recon.CardPaymentMatcher;
import com.expensetracker.recon.Reconciler;
import com.expensetracker.secret.SecretsProvider;
import com.expensetracker.tag.BankTxn;
import com.expensetracker.tag.PinnedOverrides;
import com.expensetracker.tag.Tag;
import com.expensetracker.tag.TaggedTxn;
import com.expensetracker.tag.TaggingEngine;
import com.expensetracker.workbook.TransactionsSheet;
import com.expensetracker.workbook.WorkbookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The run workflow state machine.
 *
 * <ul>
 *   <li><b>First run</b> (no working copy): write credit-card totals to the matrix, generate the
 *       Transactions review sheet from the (tagged) bank statements, set status = {@code pending}.
 *   <li><b>pending</b>: leave untouched — wait for the human to review tags + set status.
 *   <li><b>complete</b>: read the reviewed tags, push bank figures, reconcile cards, delete the
 *       Transactions sheet. Idempotent — re-running once finalized is a no-op.
 *   <li><b>regenerate</b>: rebuild the Transactions sheet from current config (carrying pins over
 *       in place), back to pending.
 * </ul>
 */
public final class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final AppConfig config;
    private final SecretsProvider secrets;
    private final PdfTextExtractor extractor = new PdfTextExtractor();

    public Orchestrator(AppConfig config, SecretsProvider secrets) {
        this.config = config;
        this.secrets = secrets;
    }

    public void run() {
        List<StatementDiscovery.Match> matches =
                new StatementDiscovery().discover(config.workingDir(), config.accounts());
        Path output = config.outputWorkbook();
        String master = secrets.password("MASTER");
        boolean firstRun = !Files.exists(output);

        try (WorkbookService wb = WorkbookService.open(
                firstRun ? config.inputWorkbook() : output, master, config.masterSheet())) {
            config.banks().forEach(b -> wb.registerBank(b.label()));
            config.cards().forEach(c -> wb.registerCard(c.label()));

            // Integrity guard: the matrix must hold at most one row per month. Duplicates mean the
            // workbook was hand-edited/corrupted; writing would silently update only one — fail loud.
            List<LocalDate> dupMonths = wb.duplicateMonthRows();
            if (!dupMonths.isEmpty()) {
                throw new IllegalStateException("Matrix has duplicate Month Key rows for " + dupMonths
                        + " — at most one row per month is allowed; fix the workbook and re-run.");
            }

            if (firstRun) {
                log.info("first run (no working copy) — generating review surface");
                generatePending(wb, matches, List.of());
                wb.setStatus("pending");
                wb.save(output, master);
                log.info("STATUS=pending -> review the '{}' sheet in {}, then set Control!B1 and re-run:",
                        TransactionsSheet.NAME, output.getFileName());
                log.info("    complete    -> push the reviewed figures into the matrix");
                log.info("    regenerate  -> rebuild the sheet after a rule-config change (re-applies pins)");
                log.info("  one-off fix: set the row's Tag + Pinned = TRUE (kept across regenerate)");
                return;
            }

            String status = wb.getStatus();
            String state = status == null ? "pending" : status.trim().toLowerCase();
            log.info("working copy present, status = {}", state);
            switch (state) {
                case "complete" -> {
                    if (!wb.hasTransactionsSheet()) {
                        log.info("STATUS=complete and already finalized (no Transactions sheet) — nothing to do.");
                        return;
                    }
                    List<TaggedTxn> reviewed = wb.readTransactionsSheet();   // reviewed tags incl. pinned ones
                    pushBankFigures(wb, reviewed);
                    doReconcile(wb, reviewed);
                    wb.deleteTransactionsSheet();
                    wb.setStatus("complete");
                    wb.save(output, master);
                    archiveProcessed(matches);   // cycle done → move PDFs out of the active working dir
                    log.info("STATUS=complete -> bank figures pushed, cards reconciled, "
                            + "Transactions sheet removed. Copy the workbook back to finalize.");
                }
                case "regenerate" -> {
                    generatePending(wb, matches, wb.readPinned());   // re-tag from config, carry pins over in place
                    wb.setStatus("pending");
                    wb.save(output, master);
                    log.info("STATUS=regenerate -> review surface rebuilt (pins re-applied); back to pending.");
                }
                default -> {
                    // pending claims a review sheet exists; if it's gone the workbook is inconsistent
                    // (don't silently rebuild after an accidental edit). regenerate is the recovery path.
                    if (!wb.hasTransactionsSheet()) {
                        throw new IllegalStateException("STATUS=" + state + " but the Transactions sheet "
                                + "is missing — workbook is inconsistent. Set Control!B1 = regenerate to "
                                + "rebuild the review sheet from the statements.");
                    }
                    log.info("STATUS=pending -> awaiting review. "
                            + "Set Control!B1 = complete or regenerate, then re-run.");
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * After a finalized run, move the cycle's statement PDFs into {@code workingDir/processed/<run_id>/}
     * so only unprocessed PDFs remain active (prevents accidental reprocessing; keeps an audit trail).
     * {@code Files.list} in discovery is top-level only, so the archive is never re-scanned.
     */
    private void archiveProcessed(List<StatementDiscovery.Match> matches) {
        if (matches.isEmpty()) {
            return;
        }
        String runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dest = config.workingDir().resolve("processed").resolve(runId);
        try {
            Files.createDirectories(dest);
            for (StatementDiscovery.Match m : matches) {
                Path f = m.file();
                if (Files.exists(f)) {
                    Files.move(f, dest.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    log.info("  archived {} -> processed/{}/", f.getFileName(), runId);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to archive processed PDFs to " + dest, e);
        }
    }

    /** First-run / regenerate: tag banks → Transactions sheet (pins applied/marked); CC totals → matrix. */
    private void generatePending(WorkbookService wb, List<StatementDiscovery.Match> matches, List<TaggedTxn> pins) {
        List<TaggedTxn> tagged = PinnedOverrides.apply(parseAndTagBanks(matches), pins);
        Set<String> pinnedIds = pins.stream().map(p -> PinnedOverrides.identity(p.txn())).collect(Collectors.toSet());
        CardPaymentMatcher matcher = new CardPaymentMatcher(config.cards());
        wb.writeTransactionsSheet(tagged, tt -> noteFor(tt, matcher),
                tt -> pinnedIds.contains(PinnedOverrides.identity(tt.txn())));
        log.info("  wrote {} bank transactions{} to the Transactions sheet",
                tagged.size(), pins.isEmpty() ? "" : " (" + pins.size() + " pinned carried over)");
        writeCardsToMatrix(wb, matches);
    }

    private List<TaggedTxn> parseAndTagBanks(List<StatementDiscovery.Match> matches) {
        List<BankTxn> unified = new ArrayList<>();
        List<StatementOverlap.Period> periods = new ArrayList<>();
        for (StatementDiscovery.Match m : matches) {
            Account a = m.account();
            if (a.skip() || !a.isBank()) {
                continue;
            }
            List<String> lines = extractor.extract(m.file(), secrets.password(a.label()));
            ParsedBankStatement ps = bankParser(a).parse(lines);
            if (ps.periodStart() == null || ps.periodEnd() == null) {
                throw new IllegalStateException("Cannot determine statement period for " + a.label()
                        + " (" + m.file().getFileName() + ") — incomplete or unrecognized statement.");
            }
            // Parse-correctness guard: if the statement printed opening/closing balances, the
            // extracted transactions must reconcile to them (else extraction dropped/added rows).
            if (StatementBalanceValidator.hasPrintedBalances(ps) && !StatementBalanceValidator.balances(ps)) {
                throw new IllegalStateException("Statement for " + a.label() + " (" + m.file().getFileName()
                        + ") does not reconcile to its printed totals — parse error, aborting.");
            }
            periods.add(new StatementOverlap.Period(a.label(), ps.periodStart(), ps.periodEnd()));
            ps.transactions().forEach(t -> unified.add(BankTxn.of(a.label(), t)));
        }
        StatementOverlap.check(periods);   // fail-loud on duplicate / re-issued statements
        return new TaggingEngine(config.rules()).tag(unified);
    }

    private void writeCardsToMatrix(WorkbookService wb, List<StatementDiscovery.Match> matches) {
        Set<String> seenCardMonths = new HashSet<>();   // fail-loud on a duplicate card+billing-month
        for (StatementDiscovery.Match m : matches) {
            Account a = m.account();
            if (!a.isCard()) {
                continue;
            }
            if (a.skip()) {
                log.info("  skipped card {} (ignored / image-only)", a.label());
                continue;
            }
            List<String> lines = extractor.extract(m.file(), secrets.password(a.label()));
            ParsedCardStatement cs = cardParser(a).parse(lines);
            YearMonth billMonth = YearMonth.from(cs.billingDate());
            if (!seenCardMonths.add(a.label() + "|" + billMonth)) {
                throw new IllegalStateException("Duplicate card statement for " + a.label()
                        + " billing month " + billMonth + " — remove the stale/duplicate file and re-run.");
            }
            LocalDate monthKey = billMonth.atDay(1);
            wb.writeCard(a.label(), monthKey, cs.totalAmountDue());
            log.info("  card {} {} = {}", a.label(), monthKey, cs.totalAmountDue().toPlainString());
        }
    }

    private void pushBankFigures(WorkbookService wb, List<TaggedTxn> tagged) {
        for (MonthlyFigure f : new Aggregator().aggregate(tagged)) {
            wb.writeBank(f.bank(), f.month().atDay(1), f);
            log.info("  bank {} {}  debits={}  credits/transfers={}  net={}",
                    f.bank(), f.month(), f.bankDebits().toPlainString(),
                    f.creditsTransfers().toPlainString(), f.netExpenses().toPlainString());
        }
    }

    private void doReconcile(WorkbookService wb, List<TaggedTxn> tagged) {
        Reconciler.Result recon = new Reconciler().reconcile(tagged, config.cards());
        for (Reconciler.Action act : recon.actions()) {
            var old = wb.reconcileCard(act.cardLabel(), act.priorEom(), act.actual());
            if (old == null) {
                log.info("  reconcile skipped (no prior-month row, or cell already verified): {} {}",
                        act.cardLabel(), act.priorEom());
            } else {
                log.info("  reconciled (verified green) {} {} = {}",
                        act.cardLabel(), act.priorEom(), act.actual().toPlainString());
            }
        }
        recon.unmatched().forEach(u -> log.info("  reconcile skipped (no card match): {}", u));
    }

    /** System Note: name the card a cc-payment settles, e.g. "CC payment for HDFC RUPAY 3787 · Apr 2026". */
    private static String noteFor(TaggedTxn tt, CardPaymentMatcher matcher) {
        if (tt.tag() != Tag.CC_PAYMENT) {
            return "";
        }
        Account card = matcher.match(tt.txn().bank(), tt.txn().description());
        YearMonth bill = YearMonth.from(tt.txn().date()).minusMonths(1);
        String month = bill.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + bill.getYear();
        String who = card == null ? "unmatched card"
                : card.label() + (card.paymentPattern() == null ? "" : " " + card.paymentPattern());
        return "CC payment for " + who + " · " + month;
    }

    private static BankStatementParser bankParser(Account a) {
        return switch (a.formatKey().toUpperCase()) {
            case "HDFC_BANK", "HDFC" -> new HdfcBankParser();
            case "NIYO_BANK", "NIYO" -> new NiyoBankParser();
            case "YES_BANK", "YES" -> new YesBankParser();
            case "ICICI_BANK", "ICICI" -> new IciciBankParser();
            default -> throw new IllegalStateException(
                    "No bank parser for format '" + a.formatKey() + "' (account " + a.label() + ")");
        };
    }

    private static CardStatementParser cardParser(Account a) {
        return switch (a.formatKey().toUpperCase()) {
            case "HDFC_CARD", "HDFC CC", "HDFC RUPAY" -> new HdfcCardParser(a.label());
            case "YES_CARD", "YES CC" -> new YesCardParser();
            case "AXIS_CARD", "AXIS CC" -> new AxisCardParser();
            default -> throw new IllegalStateException(
                    "No card parser for format '" + a.formatKey() + "' (account " + a.label() + ")");
        };
    }
}
