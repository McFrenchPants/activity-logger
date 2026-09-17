# Progress — Domain services

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — signed off by owner 2026-09-17.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/domain-services`, off `main` at 2f6bb82.

This work item is **sdlc-tracked** (`DS1` in `.sdlc/state.json`). Verification tier: spec §7 (verifier for every non-doc task).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| DS1.1 | Domain contracts, name rules, test support | todo | |
| DS1.2 | Temporal resolver | todo | After DS1.1 |
| DS1.3 | Candidate selector and interpretation validator | todo | After DS1.1, DS1.2 |
| DS1.4 | ActivityRepository implementation in core-data | todo | After DS1.1 |
| DS1.5 | Orchestrator, correction service, review resolution | todo | After DS1.1-DS1.4 |
| DS1.6 | Documentation | todo | After DS1.5 |

## Open items (carry forward)

- Hide/restore of an occurrence (Undo, "Remove from history") has no
  data-layer operation and no audit columns — Step 7 decision (spec §6).

## Session log

### 2026-09-17 — design signed off with temporal changes; plan written

Owner approved the spec with two changes, folded into §5.1: a weekday always
means the most recent previous such day (so "Saturday" said on Saturday is 7
days earlier; the earlier "unresolvable" rule is gone), and "N days/weeks
ago" (digits or words, "a", "a couple of") resolves to DATE_ONLY start of
that day. "A few days ago" and "a month ago" stay unresolvable (orchestrator
call: no honest number). Plan: six tasks; tasks added to state.json with
`lifecycle_state: null` until packeted (DB1 convention). validate-state still
reports only the pre-existing missing SS1/DB1 evidence files.

### 2026-09-17 — design spec drafted; stopped for sign-off

Orient: nothing in flight (SS1, DB1 released; no unmerged branches; clean
tree). Backlog had no ready item: item 4 (AI slice) depends on handoff Step 3,
which was never a backlog item; items 6 and 7 are blocked. Owner chose Step 3
(domain services) over the AI slice and the watch check; added as backlog
item 8.

Owner product decision: no "accept with review marker" tier — anything short
of confident goes to Needs review, nothing logged (spec §5.2). This
contradicts AI_INTERPRETATION_SPEC §11's middle tier; that doc gets updated in
the docs task.

Orchestrator technical calls (spec §5): temporal rules table with part-of-day
bands, bare same-day weekday is unresolvable, numeric dates unresolvable;
review resolution inserts a user-authored interpretation (distinct from DB1
§5.5's rule for corrections); hidden occurrences cannot be corrected (closes
DB1 carry-forward); undo/hide deferred to Step 7; English only.

Sized "significant" (seven components, AI-output validation widen trigger).
Next: on sign-off, write IMPLEMENTATION_PLAN.md and add DS1 to state.json.
