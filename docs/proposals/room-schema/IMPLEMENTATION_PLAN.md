# Implementation plan — Room schema v1 + migration test infrastructure (backlog item 3)

Work item `DB1` (sdlc-tracked). Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md),
signed off by the owner 2026-09-16. Branch `feature/room-schema`.

Audience: agents. Requirement numbers (R1..R12) and decision numbers (§5.x)
refer to the design spec.

## Standing constraints for every task

- Kotlin stays 2.3.21, KSP 2.3.12, Room 2.8.5 (ADR-022). Any new library is
  verified to resolve and actually run on this toolchain before it enters
  `gradle/libs.versions.toml`, and is pinned there. No version literals in
  module build files.
- No `com.google.mlkit` anything in `core-data` or `core-domain` (ADR-023).
- `core-domain` stays pure Kotlin/JVM: no Android, no Room types.
- `exportSchema = true`; schema JSON lives in tracked `core-data/schemas`.
- No `fallbackToDestructiveMigration*` call anywhere, in any variant.
- No logging of row contents (AGENTS.md §11). No INTERNET permission.
- `allowBackup="false"` untouched.
- Routine test gate is host-side `./gradlew test`; nothing needs a device.
- Never weaken, skip or `@Ignore` a test to get green.

## Vocabularies (orchestrator decision, stored as enum *names*, R8)

Where DOMAIN_MODEL/DATA_MODEL/AI_INTERPRETATION_SPEC name values, these follow
them; where they don't, these are the minimal set Steps 3-4 need. All are
additive later because they are stored as text.

| Enum (core-domain) | Values | Column(s) |
|---|---|---|
| `ActivityState` | COMPLETED, IN_PROGRESS | interpretations.activity_state, occurrences.activity_state, corrections.previous/new_activity_state |
| `ActivityResolution` | EXISTING_ACTIVITY, NEW_ACTIVITY, AMBIGUOUS, UNRESOLVED | interpretations.activity_resolution |
| `ProcessingState` | CAPTURED, QUEUED_FOR_PHONE, TRANSCRIBED, INTERPRETING, INTERPRETED, PERSISTED, NEEDS_REVIEW, FAILED_RETRYABLE, FAILED_FINAL | raw_captures.processing_state |
| `TimePrecision` | EXACT, APPROXIMATE, DATE_ONLY, INFERRED_NOW | interpretations/occurrences.time_precision, corrections.previous/new_time_precision |
| `VisibilityStatus` | ACTIVE, HIDDEN | occurrences.visibility_status |
| `CanonicalActivityStatus` | ACTIVE, MERGED, ARCHIVED | canonical_activities.status |
| `AliasSource` | USER_CORRECTION, AI_CONFIRMED, SEEDED, MANUAL | activity_aliases.source |
| `CorrectionSource` | USER, REINTERPRETATION | corrections.source (USER is the COR-004 user-originated flag) |
| `CaptureSource` | PHONE_VOICE, PHONE_TEXT, WATCH_VOICE | raw_captures.source |
| `InterpretationOperation` | LOG_ACTIVITY, QUERY_HISTORY, UNSUPPORTED | interpretations.operation |
| `ValidationStatus` | VALID, INVALID, NEEDS_REVIEW | interpretations.validation_status |
| `ConfidenceBand` | HIGH, MEDIUM, LOW | interpretations.model_confidence_band |

Reading an unknown stored name must fail loudly (exception), never map to a
default constant.

## Tasks

### DB1.1 — Host-side Room test tooling + ID factory

**Scope:** `gradle/libs.versions.toml`, `core-data/build.gradle.kts`,
`core-data/src/main/kotlin/.../core/data/id/`, `core-data/src/test/`,
`docs/proposals/room-schema/TOOLING_NOTES.md` (new).

Settle spec §6 by running things, not by recall:

