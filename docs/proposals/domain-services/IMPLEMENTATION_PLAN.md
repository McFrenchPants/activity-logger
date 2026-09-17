# Implementation plan — Domain services (backlog item 8)

Work item `DS1` (sdlc-tracked). Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md),
signed off by the owner 2026-09-17. Branch `feature/domain-services`.

Audience: agents. R-numbers and §-numbers refer to the design spec.

## Standing constraints for every task

- Kotlin 2.3.21 / KSP 2.3.12 / Room 2.8.5 unchanged (ADR-022). Any new library
  is verified to resolve and run before it enters `gradle/libs.versions.toml`,
  pinned there; no version literals in module build files.
- `core-domain` and `core-testing` stay pure Kotlin/JVM: stdlib + `java.time`
  only in `core-domain` main. No Android, Room, ML Kit, or coroutines library
  in `core-domain` main.
- Time comes from an injected `java.time.Clock`; zones are passed explicitly.
  No `Instant.now()`, `System.currentTimeMillis()` or `ZoneId.systemDefault()`
  in domain logic.
- No logging. Exception messages and reason codes carry ids and enum names,
  never raw text, temporal expressions or activity names.
- No schema change: `core-data/schemas` stays byte-identical, DB version 1.
- DB1 invariants hold: raw text write-once, interpretations append-only,
  occurrences change only via transactional accept/correct, accept idempotent
  per capture. `DaoWriteSurfaceGuardTest` is extended, never weakened.
- Routine gate `./gradlew test`, host-only. Never `@Ignore` or weaken a test.

## Tasks

### DS1.1 — Domain contracts, name rules, test support

**Scope:** `core-domain/src`, `core-testing` (build file + src).

Settles spec §6 by running code.

1. Domain types (package `...core.domain.*`, public): interpretation candidate
   (operation, resolution, matched id, proposed name, state, temporal
   expression, confidence band — all enum-typed where an enum exists);
   interpretation input (raw text, captured `Instant`, `ZoneId`, candidate
   list); candidate activity (id, display name, alias texts); catalog entry
   (id, display name, normalized name, normalized aliases, last ACTIVE-visible
   occurredAt nullable); stored-capture read model (id, raw text, captured
   instant, zone, speech confidence, processing state, has-occurrence flag);
   occurrence and activity read models; interpreter provenance
   (interpreter version, prompt version, schema version).
2. `ActivityInterpreter` (R2): `val provenance`, `suspend fun interpret(input)`
   returning a sealed result: success(candidate, structured JSON text?) or
   failure(kind ∈ UNAVAILABLE, RETRYABLE, MALFORMED, OTHER, structured JSON?).
3. `ActivityRepository` (R8) — the full contract DS1.4 implements and DS1.5
   consumes. Suspend functions:
   create raw capture → id; get stored capture; load catalog (ACTIVE only);
   record a non-accepting outcome (optional interpretation record + new
   processing state, atomic); accept interpretation (capture id,
   interpretation record, target = existing activity id | new display name,
   occurredAt, precision, state) → occurrence id; apply correction
   (occurrence id, requested changes where the activity may be an existing id
   or a new display name, source, reason, now) → correction id or "nothing
   changed"; get occurrence; get activity. The interpretation record is a
   domain type mirroring `interpretations` columns except id/raw capture id,
   with `Instant` times.
4. `NameNormalizer` (R4) and `NewActivityNameCheck` (§5.4) returning a
   reason enum or OK.
5. `core-testing`: depend on `core-domain`; replace `ScaffoldFixtures` with a
   mutable fixed `Clock`, a scriptable `FakeActivityInterpreter`, and a
   `runSuspend {}` helper that runs a suspend block to completion without a
   coroutines library (`startCoroutine` + completion capture; fails if the
   block actually suspends). Wire `testImplementation(project(":core-testing"))`
   into `core-domain` and prove the dependency direction works. If Gradle
   refuses it, keep the helpers in `core-domain`'s test source set instead and
   report why.

**Acceptance criteria:**
1. Types and both interfaces compile in `core-domain`; `core-domain` build
   plugins/deps unchanged except the `core-testing` test dependency.
