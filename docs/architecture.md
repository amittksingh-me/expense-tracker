# Architecture & Design — Personal Expense Tracker

> **Implementation/design decisions.** The *what* (business logic) lives in
> [business-requirements.md](business-requirements.md); this doc is the *how*. Where the two
> disagree, business-requirements wins.

## Runtime & build

- **Plain Java 21, no Spring.** This is an occasional, local, single-user **batch CLI** — it
  starts, processes a handful of statements, writes the workbook, exits. Spring Boot's value
  (DI-at-scale, embedded server, autoconfig, lifecycle) buys nothing here and adds startup
  cost/weight. The dependency graph is small and fixed → **manual wiring in `main()`**.
- **CLI args:** plain `args[]` — an optional config-file path (defaults to the classpath
  `config.yaml`). No other flags yet; picocli only if the option set grows.
- **Build:** **Maven**. Run via `exec:java` or the IntelliJ run config; a single runnable
  (shaded) jar is deferred.
- **Logging:** SLF4J via **slf4j-simple → stdout** (configured in `simplelogger.properties`);
  POI's Log4j2 calls are bridged through **log4j-to-slf4j**. Errors fail loud (non-zero exit, no
  partial writes).

## PDF extraction & decryption

- **`pdftotext -layout` (poppler) is the target-state extractor**, behind a `PdfTextExtractor`
  interface. Rationale: `-layout` preserves the visual **column alignment**, which is what makes
  the bank transaction table parseable; PDFBox's default text stripping collapses columns and
  would need custom positional code to match it.
- **Decryption is the same tool:** `pdftotext -upw <password> -layout <pdf> -` decrypts *and*
  extracts in one call — so production needs **neither qpdf nor PDFBox** (qpdf was only for
  manual spiking). The password comes from the Keychain (see Secrets).
- **PDFBox is the documented fallback:** because parsers consume *text* (not PDFs), a
  `PdfBoxTextExtractor` could replace the poppler one by changing a single wiring line, if
  pure-Java portability ever matters. It does not today (personal Mac tool).

## Excel I/O

- **Apache POI (XSSF).** Responsibilities of `WorkbookService` (the *only* component that
  touches POI):
  - open the **password-protected** workbook and re-save it encrypted (password = Keychain `MASTER`);
  - read the master matrix, the `Control` status, and the Transactions sheet (incl. its pinned rows);
  - write/refresh the Transactions sheet;
  - **upsert** month rows — write only system-owned cells, **preserve native formulas and the
    manual `Comments`**, apply cell-fill **visual cues**;
  - `setForceFormulaRecalculation(true)` so year-scoped Median/Average refresh on open;
  - on `regenerate`, carry pinned rows over in place; delete the Transactions sheet on `complete`.

## Configuration & secrets

- **Config in YAML** (Jackson or SnakeYAML): accounts, mandates, paths (+ master sheet name),
  and the ordered tagging rules. Parsed into typed records, validated up front (fail-loud).
- **Passwords never live in config.** A `SecretsProvider` fetches each statement password from
  the **macOS Keychain** via the `security` CLI on demand (never logged).

### Account labels (the identifier)
A unique friendly **Label** per account is the single key used across **config**, the **matrix
column(s)**, the Transactions **`Bank` column**, and the **Keychain** (service `expense-tracker`,
account = the Label, read via `security find-generic-password`). Because it drives a distinct
matrix column, it must be unique — which is how two HDFC cards stay separate.

Current labels (will live in config):
- **Bank accounts (3):** `HDFC`, `YES`, `NIYO`
- **Credit cards (4):** `HDFC CC`, `YES CC`, `AXIS CC`, `HDFC RUPAY`
- **Workbook password:** key `MASTER` — the input/output Excel is **password-protected**; the same
  `SecretsProvider` fetches this to open and re-save the encrypted workbook.

All verified accessible in Keychain (service `expense-tracker`).

The Label is independent of the **PDF-match pattern**, the card **payment-identification
pattern**, and the **password** (Keychain) — those are separate per-account fields.

### Run configuration (current setup; all config-driven)
- **Config file:** `src/main/resources/config.yaml` (loaded from the classpath; pass a path
  argument to `Main`/`exec.args` to override with an external file).
