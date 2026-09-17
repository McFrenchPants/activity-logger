# Progress — Domain services

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — **awaiting owner sign-off**.
Implementation plan: not yet written (written after sign-off).
Branch: `feature/domain-services`, off `main` at 2f6bb82.

Work item `DS1`. Will be **sdlc-tracked** in `.sdlc/state.json` once the plan
exists. Verification tier: spec §7 (verifier for every non-doc task).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| — | Implementation plan | todo | Blocked on design-spec sign-off |

## Open items (carry forward)

- Hide/restore of an occurrence (Undo, "Remove from history") has no
  data-layer operation and no audit columns — Step 7 decision (spec §6).

## Session log

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
