# Design spec — Domain services (backlog item 8)

Status: **approved by owner** (2026-09-17), with two temporal changes folded
into §5.1: a weekday always means the most recent *previous* such day, and
"N days/weeks ago" resolves to a date.
Work item `DS1`. Branch: `feature/domain-services`, off `main` at 2f6bb82.
Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 3 — Domain services",
`docs/ARCHITECTURE.md` §5-6, §12, §15-16, `docs/AI_INTERPRETATION_SPEC.md`
§4, §7-11, `docs/DOMAIN_MODEL.md`, `docs/DATA_MODEL.md`, `docs/UX_SPEC.md`
§4.2, `docs/UX_VISUAL_SPEC.md` §4.1, `docs/TEST_STRATEGY.md` §5-6,
ADR-008/010/017/018, carry-forward items in
`docs/proposals/room-schema/PROGRESS.md`.

Audience: agents. The owner-facing summary is given in chat, not here.

## 1. Goal

Give Step 4 (AI vertical slice) everything it needs except the Gemini Nano
adapter itself: the deterministic logic that sits between a model result and
the database, and the narrow interfaces that connect it to `core-ai` and
`core-data`. After this work, Step 4 should be "implement `ActivityInterpreter`
in `core-ai`, wire the orchestrator, run on the Pixel 10 Pro" — not "also
invent validation, time resolution and persistence policy on the way".

## 2. Non-goals

- Any ML Kit / Gemini Nano code, prompt text, or on-device run (Step 4).
- Speech, UI, ViewModels, Wear transport, query interpretation (Steps 6-9).
- Undo / "Remove from history" (hiding an occurrence). It has no data-layer
  operation and the `corrections` table has no visibility columns, so auditing
  it is a schema decision; deferred to Step 7 and recorded as an open item.
- Alias promotion policy (turning repeated phrases into durable aliases).
  Aliases are *read* by the candidate selector; nothing here writes them.
- Lexical/recency candidate pre-selection beyond what §3 R3 requires, and
  embeddings (ADR-015).
- Seed catalog / semantic corpus automation (Step 5). Unit-test fixtures for
  the cases named in TEST_STRATEGY §5-6 are in scope; the corpus runner is not.
- Canonical activity merges.
- Languages other than English (§5.7).

## 3. Requirements

**R1 — Domain types for the interpretation boundary, in `core-domain`.** A
model-agnostic `InterpretationCandidate` (operation, resolution, matched
activity id, proposed name, state, temporal expression, confidence band) and
`InterpretationInput` (raw text, captured instant, capture zone, candidate
activities). `core-ai` will map ML Kit output into these; nothing in
`core-domain` knows about ML Kit (ADR-023, ARCHITECTURE §13).

**R2 — `ActivityInterpreter` interface in `core-domain`** matching
ARCHITECTURE §5, returning either a candidate or a typed failure
(unavailable / retryable / malformed / other). No implementation here beyond
test fakes.

**R3 — Candidate selector.** Deterministic, pure. Given the catalog (active
activities plus their aliases) and a bound, returns a bounded, stably ordered
candidate list and a candidate-context hash (AI_INTERPRETATION_SPEC §12).
MVP policy (ARCHITECTURE §16): all active activities when the catalog fits the
bound. Above the bound: activities with an exact normalized alias/name token
hit in the raw text first, then by most recent occurrence, truncated. Merged
and archived activities are never candidates. The hash depends only on the
ordered candidate ids and names, so the same catalog yields the same hash.

**R4 — Name normalization**, one function, used by the selector and by the
new-activity check (§5.4): trim, collapse whitespace, lowercase (locale-root),
strip leading/trailing punctuation. `canonical_activities.normalized_name` and
`activity_aliases.normalized_alias` are produced by this same function.

**R5 — Temporal resolver.** Deterministic, pure, no model involvement
(ARCHITECTURE §15, ADR-018). Input: temporal expression (nullable), captured
instant, capture zone id. Output is exactly one of:
- `Resolved(occurredAt, precision)`
- `Future` — the phrase refers to a time after capture
- `Unresolvable` — phrase present but not safely resolvable