- **Input workbook and output** both point at the working directory `~/Downloads/working`.
- **Master matrix sheet name:** `Expenses`.
- **Month row key:** the workbook uses **1st-of-month** dates (`01-May-2026`), not end-of-month.
  (Older 2025 rows use end-of-month; new appends follow the current 2026 convention.)
- **Cell formats (match the existing sheet):** dates `[$-809]dd mmmm yyyy`; amounts `"₹"#,##0.00`.
  New rows **clone their cell styles from an existing data row** (and the verified-green style
  from an existing green cell), so font/size (14), number format, and the exact green shade all
  match — rather than building styles from scratch.
- **Card-cell three-state colour:** **yellow** = *unverified* (fresh from a statement); **green**
  `FF9BBB59` = *verified* against the bank debit; **amber** (`LIGHT_ORANGE`) = *revised* — a value
  that was green and got overwritten by a re-processed statement. `writeCard` checks `isVerified`
  before overwriting: green→**amber**, else→**yellow** (it never guards/blocks the overwrite —
  newer statement is authoritative). Reconciliation treats amber like yellow (eligible → green) and
  still skips green. Bank figures are written with **no fill**.
- **Status (`Control`) sheet is visible** (not hidden), per user preference.
- **Processed-PDF archive:** on a finalized `complete` run, `Orchestrator.archiveProcessed` moves the
  cycle's statement PDFs to `workingDir/processed/<run_id>/` (timestamped). Discovery uses
  `Files.list` (top-level only), so the archive is never re-scanned. PDFs are *not* moved on
  first-run/`regenerate` (regenerate still needs them).
- **Mandates** (card → paying bank, payment-identification pattern):
  - `HDFC CC` ← HDFC bank, last-4 `8339`
  - `HDFC RUPAY` ← HDFC bank, last-4 `3787`
  - `YES CC` ← YES bank (only card from YES; pattern `CREDIT CARD`)
  - `AXIS CC` ← HDFC bank — **ignored for now** (statement is image-only/scanned; no parser).
- **Reconciliation tolerance:** while Axis is ignored, a `cc-payment` debit that matches **no**
  configured card is **logged and skipped** (not a hard abort). But **two debits matching the same
  card for the same prior month abort** (ambiguous double-payment/reversal), and a debit whose
  prior-month row doesn't exist is **skipped and logged**.

## Components & boundaries

```
CLI (args)
  └─ Orchestrator (state machine: first-run / pending / complete / regenerate)
       ├─ ConfigLoader        → accounts, mandates, rules, paths (YAML)
       ├─ SecretsProvider     → passwords from macOS Keychain (`security`)
       ├─ StatementDiscovery  → each PDF must resolve to exactly 1 account (fail-loud on 0/>1);
       │                        accounts need NOT have a statement in a run (missing ≠ error)
       ├─ PdfTextExtractor    → `pdftotext -upw -layout` → text lines      [ONE shared component]
       ├─ Parser dispatch (account **format** → per-institution parser; a `switch`)
       │     • BankStatementParser  (HDFC/NIYO/YES) → transactions + period + totals
       │     • CardStatementParser  (HDFC/YES)      → billing date + Total Amount Due
       ├─ StatementOverlap    → abort if two same-account BANK statements' periods overlap;
       │                        cards abort on a duplicate card+billing-month (both fail-loud)
       ├─ TaggingEngine       → ordered substring rules (first match wins) → tag; defaults
       ├─ PinnedOverrides     → overlay human-pinned one-off tags (by transaction identity)
       ├─ Treatment / Bucket  → (Sign,Tag) → Expense | Credits/Transfers | Ignored  [shared]
       ├─ Aggregator          → uses Treatment to sum per-account, per-calendar-month figures
       ├─ CardPaymentMatcher  → cc-payment debit → the card it settles (mandate + payment pattern)
       ├─ Reconciler          → uses CardPaymentMatcher; correct prior month's card column
       └─ WorkbookService (POI)
```

**Boundary discipline:** only `PdfTextExtractor` knows PDF mechanics; only `WorkbookService`
knows Excel; the middle (tagging, aggregation, reconciliation) is **pure domain logic, no I/O** —
which is where the business rules live and where unit tests concentrate.

