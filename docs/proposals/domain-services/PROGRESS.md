# Progress — Domain services

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — signed off by owner 2026-09-17.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/domain-services`, off `main` at 2f6bb82.

This work item is **sdlc-tracked** (`DS1` in `.sdlc/state.json`). Verification tier: spec §7 (verifier for every non-doc task).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| DS1.1 | Domain contracts, name rules, test support | done | Verifier pass. Repo contract gaps carried into DS1.4/DS1.5 packets |
| DS1.2 | Temporal resolver | done | Verifier pass; orchestrator fixed hedged clock precision, DST-gap clock times, punctuation-only input |
| DS1.3 | Candidate selector and interpretation validator | done | Verifier pass; orchestrator hardened speech-confidence range, escaped hash separators |
| DS1.4 | ActivityRepository implementation in core-data | done | Verifier pass; guard-test bite re-checked by orchestrator |
| DS1.5 | Orchestrator, correction service, review resolution | done | Verifier pass on attempt 2; attempt 1 failed on an orchestrator packet error (invented matched id vs FK) |
| DS1.6 | Documentation | todo | After DS1.5 |

## Open items (carry forward)

- Hide/restore of an occurrence (Undo, "Remove from history") has no
  data-layer operation and no audit columns — Step 7 decision (spec §6).
- Re-interpretation of an already-accepted capture (new interpretation becomes
  effective, CorrectionSource.REINTERPRETATION) has no path in the
  `ActivityRepository` contract (`CorrectionChanges` has no interpretation
  field). Not needed by DS1 or Step 4; add when a repair/rerun feature exists.
- Listing captures waiting for review is not in the repository contract —
  Step 7 (History "Needs review" filter).

## Session log

### 2026-09-17 — DS1.4 done

`RoomActivityRepository` (internal) + public `createActivityRepository(context,
clock)` via `ActivityLedgerDatabaseFactory.create`. LedgerWriteDao gained
`recordOutcome` (state update then interpretation insert, one transaction;
refuses captures with an occurrence and states other than
NEEDS_REVIEW/FAILED_RETRYABLE/FAILED_FINAL) and
`applyCorrectionCreatingActivity`; accept/correct now refuse a target activity
that is not ACTIVE. Read queries: active activities, aliases of active
activities, one grouped last-ACTIVE-occurrence query, occurrence by capture.
kotlinx-coroutines-core 1.8.1 declared explicitly (same version as the
transitive one). 29 new tests; no schema change. Verifier pass; its one
uncertain item (guard test still bites) was re-run by the orchestrator: a
planted @Update failed `daoWriteSurfaceIsRestricted`, revert passed.
Known seams, accepted: a missing target activity is a SQLiteConstraintException
at writer level and becomes IllegalArgumentException only in the repository
(translation matches "FOREIGN KEY" in the message — unverified on a real device
but same SQLite wording); applyCorrection timestamps use the caller's `now`,
other repository writes use the injected clock (documented in the domain
KDoc); recordOutcome does not check an interpretation's matched activity is
ACTIVE (INVALID results are the expected input there).

### 2026-09-17 — DS1.3 done

CandidateSelector (`candidates`, bound 40, exact whole-token alias/name hits
then recency, final list name-ordered, SHA-256 context hash) and
InterpretationValidator (`validation`, 18 persisted reason codes, REJECT >
NEEDS_REVIEW > AUTO_ACCEPT; no review-marker tier). 44 tests. Verifier pass;
orchestrator fixes: speech confidence outside 0..1 (or NaN) counts as low and
the threshold must be in 0..1; hash separators written as ``/``
escapes instead of invisible literals (hash value unchanged, test proves it).
For DS1.5: the validator trusts the supplied shortlist, so a candidate id
that went stale between selection and acceptance is caught only by the
repository/foreign key — the orchestrator should re-check the target is
ACTIVE or rely on accept failing loudly.

### 2026-09-17 — DS1.2 done

TemporalResolver in core-domain `temporal` package: ordered whole-match phrase
rules per spec §5.1, final guard against results after capture, 25 tests
(DST in Detroit and Santiago's midnight gap, just-after-midnight, invariant
sweep). Before implementation the orchestrator widened the "tonight" band to
start at 17:00 ("tonight" said at 20:00 was Future). Verifier pass with
findings; orchestrator fixed inline and recorded in spec §5.1: hedged clock
times ("about 3pm") are APPROXIMATE, clock times inside a DST gap are
Unresolvable, punctuation-only input is Unresolvable. Left as is (lenient,
harmless): mismatched plurals ("1 days ago"), "a couple hours ago", "in a
while" → Future, "12 this morning" → 00:00.

### 2026-09-17 — DS1.1 done

Domain contracts in core-domain (`interpretation`, `repository`, `naming`
packages), NameNormalizer + NewActivityNameCheck, core-testing helpers
(MutableClock, FakeActivityInterpreter, runSuspend without a coroutines
library). core-domain → core-testing test dependency works. Verifier pass with
non-blocking findings, resolved as orchestrator decisions for later packets:
the core-data implementation takes an injected `Clock` for row timestamps
(accept/record/create have no `now` parameter); unknown ids throw
IllegalArgumentException, documented in the interface KDoc by DS1.4; review
resolution builds a fresh user-resolution record whose matched id / proposed
name agree with the target (DS1.5). Name check deliberately rejects month and
part-of-day words ("May", "Morning walk").

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