Behaviour is fixed by §5.1 and must be covered case-by-case by unit tests
including every TEST_STRATEGY §5 case under its fixed context
(`2026-09-15T20:00-04:00`, `America/Detroit`), plus DST-transition days and a
capture just after local midnight.

**R6 — Interpretation validator.** Deterministic, pure. Input: the candidate,
the candidates actually supplied, the temporal result, capture metadata
(speech confidence if present). Output: a decision of exactly one of
`AUTO_ACCEPT`, `NEEDS_REVIEW`, `REJECT`, each with machine-readable reason
codes (never raw user text), plus the `ValidationStatus` to persist.
Rules are §5.2. **There is no "accept with review marker" tier** (owner
decision 2026-09-17, §5.2).

**R7 — Capture interpretation orchestrator** (`core-domain`, pure policy
around injected interfaces): given a stored raw capture, run selector →
interpreter → temporal resolver → validator, then call the repository to
record the outcome:
- `AUTO_ACCEPT` → accept the interpretation (one transaction, R8).
- `NEEDS_REVIEW` → store the interpretation with `NEEDS_REVIEW`, set the
  capture to `NEEDS_REVIEW`, create no occurrence and no activity.
- `REJECT` (malformed / invalid id / unsupported) → store the interpretation
  with `INVALID` where one exists, set the capture to `NEEDS_REVIEW`
  (the words are saved, nothing guessed).
- Interpreter unavailable / retryable → no interpretation row, capture to
  `FAILED_RETRYABLE` (AI_INTERPRETATION_SPEC §17).