**Card-payment resolution is one shared rule.** `CardPaymentMatcher` (mandate bank + payment
pattern → card, fail-loud on >1) is used **both** when the Transactions sheet's `System Note` is
written ("CC payment for HDFC RUPAY 3787 · Apr 2026") and by reconciliation — so the logic lives
in one place and `TaggingEngine` stays a pure rules→tag function. The `Pinned` column is
pre-populated `FALSE`.

**Bucketing is one shared rule too.** `Treatment.of(Sign, Tag) → Bucket` is the single source of
truth for how a transaction lands in the figures. The `Aggregator` uses it to sum, and the
Transactions sheet's read-only **`Effect`** column uses it to show the human what each row does
(`Expense` / `Credits/Transfers` / `Ignored`) — the one thing review is really checking. The
column is derived (never read back) and recomputed on every `regenerate`.

## State persistence (all inside the workbook, so it travels on copy-back)

- **Verification status** → a small **`Control` sheet** (`B1`); human-editable.
- **Pinned overrides** → kept **in place in the `Transactions` sheet** (no separate store). On
  `regenerate` the pinned rows are read, the unpinned rows are re-tagged from config, and the pins
  are **re-applied** (`PinnedOverrides.apply`, by normalized transaction identity) and re-marked
  `TRUE`; the pinned tag is what's used at `complete`. (Pins are date-keyed, so they only matter
  within their own review cycle — no cross-month durability needed.)
- **Verified-card state** → the **green fill is not cosmetic; it is persisted workbook state**
  meaning "this card amount was confirmed against the bank debit." Reconciliation reads it back
  (`isVerified`) to **skip already-verified cells**, so the cell colour is a real part of the data
  model — not just presentation. (Only a newer *statement* re-opens it; see the card two-state
  colour above.) Treat it as state, never as decoration.

## Parser design

- Parsers are **pluggable per institution behind `BankStatementParser` /
  `CardStatementParser`**, chosen by the account's **`format`** (not its label, so several accounts
  can share one format). A `switch` on `format` in the Orchestrator — fine for the current handful
  of formats.
- **Target: a generic layout-driven parser configured by a per-bank `BankFormat`** (header
  pattern, date format, column layout, sign convention, end marker, period/last-4 patterns),
  **reached by refactoring from concrete parsers** once real statements reveal the common
  structure. Custom parsers remain the **fallback for outliers**. We do *not* design the generic
  model up front — that would be guessing instead of observing.
- Key enabler: with `-layout`, columns are whitespace-aligned, so column **x-positions can be
  read from the header row** and used to slice each data line — a generic technique covering many
  layouts. The main thing resisting pure config is the **column model** (separate
  `Withdrawal`/`Deposit` columns vs a single amount + `Dr/Cr` indicator).

### Parser contract
- **Input:** the `pdftotext -layout` output as text lines — *not* a PDF, password, or file. (This
  is what makes parsers unit-testable on plain-text fixtures.)
- **Output (bank):** `ParsedBankStatement` = account last-4 + statement period + list of
  `RawBankTxn(date, description, amount, sign)` + `PrintedTotals`. **No tagging in the parser** —
  it reports raw facts only.
- **Output (card):** `ParsedCardStatement` = card last-4 + billing date + `Total Amount Due`.

## Bank pipeline: parse → unify → tag

Parsing yields one `ParsedBankStatement` per PDF (raw txns, no account label). A small
**enrich/combine** step attaches each account's configured label and concatenates all statements
into one **unified `List<BankTxn>`** (the shape of a Transactions-sheet row: bank, date,
description, amount, sign). Tagging then runs **in one shot** over that unified list — matching
the requirement of a single Transactions sheet and enabling **account-scoped rules**.

