# Personal Expense Tracker

A personal, **local-first** tool that reads password-protected bank & credit-card statements
and maintains a wide **monthly expense matrix** in a local Excel workbook. Privacy is a hard
constraint: everything runs locally, classification is rules-based, no cloud is used at all,
and nothing goes to any third-party/AI processor.

## Status

**Implemented** — end-to-end pipeline runs on real encrypted statements and writes the
password-protected output workbook, with the full pending/complete/regenerate review loop and
pinned one-off overrides. 133 unit tests. See [README.md](README.md) for how to run.
(Open items: a packaged jar — see README / architecture.)

## Docs

- @docs/business-requirements.md — the full business logic (the monthly matrix, bucketing
  rules, credit-card reconciliation, review workflow). **This is the spec; keep it current.**
- [docs/architecture.md](docs/architecture.md) — implementation/design decisions (runtime,
  PDF extraction, Excel I/O, components, parser design, testing strategy).

_(Future: `docs/decisions.md` if a running decision log becomes useful.)_

## Working agreement

- **Never change the requirements (`docs/business-requirements.md`) without explicit
  confirmation from the user first.** Propose the change, wait for an explicit "yes", then edit.

## Working notes

- Keep `CLAUDE.md` lean — an index/map. Put detailed specs in `docs/` and reference them here.
- Separate concerns by stability: business rules (change rarely) vs implementation notes (churn).