Running it again on a capture that already has an occurrence changes nothing
(builds on the data layer's per-capture idempotency).

**R8 — `ActivityRepository` interface in `core-domain`, implemented in
`core-data`**, exposing only what R7, R9 and R10 call: read the candidate
catalog, record a review/rejected/failed outcome, accept an interpretation,
resolve a capture by user choice, apply a correction, read an occurrence.
It is the only public door into `core-data`'s writer; DAOs stay internal.
Suspend functions; the implementation moves blocking DAO work off the caller's
thread.

**R9 — Correction service.** Validates and applies a user correction of an
occurrence's activity, occurred time/precision, or state through the existing
transactional `applyCorrection`. Refuses: unknown occurrence, HIDDEN occurrence
(§5.5), a target activity that is not ACTIVE, an occurred time after the
correction time, a no-op (reported as "nothing changed", not an error).
Creating a new activity as part of a correction goes through the same
new-activity check as §5.4.

**R10 — Review resolution** ("Which activity was this?", UX_VISUAL_SPEC §4.1).
For a capture with no occurrence (NEEDS_REVIEW, or never interpreted because
AI was unavailable), the user chooses an existing activity or names a new one
and may adjust the time; this creates the occurrence in one transaction (§5.3).

**R11 — Tests run on the host** as part of `./gradlew test`. Domain logic is
unit-tested in `core-domain` with fakes; the `core-data` repository
implementation is tested against a real in-memory Room database with the
existing tooling. Required coverage beyond R5: every validator rule in §5.2
with at least one pass and one fail case; TEST_STRATEGY §6 ambiguity cases
reach `NEEDS_REVIEW` given a candidate that marks them AMBIGUOUS/UNRESOLVED;
the "edged the lawn" false-merge case (candidate claims EXISTING Mow lawn with
LOW confidence → `NEEDS_REVIEW`); orchestrator paths for all four outcomes
including rerun idempotency; raw text byte-identical after every new write
path (extends the DB1 immutability test).

**R12 — Documentation**: `ARCHITECTURE.md` §5 updated to the real interface
names; AI_INTERPRETATION_SPEC §11 updated to the two-outcome policy; an ADR for
the confidence policy decision and one for the temporal resolution rules;
`PROJECT_STATUS.md` roadmap item 7; `DATA_MODEL.md` if §5.3 adds a data-layer
operation (no schema change is expected).

## 4. Constraints

- `core-domain` stays pure Kotlin/JVM: `java.time` and the Kotlin stdlib only.
  No Android, Room, ML Kit or coroutines-library types. (`suspend` in
  interfaces needs no library.) If orchestrator tests need
  `kotlinx-coroutines-test`, it is a test-only dependency, verified to resolve
  on Kotlin 2.3.21 and pinned in the catalog.
- No new schema version. If an operation truly needs a column, stop and
  escalate instead of migrating.
- No logging of raw text, expressions or names anywhere (AGENTS.md §11).
  Reason codes are enums.
- All DB1 invariants stay: raw text write-once, interpretations append-only,
  corrections the only mutation path for occurrences, accept is one
  transaction and idempotent per capture.
- Clock and zone are injected; no `Instant.now()` / `ZoneId.systemDefault()`
  inside domain logic.

## 5. Decisions taken for this spec

### 5.1 Temporal rules (orchestrator, technical — recorded as an ADR)

All arithmetic in the capture zone. `now` = captured instant.
Resolved times are never after `now`; a phrase whose resolution would be after
`now` is `Future`.

| Phrase family | Result | Precision |
|---|---|---|
| null / blank expression | `now` | INFERRED_NOW |
| "just now", "just", "now", "a moment ago", "just finished" | `now` | INFERRED_NOW |
| "today" | start of today | DATE_ONLY |
| "yesterday" | start of yesterday | DATE_ONLY |
| "this morning" / "this afternoon" / "this evening" / "tonight" | today at part-of-day anchor | APPROXIMATE |
| "earlier today" | midpoint between start of today and `now` | APPROXIMATE |
| "last night" | yesterday 21:00 | APPROXIMATE |
| "yesterday morning/afternoon/evening" | yesterday at anchor | APPROXIMATE |
| "N minutes/hours ago", "an hour ago", "a couple of hours ago", "about/around …" | `now` − duration | APPROXIMATE |
| weekday ("Saturday", "on Saturday", "last Saturday") | start of the most recent such day **before today** — said on a Saturday, "Saturday" means 7 days earlier (owner decision 2026-09-17) | DATE_ONLY |
| "N days ago", "N weeks ago", "a day ago", "a week ago", "a couple of days/weeks ago", optional "about/around" (N as digits or English words one-twelve) | start of the calendar day N days (or 7×N days) before today | DATE_ONLY |
| month-name date ("September 1st", "Sept 1", "1 September") | start of that date in the most recent year for which it is not after today | DATE_ONLY |
| explicit clock time today ("at 3pm", "at 3 this afternoon") | today at that time | EXACT |
| "tomorrow", "next …", "later", "in N …" | `Future` | — |
| anything else ("last week", "a few days ago", "a month ago", "recently", "the other day", numeric dates like 9/1) | `Unresolvable` | — |

Bands and anchors (anchors sit inside each band so a display layer can recover
the band from the local hour): morning 05:00-12:00 anchor 09:00, afternoon
12:00-17:00 anchor 15:00, evening 17:00-21:00 anchor 19:00, tonight
17:00-24:00 anchor 21:00 (overlaps evening on purpose: "tonight" said at
20:00 means earlier this evening, not the future). If `now` is inside the band and before the anchor,
the result is `now` (still APPROXIMATE) — "this morning" said at 08:30 is not
`Future`. Only a band that starts after `now` is `Future`.
"Start of day" uses `LocalDate.atStartOfDay(zone)`, which handles DST gaps.
DATE_ONLY today is start of today even if that is earlier than `now` — the
precision field, not the instant, tells the UI what to show.
Matching is case-insensitive over the normalized expression and tolerant of a
leading "on"/"at"/"about"/"around". Numeric dates are unresolvable because
their day/month order is locale-dependent.

### 5.2 Confidence policy — two outcomes (owner decision 2026-09-17)

The owner chose "ask me" over "save and mark it": anything short of confident
goes to Needs review and nothing is logged until the user picks. This removes
AI_INTERPRETATION_SPEC §11's "auto-accept with review marker" tier.

`REJECT` when any of: operation is not LOG_ACTIVITY (QUERY_HISTORY is routed
elsewhere in Step 9 and is not an error, but is not logged); EXISTING_ACTIVITY
with null id or an id not in the supplied candidates; NEW_ACTIVITY with a null
or blank proposed name, or with a non-null matched id; AMBIGUOUS/UNRESOLVED
with a matched id or proposed name present; state missing for a LOG_ACTIVITY.

`NEEDS_REVIEW` when not rejected and any of: resolution AMBIGUOUS or
UNRESOLVED; confidence band not HIGH (including missing); temporal result
`Future` or `Unresolvable`; NEW_ACTIVITY whose proposed name fails the naming
check (§5.4) or normalizes to an existing active activity's name or alias
(the model should have matched it — ADR-017, treat as unsafe rather than
silently linking or duplicating); speech confidence present and below 0.5.

`AUTO_ACCEPT` otherwise: valid structure, HIGH confidence, resolved time,
EXISTING_ACTIVITY with a supplied id, or NEW_ACTIVITY with an acceptable,
non-duplicate name.

The 0.5 speech threshold is a starting constant in one place, to be revisited
with real recognizer output in Step 6.

### 5.3 Review resolution records a user-authored interpretation

An occurrence must reference an interpretation (`effective_interpretation_id`
is NOT NULL), and a capture waiting for review may have no usable one (AI
unavailable, or an INVALID/UNRESOLVED result). Resolving it therefore inserts
an interpretation row describing the user's choice, with
`interpreter_version = "user-resolution"`, `prompt_version = "none"`,
`validation_status = VALID`, and then accepts it through the existing
transactional path. This is honest provenance — the row says a person, not a
model, decided — and differs from DB1 §5.5, which concerns *corrections to an
existing occurrence* and still never fabricates interpretations. Any earlier
model interpretation of the capture stays as it was. If the existing
`acceptInterpretation` request shape already expresses this, no data-layer
change is needed; otherwise the change is additive to `LedgerRequests`/writer,
with no schema change.

### 5.4 New-activity name check

A proposed or user-typed new name must: be 1-60 characters after trimming;
contain no digits-only or date/time words ("today", "yesterday", weekday
names, month names); not start with "I " / "I've " / "we "; and normalize to
something non-empty. This catches the "Bad" examples in
AI_INTERPRETATION_SPEC §9 cheaply. It is a guard, not a style enforcer — a
model name failing it goes to Needs review; a user-typed name failing it is
returned to the UI as a validation error.

### 5.5 Hidden occurrences cannot be corrected

Closes DB1's carry-forward item. A hidden occurrence has been removed from
history by the user; correcting it silently would edit something they can't
see. If a "restore" action is designed in Step 7, correction becomes possible
again after restore.

### 5.6 Where things live

- `core-domain`: domain types, `ActivityInterpreter`, `ActivityRepository`,
  `Clock`-like time source, name normalizer, candidate selector, temporal
  resolver, validator, orchestrator, correction service, review resolution —
  all pure.
- `core-data`: the `ActivityRepository` implementation over
  `ActivityLedgerWriter` and DAOs, plus the read queries it needs.
- `core-testing`: shared fakes (`FakeActivityInterpreter`, fixed clock,
  in-memory repository) consumed by tests in other modules; replaces
  `ScaffoldFixtures`.

### 5.7 English only for MVP

Temporal phrases and the name check are English. The resolver is structured as
a table of phrase rules so another language is an addition, not a rewrite.

## 6. Open questions

None for the owner. For the plan's first task to settle by running code:
whether orchestrator tests need `kotlinx-coroutines-test` or can drive
`suspend` functions with a plain `runBlocking`-free helper, and the version
that resolves on Kotlin 2.3.21 if needed.

Open item carried to Step 7: hiding/restoring an occurrence needs a
data-layer operation and an audit decision (corrections table has no
visibility columns).

## 7. Verification tier

Validator, orchestrator and review-resolution tasks touch
`ai_output_validation_and_persistence` (project widen list); the repository
implementation touches `data_persistence_migrations` (floor) and
`raw_capture_immutability`. **Every task except pure documentation goes to the
verifier agent**; documentation tasks get an orchestrator spot-check.