### Model
- `Tag` enum: `EXPENSE, INVESTMENT, CC_PAYMENT, SELF_TRANSFER, REFUND, SALARY, INTEREST`
- `Rule`: a pattern on `Description` + optional account/`Sign` scope → `Tag`
- `BankTxn`: a bank-labelled transaction (parser's `RawBankTxn` + bank)
- `TaggedTxn`: a `BankTxn` + its assigned `Tag`

### TaggingEngine (pure)
`tag(unified) → tagged` (rules supplied at construction):
- rules evaluated in **configuration order, first match wins**;
- no match → default: debit `EXPENSE`, credit `REFUND`.

`PinnedOverrides.apply(tagged, pins)` then overlays human-pinned one-off tags by normalized
transaction identity — kept separate so `TaggingEngine` stays a pure rules→tag function.

Loading rules from config is I/O and **deferred**; the engine takes them as objects.
**Pattern-matching mode:** a rule's pattern is a **case-insensitive substring** of the
`Description` (readable plain strings, no regex escaping — chosen for easy hand-editing).
Matches anywhere in the description; rules may also be scoped to an account and/or `Sign`.

### Testing (golden-file / characterization)
Parse all fixtures → unify with labels → tag with a fixed ruleset → compare against one
`tags.tsv` golden file: every transaction with a `Bank` column, in a fixed order, matched by
index with `amount`/`sign` as a sanity cross-check. The `expectedTag` column is the
human-curated answer key; rules are tuned until the engine reproduces it; any one-off that no
rule can express becomes a **pinned override**.

## Validation strategy

The parse-correctness guard is a **check against the statement's own printed totals**:
`opening + Σcredits − Σdebits == closing`. It is **enforced at run time** (`StatementBalanceValidator`,
called from the Orchestrator): if a bank statement prints opening/closing balances and they don't
reconcile, the run **aborts** (parse error). A statement that prints no balances is skipped (can't
be checked). *(This is parse validation — kept deliberately distinct, in name and package, from
credit-card **reconciliation** in `com.expensetracker.recon`, which matches a card's bill against
the bank debit.)*

## Testing strategy

- **Fixture-based characterization tests.** Each bank's `pdftotext -layout` output is saved as a
  fixture under `src/test/resources/fixtures/`; a per-bank test feeds it to the parser and
  asserts: the printed-totals balance check passes, transaction count, representative rows
  (date/amount/sign/description), period start/end, account last-4.
- **Pure-domain unit tests** for tagging, `Treatment`/bucketing, aggregation, the card matcher,
  pinned-override identity, and statement-overlap — no I/O.
- **Workbook acceptance tests** (`WorkbookServiceAcceptanceTest`) drive the highest-risk surface —
  the workbook mechanics — over a **synthetic, unencrypted** matrix (a test-only
  `WorkbookService.forTesting` factory bypasses encryption): upsert, the card two-state colour,
  reconcile + green-overwrite, and the Transactions/status round-trip. No real workbook/password.
- No CLI, no PDFs, no passwords in the test loop — fast and repeatable.
- Fixtures contain **real statement data** → keep local; if the repo is ever put under git, add
  the fixtures folder to `.gitignore`.

## Build sequence (risk-first)

1. **Bank parsing** (current phase) — prove the hardest, most uncertain part via fixtures + tests.
2. Card parsing (trivial: one field).
3. POI round-trip on a copy of the real workbook (don't corrupt formulas/other sheets).
4. Vertical slice: one bank end-to-end into the matrix.
5. Transactions sheet + status state machine + review loop.
6. Credit-card reconciliation.
7. Generalize: multiple accounts, config-driven, pins, error-handling polish.

## Deferred / open

- **AXIS CC** statement is image-only — needs OCR (or manual entry); ignored for now.
- **Packaging** — a single runnable (shaded) jar; today it runs via `exec:java` / IntelliJ.
- **Generic `BankFormat`** model — extracted later only if more banks justify it.
- **Pinned overrides** persist only within a review cycle (date-keyed); no cross-month store.
- **Workbook-level acceptance tests** — `WorkbookServiceAcceptanceTest` now drives the workbook
  mechanics (upsert, card two-state colour, reconcile/green-overwrite, Transactions/status
  round-trip) over a synthetic sheet. An Orchestrator full-cycle harness (first → complete →
  regenerate, needing PDFs/Keychain) is still verified manually via `exec:java`.
- **Multi-error reporting** — the run currently aborts on the *first* error (no partial writes);
  accumulating all discovery/parse errors into one report is a possible future improvement.