2. Normalizer tests: whitespace collapse, case, leading/trailing punctuation,
   internal punctuation preserved, idempotent (normalize twice = once).
3. Name check tests: every "Bad" example of AI_INTERPRETATION_SPEC §9 fails
   with a specific reason; every "Good" example passes; length bounds 1/60/61.
4. `runSuspend` test proves it returns a value, propagates an exception, and
   fails loudly on a genuinely suspending block.
5. No new catalog entry (or, if one proved necessary, pinned and justified).
   `./gradlew test` passes.

### DS1.2 — Temporal resolver

**Scope:** `core-domain/src`. Depends on DS1.1.

Implements R5 exactly per §5.1's table (paste the table into the packet).
Structured as an ordered list of phrase rules (§5.7).

**Acceptance criteria:**
1. One test per table row, plus every TEST_STRATEGY §5 case under context
   `2026-09-15T20:00-04:00` / `America/Detroit` with expected instant and
   precision asserted exactly.
2. Weekday: each of the seven weekday names from a Tuesday capture; "Tuesday"
   said on a Tuesday resolves 7 days earlier; "last Saturday" == "Saturday".
3. "N days/weeks ago": digits and word forms one-twelve, "a"/"a couple of",
   with and without about/around; "a few days ago" and "a month ago" are
   Unresolvable.
4. Part-of-day bands: "this morning" at 08:30 → now, APPROXIMATE; at 13:00 →
   09:00; "this evening" at 16:00 → Future; "tonight" at 23:00 → 21:00.
5. Future phrases → Future; unsupported phrases incl. "9/1", "last week",
   "the other day" → Unresolvable; no result ever after capture instant
   (property-style loop over all rules × several capture times).
6. DST: America/Detroit spring-forward (2026-03-08) and fall-back (2026-11-01)
   days — "yesterday"/"today" start-of-day and "2 hours ago" are correct;
   capture at 00:10 local: "yesterday" is the previous date, "last night"
   is previous date 21:00.
7. Case-insensitive, tolerant of leading on/at/about/around and trailing
   punctuation. `./gradlew test` passes.

### DS1.3 — Candidate selector and interpretation validator

**Scope:** `core-domain/src`. Depends on DS1.1, DS1.2 (validator consumes the
temporal result type).

Selector per R3; validator per R6 and §5.2 (paste §5.2 into the packet),
emitting decision, `ValidationStatus` (AUTO_ACCEPT→VALID,
NEEDS_REVIEW→NEEDS_REVIEW, REJECT→INVALID) and a set of reason-code enums.

**Acceptance criteria:**
1. Selector: catalog ≤ bound → all ACTIVE entries, stable order (display
   name normalized, then id); MERGED/ARCHIVED never returned; above bound →
   token/alias hits first, then most-recent occurrence, truncated to bound;
   hash identical for identical catalogs and different when any candidate id
   or name differs; hash contains no raw text.
2. Validator: for every REJECT and NEEDS_REVIEW rule in §5.2 a failing case
   asserting its reason code, and one AUTO_ACCEPT case each for EXISTING and
   NEW. Multiple triggered reasons are all reported; REJECT outranks
   NEEDS_REVIEW.
3. False-merge case: "I edged the lawn." with candidate EXISTING Mow lawn at
   MEDIUM or LOW → NEEDS_REVIEW. TEST_STRATEGY §6 inputs with AMBIGUOUS /
   UNRESOLVED candidates → NEEDS_REVIEW.
4. NEW_ACTIVITY whose name normalizes to an ACTIVE activity's name or alias →
   NEEDS_REVIEW with a duplicate reason; speech confidence 0.49 → review,
   0.5 and null → no speech reason. `./gradlew test` passes.

### DS1.4 — `ActivityRepository` implementation in core-data

**Scope:** `core-data/src`. Depends on DS1.1.

