# Sample workbook — `expenses-template.xlsx`

A tiny, **unencrypted, `Expenses`-only** workbook with **placeholder data** that documents the
matrix layout this tool reads and writes. It is **not** a real statement export — the numbers and
labels are made up. (Regenerate it with
`mvn test -Dtest=SampleWorkbookGenerator -DgenSample=true`, which builds it through the real
`WorkbookService`, so it always matches the column-discovery code.)

## What the layout must look like

The system finds columns **by header name**, not by fixed position, across three header rows:

| Sheet row | Holds |
|---|---|
| **row 1** | bank **group labels**, one over each triplet's first column (e.g. `Bank 1`, `Bank 2`) |
| **row 2** | `Date`, one column per **card label**, `Comments`, then per bank a **`Bank Debits` / `Credits/Transfers` / `Net Expenses`** triplet, then `Net Bank Expenses`, `Total Expenses`, `CC Expense`, `Median Expense`, `Year`, `Average Expense` |
| **row 3+** | one **data row per month**, keyed by the `Date` cell = first of the month (`01-Jan-2026`) |

What the system writes vs. leaves alone:
- **Writes (system-owned):** the card totals and each bank's `Bank Debits` / `Credits/Transfers`,
  plus the card-cell colour (🟡 unverified → 🟢 verified → 🟠 revised).
- **Leaves alone:** the derived columns (`Net Expenses`, `Net Bank Expenses`, `CC Expense`,
  `Total Expenses`, `Median`/`Average`, `Year`) stay as **live Excel formulas**; `Comments` is
  yours. In the sample the card cells are 🟡 yellow (a fresh import, not yet reconciled).

The template uses 2 cards + 2 bank triplets for clarity; real setups just add more columns of the
same shape. Concrete account names live in your `config.yaml` / `config.local.yaml`, never hard-coded.

## Turning it into your real workbook

1. Open the template, adjust the card/bank columns to your accounts, keep the header names exact.
2. **Save it password-protected** (Excel → Save with a password), and store that password in the
   macOS Keychain under service `expense-tracker`, account `MASTER`.
3. Point `inputWorkbook` / `outputWorkbook` / `masterSheet` in your `config.local.yaml` at it.

The tool only ever reads/writes the master sheet (here `Expenses`) plus the `Transactions` and
`Control` sheets it manages; any other sheets in your workbook are left untouched.