1. Choose and wire the host-side approach for Room tests in the `core-data`
   Android library module (candidates: Robolectric + androidx.test core;
   Room's bundled SQLite driver). The choice must support both an in-memory
   database with foreign keys enforced *and* `MigrationTestHelper`
   (create-at-version from the exported schema JSON). Prove both against the
   existing placeholder database with small tests. Those proof tests are
   allowed to be deleted by DB1.3 along with the placeholder.
2. Determine whether `kotlin.uuid.Uuid` at Kotlin 2.3.21 has a working UUIDv7
   generator. Implement `IdFactory` (interface, returns canonical lowercase
   36-char text) with a production implementation using V7 if available,
   else `java.util.UUID.randomUUID()`. Opt-in to experimental API confined to
   that one file. Plus a deterministic test implementation.
3. Write `TOOLING_NOTES.md`: approach chosen and why, every new catalog entry
   with exact version, the UUID finding with evidence (compile/test output).

**Acceptance criteria:**
1. `./gradlew :core-data:test` runs ≥1 test that opens an in-memory Room DB on
   the host and ≥1 test that uses `MigrationTestHelper` to create the
   placeholder DB at version 1 from `core-data/schemas`; JUnit XML shows them
   executed and passing.
2. A test proves `PRAGMA foreign_keys` is on (value 1) for a DB opened the way
   later tests will open it.
3. `IdFactory` production output: 36 chars, lowercase, valid UUID; if V7, the
   version nibble is 7 and IDs generated in sequence sort non-decreasing.
4. Every new dependency is in the catalog with a pinned version; no version
   literal in `core-data/build.gradle.kts`; Kotlin/KSP/Room/AGP unchanged.
5. `./gradlew test` (all modules) passes.

### DB1.2 — Domain vocabularies in core-domain

**Scope:** `core-domain/src/main/kotlin/.../core/domain/`,
`core-domain/src/test/`. Depends on nothing; after DB1.1 in sequence.

The twelve enums in the table above, as plain Kotlin enums in package
`com.mcfrenchpants.activityledger.core.domain.model` (or similar), public.
Remove `ScaffoldPlaceholder.kt` and its test.

**Acceptance criteria:**
1. Enum names and constants exactly match the table.
2. A test pins every constant's `name` per enum (a golden list), so renaming
   or removing a constant fails a test. Ordinal is not asserted and not used.
3. `core-domain` build file unchanged in plugins/dependencies (still pure JVM).
4. Placeholder files removed; `./gradlew test` passes.

### DB1.3 — Schema v1: entities, converters, database

**Scope:** `core-data/src/main/kotlin/`, `core-data/schemas/`,
`core-data/src/test/`. Depends on DB1.1, DB1.2.

- Six `@Entity` classes per DATA_MODEL §2 (R1), `internal`, plus
  `raw_captures.captured_zone_id TEXT NOT NULL` (§5.3). Text primary keys (R2).
- Enum columns via type converters storing `name` (R8); unknown name → throw.
- Foreign keys (R3), all `onDelete = NO_ACTION` (or RESTRICT), no CASCADE /
  SET_NULL anywhere: aliases→activities; interpretations.raw_capture_id→
  raw_captures; interpretations.matched_activity_id→activities;
  occurrences.{canonical_activity_id, raw_capture_id,
  effective_interpretation_id}; corrections.occurrence_id→occurrences;
  corrections.{previous,new}_canonical_activity_id→activities;
  corrections.{previous,new}_effective_interpretation_id→interpretations;
  canonical_activities.merged_into_activity_id→canonical_activities.
- Indexes: DATA_MODEL's list, with `occurrences.raw_capture_id` **unique**
  (§5.2), `activity_aliases(canonical_activity_id, normalized_alias)` unique
  (§5.4), `canonical_activities.normalized_name` non-unique, plus an index on
  every FK child column Room asks for (no Room FK-index warnings left).
  `(canonical_activity_id, occurred_at)` composite index for R9's query.
- `ActivityLedgerDatabase` version 1, `exportSchema = true`. Exported
  `1.json` committed.
- Delete the placeholder entity/DAO/database, its schema JSON directory, and
  DB1.1's placeholder-only proof tests (re-point the FK-pragma test to the new
  DB rather than deleting its coverage).

**Acceptance criteria:**
1. Exported schema JSON shows the six tables with columns/nullability per
   DATA_MODEL + `captured_zone_id`, and every FK and index listed above.
   No `CASCADE` or `SET NULL` in it.
2. Tests: deleting a raw capture referenced by an interpretation or occurrence
   fails with a constraint error; deleting an interpretation referenced by an
   occurrence fails; inserting a second occurrence for one raw capture fails;
   inserting a duplicate `(activity, normalized_alias)` fails.
3. A converter test: round-trips every enum constant; an unknown stored name
   throws.
4. No placeholder remains (`grep -ri placeholder core-data` finds nothing in
   source or schemas). KSP build has no Room warnings. `./gradlew test` passes.

### DB1.4 — Data-layer operations and integrity tests

**Scope:** `core-data/src/main/kotlin/`, `core-data/src/test/`. Depends on
DB1.3.

DAOs (internal) exposing only these write paths:

- Raw captures: `insert`; `updateProcessingState(id, state, updatedAt)`.
  No `@Update` / generic update / delete (R4).
- Canonical activities: `insert`; aliases: `insert`. (Status changes/merge are
  out of scope.)
- Interpretations: `insert` only (R5).
- Occurrences: no public standalone insert/update — changed only through the
  two transactional operations below.
