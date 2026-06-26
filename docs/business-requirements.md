# Business Requirements — Personal Expense Tracker

> **Business logic only** (what the system must do). Lower-level implementation details
> (PDF extraction technique, libraries, exact storage format) are deliberately left out here.

## Goal

Track overall **true monthly expense**. Inputs are password-protected bank & credit-card
statements. Output is a wide **monthly matrix** (one row per month) that the user maintains
today by hand — the system should reproduce and maintain it.

**Core principle:** the **Excel matrix is the single source of truth**; the Transactions sheet is
a **transient review surface**, never a ledger. PDFs are input that feed the matrix — the system
deliberately does **not** build a separate transaction database.

## Privacy requirement

- Statement files are decrypted/read **locally only**.
- Classification is **fully local, rules-based**.
- **No cloud is used at all** — input and output are local Excel files in local folders.
  Nothing is ever sent to any third-party processor or AI service.
- The **workbook itself is password-protected** (encrypted at rest); it is opened and re-saved
  using a locally-held password, never written unencrypted.

## Configuration (user-provided, makes the system generic)

### Accounts
The user declares a list of accounts. Each account has:
- **Type** — `bank account` or `credit card`
- **Label** — display name; also drives the account's column(s) in the matrix
- **Format** — which statement parser to use. **Deliberately separate from the label**, because
  several accounts can share one statement format (e.g. two HDFC bank accounts). If omitted, the
  parser identifier **defaults to the Label**.
- **PDF pattern** — pattern/regex used to identify that account's statement file(s)
- **Password** — used to open its PDFs (generally 1:1 with the account)

The **matrix layout is derived from this config**: each `credit card` → one column; each
`bank account` → a `Bank Debits / Credits/Transfers / Net Expenses` triplet. (The matrix has 4
card columns + 4 bank triplets; the current config has 4 cards + 3 bank accounts — the 4th
triplet has no statement yet. Nothing is hard-coded.)

### Mandates (which card is paid from which bank account)
A mapping from each **credit card → the bank account its bill is debited from**. A single bank
account may pay multiple cards. This mapping drives **reconciliation** (it tells the system
which bank account's debit to match against each card's provisional total).

Because one bank may pay several cards, each card also carries a **payment-identification
pattern** (typically its last-4 digits, but any regex/substring on the debit description) so the
system can match each `cc-payment` debit to the correct card column.

### Paths
- **Input Excel** — path to the last stable version of the workbook (the "main folder"), **plus
  the name of the master matrix sheet** within it. The workbook may contain other sheets
  (dashboards, notes, etc.) that the system must leave **untouched**.
- **Working directory** — where statement PDFs are dropped and where the process operates.

