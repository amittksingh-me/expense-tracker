# Personal Expense Tracker

A local-first tool that reads password-protected bank & credit-card statements and maintains a
monthly expense matrix in a password-protected Excel workbook. Everything runs locally;
classification is rules-based; nothing is sent to any cloud/AI service.

See [docs/business-requirements.md](docs/business-requirements.md) (what) and
[docs/architecture.md](docs/architecture.md) (how).

## Pipeline (small, testable components)

```
discover → extract (pdftotext) → parse (per bank/card) → tag (rules) → aggregate
        → write matrix + reconcile cards (POI) → save encrypted output
```

| Component | Package |
|---|---|
| Bank parsers (HDFC, NIYO, YES, ICICI) | `parser` |
| Card parsers (HDFC, YES, AXIS) | `card` |
| Tagging engine (config rules, first-match) | `tag` |
| Aggregator (per-account, per-month figures) | `aggregate` |
| Reconciler (cc-payment → prior-month card) | `recon` |
| Workbook service (encrypted POI read/write) | `workbook` |
| Config / Secrets / Extract / Discover | `config` `secret` `extract` `discover` |
| Orchestrator + CLI | `run` |

## Prerequisites

- Java 21, Maven
- `qpdf` + `poppler` (`brew install qpdf poppler`) — provides `pdftotext`
- Statement passwords + the workbook password in the macOS Keychain

## Keychain setup (one entry per account label + `MASTER` for the workbook)

```bash
security add-generic-password -s expense-tracker -a "HDFC"   -T /usr/bin/security -U -w
# ... repeat for NIYO, YES, ICICI, "HDFC CC", "HDFC RUPAY", "YES CC", "AXIS CC", and MASTER
```

**Rotated statement passwords (fallbacks).** If a bank changes its PDF password, keep the old one
as a numbered fallback so older months still open: store the previous password as `"<account> 1"`,
the one before that as `"<account> 2"`, and so on (contiguous from 1). The tool tries the current
password first, then each fallback in order, and only fails if none decrypt the file.

```bash
# YES rotated its password — keep the old one as the first fallback:
security add-generic-password -s expense-tracker -a "YES 1" -T /usr/bin/security -U -w   # old password
```

## Configure

`src/main/resources/config.yaml` is a **public template** with placeholder values. For real runs,
copy it to **`src/main/resources/config.local.yaml`** (same folder, **gitignored**) and fill in
your real paths, card last-4 (`paymentPattern`), and self-transfer payee name:

```bash
cp src/main/resources/config.yaml src/main/resources/config.local.yaml   # then edit the real values
```

`Main` loads `config.local.yaml` automatically from the classpath when present (it takes
precedence over the template). Passwords are never in config — they come from the macOS Keychain.

## Run

```bash
mvn -q compile exec:java                                       # auto-loads config.local.yaml (else config.yaml)
mvn -q compile exec:java -Dexec.args="/path/to/other.yaml"     # external config override
mvn test                                                       # unit tests
```

**IntelliJ:** a ready-made run config lives in `.run/` (auto-detected) — **Expense Tracker**
(runs `com.expensetracker.run.Main`). Just pick it from the Run dropdown.

The original input workbook is never modified; results are written to the configured
`outputWorkbook`. Review it, then copy it back over your stable workbook.

## Current status & notes

- **End-to-end works** on real encrypted statements → encrypted output workbook.
- **Review loop built:** `pending` → (human reviews tags / pins one-offs) → `complete` (push to
  matrix, reconcile, verified-green) or `regenerate` (re-tag, pins kept in place). Re-running once
  finalized is a no-op.
- **All four cards parsed**, incl. **AXIS CC** (`AxisCardParser` — the statement is text-based, not
  image-only as first assumed).
- **Month key** uses 1st-of-month to match the existing `Expenses` sheet (the spec text says
  end-of-month; the real workbook uses the 1st).
- **Open items:** a packaged jar (runs via `exec:java`/IntelliJ today). See
  `docs/architecture.md` → Deferred.