- `acceptInterpretation(...)` — one `@Transaction` (R7): if an occurrence
  already exists for the raw capture, return it and write nothing; else insert
  the interpretation, insert the new canonical activity if supplied, insert
  the occurrence pointing at the interpretation, set the raw capture's
  processing state to PERSISTED. Returns the occurrence id.
- `applyCorrection(occurrenceId, changes, source, reason, now)` — one
  `@Transaction` (R6, §5.5): reads the occurrence's current activity, time,
  precision, state, effective interpretation; writes a correction row with
  previous = current and new = requested for each changed field (unchanged
  fields null on both sides); updates the occurrence and `updated_at`.
- Reads: `latestOccurrenceOfActivity(activityId)` (visibility ACTIVE only,
  newest `occurred_at`); occurrence by id; interpretations for a raw capture;
  corrections for an occurrence; raw capture by id.

**Acceptance criteria:**
1. TEST_STRATEGY §8 end to end: "I edged the lawn" accepted as Mow lawn, then
   corrected to Edge lawn — raw text unchanged, original interpretation still
   present, correction row has previous Mow / new Edge, occurrence now Edge,
   latest-Mow query no longer returns it, latest-Edge does.
2. R4: after exercising every write operation the layer exposes against a
   capture, its `raw_text`, `source`, `captured_at`, `captured_zone_id` are
   byte-identical. A reflection/compile-level check or review note confirms no
   DAO method can write those columns.
3. R5: no DAO method updates or deletes interpretations or corrections
   (asserted by a test enumerating DAO methods/annotations, or equivalent).
4. R7: failure mid-transaction (e.g. occurrence insert forced to fail) leaves
   no interpretation, no new activity, and processing state unchanged; calling
   `acceptInterpretation` twice for the same capture yields one occurrence, one
   interpretation, same returned id.
5. `latestOccurrenceOfActivity` uses the composite index (asserted via
   `EXPLAIN QUERY PLAN` in a test) and ignores HIDDEN rows.
6. Schema JSON unchanged from DB1.3 (still version 1). `./gradlew test` passes.

### DB1.5 — Migration harness, production builder, destructive-fallback guard

**Scope:** `core-data/src/main/kotlin/`, `core-data/src/test/`,
`core-data/build.gradle.kts` (test resources wiring only). Depends on DB1.4.

- Production database factory in `core-data` (e.g. `ActivityLedgerDatabaseFactory`)
  that builds the real DB with an explicit, currently empty, migrations list
  and no destructive fallback.
- Reusable test harness (R10): given a start version, creates the DB from the
  exported JSON via `MigrationTestHelper`, populates representative rows in
  all six tables (incl. a corrected occurrence and a second interpretation)
  using raw SQL (not the current-version DAOs), runs
  `runMigrationsAndValidate` to current version with the production migrations
  list, then asserts every raw capture, activity, alias, interpretation,
  correction and occurrence relationship survived value-for-value.
- The harness is exercised for version 1 → current (1).
- Guard (DB-003): a test that fails if the production builder is configured
  with any destructive-migration fallback. Must be demonstrated to actually
  fail when a fallback is added (add it temporarily, run, capture the failure
  output, revert) — report the captured output.

**Acceptance criteria:**
1. Harness test passes for v1; adding a future version requires only a new
   test case calling the harness with a start version (show the call shape).
2. Guard test passes now and was shown to fail with a fallback added
   (evidence in report); the temporary change is not in the diff.
3. No `fallbackToDestructiveMigration` string in `core-data/src/main`.
4. `./gradlew test` passes; schema JSON unchanged.

### DB1.6 — Documentation

**Scope:** `docs/DATA_MODEL.md`, `docs/DECISIONS.md`, `docs/PROJECT_STATUS.md`,
`docs/proposals/room-schema/TOOLING_NOTES.md`. Depends on DB1.5.

- DATA_MODEL: `captured_zone_id`; unique occurrence-per-capture; FK list and
  no-cascade rule; extra FK-child indexes; vocabularies table; note that
  corrections do not fabricate interpretations; no triggers and why.
- New ADR (next free number) for identifiers: UUIDv7 or v4 per DB1.1's actual
  finding, text form, generated by app behind `IdFactory`.
- PROJECT_STATUS: close the "UUID vs UUIDv7" open decision with a pointer to
  the ADR; roadmap Step 2 status.

**Acceptance criteria:**
1. Every difference in spec §5 and the vocabulary table is reflected in
   DATA_MODEL, and matches the committed schema JSON (spot-checked column by
   column for the changed items).
2. ADR states the decision, the evidence, and the fallback rule.
3. No code changes.

## Test command

`./gradlew test` (host-side, all modules). DB1.1 may refine this (e.g. a
specific unit-test variant) and records it in TOOLING_NOTES.md.