### Tagging rules
Classification is **rule-based**: the system auto-tags bank transactions using a
user-maintainable set of **rules**. Each rule is a **case-insensitive text-substring match on the
`Description`** (the user specifies a distinctive, readable part of a transaction's narration),
optionally scoped to a specific account or `Sign`, and assigns a `Tag`. **Rules are evaluated in
configuration order — the first matching rule wins** (so order specific rules above broad ones).
When no rule matches, defaults apply: a debit → `expense`, a credit → `refund`.

> **Decision:** patterns are plain substrings (chosen for readability), not regex. Full regex is
> deferred — it can be reintroduced behind a per-rule `patternType: SUBSTRING | REGEX` flag if a
> narration ever needs it.

Rule maintenance is **manual and configuration-driven**. When the user spots a mis-tag in the
generated Transactions sheet, they **update the rule configuration themselves** (add or refine
a description pattern); on the next run the process simply **picks up the updated configuration**
and re-tags. Corrections therefore live in the config and are **inherently persistent** across
months — the system never auto-generates a rule from a correction. (The `regenerate` status,
below, is the path for re-tagging after a config change.) _(The rule storage format is an
implementation detail, defined later.)_

### Configuration validation
Before any statement is processed, the system **validates the configuration for internal
consistency and aborts (fail-loud) on any error**, including: **duplicate account labels**, a
credit card whose **mandate refers to an unknown bank account**, or an **active (non-skipped) card
missing its payment-identification pattern**. (PDF-pattern *ambiguity* is not pre-checked — it is
caught at run time, where a statement file matching more than one account aborts discovery.)

## The monthly matrix (source of truth, one row per month — NOT a ledger)

Columns, in order (for the current 4-card / 4-bank configuration):

| Column(s) | Meaning |
|---|---|
| `Month Key` | Row key for the month — **the first day of the calendar month** (the workbook's `Date` column, e.g. `01-Apr-2026` represents April 2026). |
| `YES CC`, `HDFC Rupay`, `HDFC CC`, `Axis CC` | 4 credit cards — each a single monthly total |
| `Comments` | **Manual only** — user logs deltas/notes. System never writes here. |
| 4 × (`Bank Debits`, `Credits/Transfers`, `Net Expenses`) | One triplet per **bank account** (4 accounts) |
| `Net Bank Expenses` | Sum of the 4 banks' `Net Expenses` |
| `Total Expenses` | `Net Bank Expenses + CC Expense` |
| `CC Expense` | Sum of the 4 card columns |
| `Median Expense` | Median of `Total Expenses` across all rows of the same `Year` |
| `Year` | Calendar year of the row; groups Median/Average |
| `Average Expense` | Average of `Total Expenses` across all rows of the same `Year` |

Formulas:
- `Net Expenses (per bank) = Bank Debits − Credits/Transfers`
- `Net Bank Expenses = Σ Net Expenses (banks)`
- `CC Expense = Σ card columns`
- `Total Expenses = Net Bank Expenses + CC Expense`
- `Median/Average Expense` = median/average of `Total Expenses` within the calendar `Year`
  (every row in a year shows the same value).

All of these derived columns (`Net Expenses`, `Net Bank Expenses`, `CC Expense`,
`Total Expenses`, `Median Expense`, `Average Expense`, and `Year` = `YEAR(Month Key)`)
are kept as **native Excel formulas**. The system writes only the **raw inputs**: the
credit-card totals and each bank's `Bank Debits` and `Credits/Transfers`. So year-scoped
Median/Average **recompute automatically** when a new row is added — the system never has to
update prior rows. When a new row is added, the formula columns **must remain live Excel
formulas**, never written as static values.

> **Definition — "system-owned cells":** the raw-input cells the system writes — the **credit-card
> columns** and each bank's **`Bank Debits`** and **`Credits/Transfers`**. Everything else in the
> matrix is either **formula-driven** (the derived columns above) or **manual** (`Comments`). The
> system overwrites system-owned cells on its runs; it never writes the formula or manual cells.
> The **card-cell verification colour (yellow/green/amber) is also system-owned formatting** — it is set
> by the system and may be overwritten on later runs, so it should not be hand-edited.

## Bank-account transaction processing — **bank accounts only**

Transaction tagging applies **only to `bank account` type**. Credit cards are never itemized
(see below).

**Month boundary = calendar month.** Each bank transaction is assigned to a month row by the
**calendar month of its transaction date**. The per-bank figures for a month are the sums over
that calendar month. A single run may produce **multiple** month rows if the dropped statements
span several months.

### Transactions sheet schema
There is a **single** Transactions sheet holding all bank transactions (the `Bank` column
distinguishes accounts). Every transaction is written as one row.

Rows are written in a **stable order: grouped by account (configuration order), and chronological
within each account** — matching the human's "one bank at a time" review. The order is
deterministic, so a `regenerate` reproduces the same layout.

Only **`Tag`** and **`Pinned`** are user-editable. `Bank`, `Txn Date`, `Description`, `Amount`,
`Sign`, `Effect`, and `System Note` are **system-owned** and regenerated on every rebuild —
hand-editing them is unsupported (and, because identity is matched on those fields, would also
break a row's pin on `regenerate`).

| Column | Meaning |
|---|---|
| `Bank` | Account label (which bank account) |
| `Txn Date` | Transaction date |
| `Description` | Raw narration from the statement |
| `Amount` | Transaction amount |
| `Sign` | `debit` or `credit` |
| `Tag` | Classification (see vocabulary below) — the human reviews this; **recurring** corrections are made by updating the rule config, then re-tagging (not by hand-editing the cell). The one exception is a `pinned` one-off (see below). |
| `Effect` | **Derived hint, read-only** — how this row's `Sign`+`Tag` lands in the figures: `Expense`, `Credits/Transfers`, or `Ignored`. Lets the human focus review on what actually moves Net Expense (chiefly the `Credits/Transfers` rows). The system never reads it back; it is recomputed on every `regenerate` from the current `Tag`. |
| `Pinned` | Optional human marker, **pre-populated `FALSE`** (flip to `TRUE` to pin). When `TRUE`, this row's hand-edited `Tag` is a **one-off override**: kept across `regenerate` (matched by transaction identity) and used at `complete`, never auto-re-tagged. |
| `System Note` | Auto-generated hint. For a `cc-payment` it **names the card it settles and the bill month**, e.g. "CC payment for HDFC RUPAY 3787 · Apr 2026". The card is resolved at transaction-processing time (the same matcher reconciliation reuses). |

### Tag vocabulary
`Sign` + `Tag` together decide treatment.

| Tag | Applies to | Treatment |
|---|---|---|
| `expense` | debit | True expense — counts toward Net Expense. **Default for an unrecognized debit.** |
| `investment` | debit **or** credit | **Debit** (money going into an investment) → `Credits/Transfers`. **Credit** (redemption / maturity / mutual-fund proceeds — your own money returning) → **ignored entirely** |
| `cc-payment` | debit | Credit-card bill payment → `Credits/Transfers`; **also drives reconciliation** against the card column |
| `self-transfer` | debit **or** credit | Movement between your own or **family** (spouse/children) accounts. **Debit** → `Credits/Transfers`; **incoming credit** → **ignored entirely** |
| `refund` | credit | External money back (merchant refund/cashback/reimbursement) → `Credits/Transfers`, reduces Net Expense. **Default for any credit not identified as `salary`, `interest`, `self-transfer`, or `investment`.** |
| `salary` | credit | Income → **ignored entirely** |
| `interest` | credit | Bank interest → **ignored entirely** |

Credits are handled **by exclusion**: a credit identified as `salary`, `interest`, an incoming
`self-transfer` (own/family account), or an `investment` inflow (redemption/maturity/MF
proceeds) is ignored; **every other credit defaults to `refund`** (we don't try to positively
detect refunds). The human corrects any mis-tag during review.

### Pinned overrides (one-offs)
For a genuine one-off that doesn't warrant a reusable description pattern, the human hand-edits
the row's `Tag` to the correct category and sets the **`Pinned`** column to `TRUE` (it defaults to
`FALSE`). On **`regenerate`** the pinned overrides are **recovered from the existing Transactions
sheet before it is discarded**, then the sheet is rebuilt: the *unpinned* rows are re-tagged from
the current config and each recovered pin is **re-applied in place** (matched by transaction
identity), so the one-off survives the rebuild. On **`complete`** the reviewed tag — pinned or not
— is what feeds the matrix. This is the only case where a tag is set by hand rather than via the
rule config.

Pins live **in the Transactions sheet** for the review cycle (they are not stored in a separate
ledger). Because a pin is keyed by transaction identity that includes `Txn Date`, it only ever
matches its own transaction within that cycle. **`regenerate` may rebuild the sheet from scratch —
row position is never part of transaction identity**, so a pin re-attaches to its row wherever it
lands. A pin **survives `regenerate` only while its underlying transaction still exists in the
regenerated statement set**; if a corrected statement no longer contains that transaction, the pin
naturally disappears (it has nothing to attach to).

**Transient transaction identity** (review-cycle scoped — used only to re-attach pins during a
rebuild, **not** a persistent ledger key) = `Bank` + `Txn Date` + `Description` + `Amount` + `Sign` (all five —
`Sign` is required so that an equal-amount debit and credit on the same day do not collide).
Comparison is normalized: `Description` **whitespace-normalized**, `Amount` to **2 decimal
places**, and `Txn Date` as a **date only** (no time component).

### Formulas (per bank account, per calendar month)
All sums below are scoped to **one bank account within one calendar month** (the month row).
- **`Bank Debits`** = Σ `Amount` where `Sign = debit` (all debits in that month, gross).
- **`Credits/Transfers`** = Σ debits tagged `investment`, `cc-payment`, `self-transfer`
  **+** Σ credits tagged `refund`.
- **`Net Expenses`** = `Bank Debits − Credits/Transfers`
  (nets out to: Σ `expense` debits − Σ `refund` credits).
- **Ignored entirely** (in no column): credits tagged `salary`, `interest`, `self-transfer`,
  `investment`.

Worked example — ₹10,000 moved from Bank 1 → Bank 2 (both the user's accounts):
- Bank 1: debit 10,000 tagged `self-transfer` → `Credits/Transfers` → excluded from Bank 1 Net Expense.
- Bank 2: incoming credit 10,000 tagged `self-transfer` → **ignored entirely**.
- A merchant refund into Bank 2 → tagged `refund` → added to Bank 2's `Credits/Transfers` → reduces its Net Expense.

## Credit cards: scope, month mapping & reconciliation

- From a credit-card statement, the system takes **only the statement's `Total Amount Due`**
  (the figure the mandate auto-debits — *not* total outstanding, current balance, or minimum
  due) — no itemizing, no tagging.
- The statement is placed on the **month row of its billing date** (the workbook's 1st-of-month
  key). E.g. statement generated 17-Jun → the **June** row. The actual bank payment happens
  later (~7-Jul).
- The card column holds the statement total as a **provisional** figure, shown in the
  **unverified (yellow)** state until reconciliation confirms it.
- **Card amount states (visual) — three states:**
  - **Unverified (yellow):** a fresh card amount taken straight from a statement, not yet confirmed
    against the bank payment.
  - **Verified (green):** confirmed against the corresponding bank debit and therefore trusted.
  - **Revised (amber):** a value that was **previously verified (green)** and has now been
    **overwritten by a newer/re-processed statement**. Amber does **not** mean untrusted — it means
    *a trusted value changed*, flagging it for human attention. Reconciliation treats amber like
    yellow (eligible) and turns it green again once confirmed.

  A fresh amount starts **yellow**; reconciliation turns it **green**; re-processing a month that
  was already green turns it **amber**.
- **A newer statement is authoritative for its card/month:** processing a card statement always
  (re)writes that month's card cell to the statement's `Total Amount Due` — **regardless of whether
  the amount actually changes**, and **even if the cell was previously green**. If it was green, the
  rewrite lands as **amber** (revised); otherwise as **yellow**. "Verified" means *confirmed against
  the bank debit*, not *frozen forever*; a re-issued/corrected statement re-opens the cell for
  re-reconciliation.
- **Reconciliation:** using the **mandate** mapping plus each card's **payment-identification
  pattern**, each credit-card-payment debit in the paying bank's statement is matched to its
  specific card (the **same matching used to write the `System Note`** at processing time, so a
  payment's card is resolved by one shared rule) and compared to that card's **immediately prior
  month** column (reconciliation looks only one month back — it does not search further). If they differ
  (refund/adjustment), the **prior month's system-owned card cell is updated** to the actual amount; the
  verified cell is marked **green** so the human can trust it. **Reconciliation operates only on
  unverified (yellow) or revised (amber) card cells; verified (green) cells are ignored. A newer
  statement rewrites the cell in yellow/amber (see above), which makes it eligible for
  reconciliation again.** (User may note the delta in `Comments` manually.)
- If a `cc-payment` debit matches **more than one** card's payment-identification pattern,
  reconciliation **aborts** (fail-loud) rather than guessing. A debit matching **zero** cards is
  **logged and skipped** (e.g. an intentionally-ignored card), not an error.
- **Exactly one payment debit is expected per card/month.** If **two or more** debits match the
  **same card for the same prior month** (double payment, reversal, corrected debit, or a duplicate
  statement), reconciliation **aborts** (fail-loud) rather than guess which is authoritative.
- If the referenced **prior-month row does not exist** (e.g. the first card statement ever, whose
  payment lands before any prior row was created), reconciliation for that payment is **skipped and
  logged** — there is nothing to correct.
- That card-payment debit in the bank statement is bucketed as `Credits/Transfers`, so card
  spend is never double-counted (real spend lives in the card column).
- **Assumption — bills paid in full:** card bills are always paid in full via mandate (never
  partial / minimum due). Therefore any mismatch between the bank's card-payment debit and the
  provisional card total can only be a **refund/adjustment**, so correcting the card column
  **down to the actual debited amount** is always the right behaviour.

## Run workflow & verification status

Input is the **last stable Excel workbook**. The process operates on a **copy** in the working
directory; the human reviews, then copies the finalized workbook back to the main folder.

A single **workbook-level verification status** governs the lifecycle. It must be
**human-editable and travel inside the workbook** (since the workbook is copied between folders);
the exact storage location is an implementation detail. Values:
- **`pending`** — a Transactions sheet has been generated, awaiting human review.
- **`complete`** — human has reviewed/corrected the tags.
- **`regenerate`** — human wants a fresh rebuild of the transaction set.

State machine on each run:

**Credit cards vs banks differ:** credit-card totals **bypass the transaction-tag review
workflow** (no per-transaction tagging), so they are written **directly to the main matrix**
(billing-date → Month Key) — the human still eyeballs the resulting cells. Bank figures require
human verification of the tags first, so they flow through the Transactions sheet.

1. **No working copy / output present** (first run):
   - Read the stable **input** workbook and write a **separate output** workbook (the working
     copy) in the working directory, preserving all sheets — the input is **never modified**. The
     system reads/writes **only the configured master sheet** plus the sheets it manages (the
     Transactions sheet and the `Control` status sheet); any other user sheets are untouched.
   - Write **credit-card** totals directly into the main matrix (billing-date → month row,
     creating or updating that row as defined under **Upsert**).
   - Add a **Transactions sheet** containing all **bank** transactions with best-guess tags.
   - Set verification status = **`pending`**.
2. **Working copy present, status = `pending`:** leave it untouched — wait for the human. **If the
   status is `pending` but the Transactions sheet is *missing*** (e.g. accidentally deleted), the
   workbook is **inconsistent** and the run **aborts** rather than silently rebuilding — the human
   recovers by setting status = `regenerate` (which is allowed to rebuild from scratch).
3. **Working copy present, status = `complete`:** compute the month row(s) from the reviewed
   tags, **push the figures into the main matrix sheet**, **apply CC reconciliation** to the
   prior month's card column(s) (using the now-verified `cc-payment` debits), **delete the
   Transactions sheet**, and finalize. **After finalization the verification status remains
   `complete`** (it is the no-op guard: status `complete` + no Transactions sheet ⇒ already done).
   The **absence of the Transactions sheet is authoritative** for "already finalized" — the status
   value alone is not relied upon, since it can be hand-edited.
   The human then copies the **output** workbook back over the stable workbook as the new version.
   (Re-running once finalized — Transactions sheet already removed — is a **no-op**.)
4. **Working copy present, status = `regenerate`:** discard the current Transactions sheet,
   re-extract and re-tag from the statements **using the current (updated) configuration**
   (re-applying any `pinned` overrides), and set status back to **`pending`**. Unlike `pending`,
   `regenerate` **tolerates a missing Transactions sheet** — rebuilding it is exactly its job (there
   are simply no pins to recover), so it is the recovery path for an accidentally-deleted sheet.

**Credit-card totals are re-imported whenever the review sheet is generated** — i.e. on a
**first run** and on **`regenerate`** (they are authoritative statement outputs, not part of the
human tag-review workflow). A re-import of a month whose cell was previously **unverified or blank
lands as yellow**; a re-import over a **previously-verified (green)** cell lands as **amber**
(revised) — flagging that an already-trusted value was reprocessed and should be re-verified. This
is how a corrected/re-issued statement flows back through normal reconciliation. `complete` does
**not** re-import cards (it pushes the reviewed bank figures and reconciles). Once a workbook is
**finalized** (Transactions sheet already removed), a re-run is a **no-op regardless of any card
PDFs still sitting in the working directory**.

**Processed-PDF archival (automatic).** On a successful **`complete`** run (the end of a review
cycle), the system **moves the cycle's statement PDFs into `workingDir/processed/<run_id>/`** (a
per-run timestamped subfolder). PDFs are moved **only after the run succeeds** — if it aborts for
any reason, **no PDFs are moved**, so the user can fix the problem and re-run on the same inputs.
Only **unprocessed PDFs in the working directory are active inputs** — this prevents accidental
double-processing and keeps an audit trail. Discovery scans the working directory
**non-recursively**, so the `processed/` archive is never re-scanned. (PDFs
are deliberately **not** moved on first-run/`regenerate`, because `regenerate` still needs them.)
**Archived PDFs are retained indefinitely — the system never automatically deletes them** (cleanup
of old archives, if ever wanted, is a manual human step). The output workbook copy is left in place
for the human to copy back; once finalized, a further `complete` run is a **no-op** (the
Transactions sheet is already gone, and the PDFs are archived).

### Re-processing an existing month (upsert)
Month rows are keyed by the `Month Key`, which **uniquely identifies a matrix row — at most one
row may exist for a given month**. When processing data for a month, the system **updates the
existing row if present, otherwise appends a new one** (never producing a duplicate). Because the
system always works on a **copy** (never master), re-processing is safe rather than something to
prevent. On overwrite, the system rewrites its system-owned cells (a re-written **card** cell
re-enters the **unverified/yellow** state — or **amber** if it was previously verified (green) —
itself the cue that it awaits re-reconciliation; **bank** figures carry no separate highlight). The update touches **only system-owned cells** —
the card totals and each bank's `Bank Debits` / `Credits/Transfers` — and **never** the manual
`Comments` or the formula columns. These system-owned cells are **authoritative outputs —
overwritten regardless of any manual edits** (manual notes belong only in `Comments`); credit-card
columns in particular are rewritten **whenever the review sheet is (re)generated** (first run /
`regenerate`), since only bank-derived figures are gated behind verification. During an upsert,
**only accounts with a statement present in that run are updated**; cells for missing accounts
retain their existing values. If no row exists for that month, a new row is **appended at the
bottom** — the matrix only grows downward and rows are **never re-sorted** (months are processed in
order, so the latest is always last; a rare back-filled month also simply appends). A newly
appended row carries the previous matrix row's **cell formatting** (number formats, borders, fills,
fonts) and the **same live Excel formulas** in every formula-driven column, **adjusted to the new
row's references**, so workbook calculations continue automatically; only the **system-owned input
cells** are then written with the new values. (Conditional formatting is range-based in Excel, so
a new row within the range is covered automatically.)

Card cells carry the **three-state colour** described under reconciliation: **yellow (unverified)**
when freshly written, **green (verified)** once confirmed against the bank debit, and **amber
(revised)** when a newer statement overwrites a previously-green value. Reconciliation never
downgrades a green cell, but a **newer statement** re-opens it (→ amber). There is **no separate
transient overwrite highlight** — bank figures are written with no fill.

### Error handling
The process favours **failing loudly** over guessing. If a statement PDF matches **no** account
pattern or **more than one**, a password fails, or a statement cannot be parsed, the run **logs
the error and aborts** — it never silently skips a file or writes partial data. It **also
aborts if two statements for the same account would double-count**:
- **Bank accounts:** two statements whose **declared start/end periods overlap** (e.g. a duplicate
  or a re-issued statement left beside the original). Overlap is judged on the declared period, not
  on transaction dates.
- **Credit cards:** two statement files that resolve to the **same card and the same billing
  month** (cards have no period). Different billing months are legitimate (several months in one
  run).

This is the run-time, **same-run** guard: if both the original and a corrected statement are
present together, the run **aborts** so the user removes the stale file. (This does **not**
contradict "a newer statement is authoritative" under Card states — that rule is about a corrected
statement processed **alone in a later run**, after the original was already finalized; the card
cell is simply overwritten then. Both files present at once → abort; corrected file alone → use it.)
The user fixes the input/config and re-runs.

**Parse validation (correctness guarantee).** A bank statement is **accepted only if the extracted
transactions reconcile with the statement's own printed totals** — `opening + Σcredits − Σdebits =
closing`. If a statement prints opening/closing balances and they **do not** reconcile, extraction
dropped or added rows: this is a **parse error and aborts the run** (never partial/incorrect data).
A statement that prints **no** balances cannot be checked this way and is not failed on this basis.

**Matrix integrity (duplicate month rows).** The matrix must hold **at most one row per `Month
Key`**. At **startup**, the system scans the matrix; if any month appears in **more than one** row
(only possible from manual editing / a corrupted workbook — the system itself never creates
duplicates), the run **aborts** rather than guess which row to update.

**Statements are assumed complete for the issuer's normal period.** A bank statement is taken to
cover one whole statement period; partial exports or fragments (e.g. `01-Apr–15-Apr`) are not
supported. A bank statement whose declared period cannot be determined is a parse failure and
**aborts**. (Credit-card statements have no declared period — only a billing date — so this
applies to bank statements.)

A **missing statement is not an error** (issuers often omit a statement when there is no
activity). It is treated as **no update**: existing values for that account/month are left
**untouched**, and a brand-new row's **raw-input cell is left blank** (the formula columns simply
compute from blank inputs). This applies to both bank accounts and credit cards. **An account may
exist in configuration even if no statement has ever been processed for it** (e.g. a declared bank
whose statements haven't been dropped yet); its matrix columns simply **remain blank until data is
available**.

The matrix is the source of truth; the Transactions sheet is transient (not a ledger).