Implements every repository function against the existing database and
`ActivityLedgerWriter`. Blocking DAO work runs on an injected dispatcher
(kotlinx-coroutines is already on the classpath via room-ktx; declare it
explicitly only if the build requires, pinned). Normalized names come from
`NameNormalizer`. Additive data-layer changes only: a transactional
"record non-accepting outcome" operation, and correction-with-new-activity
(create ACTIVE activity + correction + occurrence update in one transaction).
Both live in `LedgerWriteDao`/the writer so the write-surface guard still
holds. Review resolution needs no new operation: it is `acceptInterpretation`
with a user-resolution interpretation record (§5.3).

**Acceptance criteria:**
1. Round-trip tests for every repository function on in-memory Room.
2. Catalog: only ACTIVE activities; aliases normalized; last occurredAt
   ignores HIDDEN occurrences.
3. Record outcome: interpretation row + processing state are atomic (forced
   failure after the insert leaves neither); with no interpretation, only the
   state changes.
4. Correction with a new activity: one correction row with previous/new
   activity ids, new activity ACTIVE with normalized name; forced failure
   rolls back the activity too.
5. Accepting a user-resolution record for a NEEDS_REVIEW capture that already
   has an INVALID model interpretation leaves that interpretation unchanged
   and points the occurrence at the new record.
6. DB1 raw-text immutability test extended to every new write path;
   `DaoWriteSurfaceGuardTest` still passes (and bites on a planted @Update);
   schemas unchanged. `./gradlew test` passes.

### DS1.5 — Orchestrator, correction service, review resolution

**Scope:** `core-domain/src`, `core-testing/src`, `core-data/src/test`
(integration test only). Depends on DS1.1-DS1.4.

R7, R9, R10 as pure services over `ActivityRepository`, `ActivityInterpreter`,
selector, resolver, validator and `Clock`. `core-testing` gains an in-memory
`ActivityRepository` fake honouring the same idempotency and refusal rules.

**Acceptance criteria:**
1. Orchestrator, per outcome (fake repo): AUTO_ACCEPT existing and new →
   one occurrence, capture PERSISTED; NEEDS_REVIEW → interpretation with
   NEEDS_REVIEW, capture NEEDS_REVIEW, no occurrence/activity; REJECT →
   INVALID interpretation, capture NEEDS_REVIEW; MALFORMED failure → INVALID
   interpretation with provenance, capture NEEDS_REVIEW; UNAVAILABLE /
   RETRYABLE → no interpretation, FAILED_RETRYABLE. Rerun on a capture with an
   occurrence calls neither interpreter nor repository writes.
2. Correction service refuses unknown occurrence, HIDDEN occurrence, non-ACTIVE
   target activity, occurred time after now, invalid new name; no-op reported
   as nothing-changed; time change stores the given precision.
3. Review resolution: existing or new activity, optional time override
   (default: capture instant, INFERRED_NOW), refuses a capture that already
   has an occurrence; the stored interpretation says `user-resolution`.
4. Integration test in `core-data` runs the real orchestrator with a fake
   interpreter against real Room for "I cut the grass yesterday." →
   Mow lawn, DATE_ONLY 2026-09-14 local start of day, raw text intact.
5. `./gradlew test` passes.

### DS1.6 — Documentation

**Scope:** `docs/ARCHITECTURE.md`, `docs/AI_INTERPRETATION_SPEC.md`,
`docs/DECISIONS.md`, `docs/DATA_MODEL.md`, `docs/PROJECT_STATUS.md`.
Depends on DS1.5.

R12: ARCHITECTURE §5 to real interface names; AI_INTERPRETATION_SPEC §10-11
to the two-outcome policy and resolver rules; ADR-027 (two-outcome confidence
policy, owner decision), ADR-028 (temporal resolution rules incl. the owner's
weekday and "N days/weeks ago" choices); DATA_MODEL notes the user-resolution
interpretation and correction-with-new-activity operation; PROJECT_STATUS
roadmap item 7 and the Step 7 open item on hide/restore.

**Acceptance criteria:** each document matches the code as merged on the
branch; no stale "review marker" tier anywhere in `docs/`
(`grep -ri "review marker" docs` returns nothing outside proposal history).
