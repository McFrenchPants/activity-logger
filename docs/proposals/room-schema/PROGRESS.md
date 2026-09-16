# Progress — Room schema v1 + migration test infrastructure

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — signed off by owner 2026-09-16.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/room-schema`, off `main` at 2640ca2.

This work item is **sdlc-tracked** (`DB1` in `.sdlc/state.json`). Every task
routes to the verifier agent: all of it touches the data-persistence floor
trigger, and most of it touches raw-capture immutability.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| DB1.1 | Host-side Room test tooling + ID factory | done | Verifier pass. Robolectric 4.17 at sdk 35; driver-based MigrationTestHelper; UUIDv7 via stdlib. See TOOLING_NOTES.md |
| DB1.2 | Domain vocabularies in core-domain | done | Verifier pass. Names pinned by VocabularyNamesTest |
| DB1.3 | Schema v1: entities, converters, database | done | Verifier pass. FixtureInsertDao is temporary; DB1.4 replaces it |
| DB1.4 | Data-layer operations and integrity tests | done | Verifier pass. Guard tightening for occurrence updates carried into DB1.5 |
| DB1.5 | Migration harness, production builder, destructive-fallback guard | done | Verifier pass; verifier independently reproduced both fallback bite checks |
| DB1.6 | Documentation: DATA_MODEL, identifier ADR, PROJECT_STATUS | done | Verifier pass. ADR-026 (UUIDv7). DATA_MODEL checked column by column against 1.json |

## Open items (carry forward)

- Migration harness compares only columns present in both versions; at the
  first real migration, add an explicit check so a dropped/renamed column
  cannot lose data silently. Its value comparison cannot fail until a real
  migration exists.
- `ActivityLedgerDatabaseFactory.builder()` is visible module-wide; when the
  phone app wires the database, it must go through `create()` (the
  behavioral fallback guard only exercises the factory itself).
- `applyCorrection` does not refuse corrections to HIDDEN occurrences —
  policy question for Step 3's correction service, not the data layer.

## Session log

### 2026-09-16 — merged to main

Owner asked for the merge. `feature/room-schema` merged into `main` with
`--no-ff` (fast-forward was possible; merge commit kept for a visible
boundary). `./gradlew test` on main after merge: 65 tests, 0 failures.
DB1 and its tasks set to `released`, backlog item 3 `done`, PROJECT_STATUS
roadmap item 6 struck through. Not pushed to origin.

### 2026-09-16 — DB1.6 done; work item complete, awaiting owner merge

DATA_MODEL.md now describes schema v1 as exported (captured_zone_id, all 12
NO ACTION FKs with their indexes, unique occurrence-per-capture, vocabulary
table, no triggers, corrections don't fabricate interpretations). ADR-026
records UUIDv7 identifiers and the UUIDv4 fallback rule. PROJECT_STATUS closes
the UUID question and marks schema v1 implemented pending merge. Verifier
pass; orchestrator fixed one grammar slip in ADR-026. Earlier tracking notes
said "13 FKs" — a miscount; 1.json has 12, corrected here and in state.json.
Backlog item 3 note updated (still `in progress` until merged). Verifier
nit left as is: DATA_MODEL points to TEST_STRATEGY §11 for the migration
harness, which states the requirement but doesn't name the harness file.
First spawn again got an unsubstituted packet (orchestrator error, same as
DB1.1); re-spawned with the packet inline — not counted as an attempt.
When merged: set DB1 and its tasks to `released`, backlog item 3 to `done`.

### 2026-09-16 — DB1.2 to DB1.5 done; stopped at task budget

Five tasks completed this run (budget `max_tasks_per_run: 5`), each passed the
verifier. core-data now has: schema v1 (six tables, 12 NO ACTION FKs, Room
enforces FKs itself), blocking DAOs with a class-file-reading guard test that
restricts the write surface (no @Update/@Upsert/@Delete, ABORT inserts only,
occurrence/correction writes only in `LedgerWriteDao`), transactional
`acceptInterpretation` (idempotent per capture) and `applyCorrection`
(always writes a correction row), the TEST_STRATEGY §8 scenario, a production
factory with an empty migrations list, a seeded v1 migration harness, a
schema-version consistency test, and a behavioral no-destructive-fallback
guard. 47 core-data tests. TOOLING_NOTES.md corrected after DB1.3 (FK
callback removed). Next: DB1.6 (docs + identifier ADR), then the work item
is complete.

### 2026-09-16 — DB1.1 done

Host-side Room tests work (Robolectric 4.17, SDK 35 because 36 fails on JDK
21; driver-based `MigrationTestHelper` because the Instrumentation/Class
constructor fails on Windows paths). `Uuid.generateV7()` works at Kotlin
2.3.21, so IDs are UUIDv7 — DB1.6's ADR records that. Carry-forward for
DB1.3/DB1.5: `inMemoryTestDatabase` forces `foreign_keys=ON` in tests, so FK
tests on the real schema must also show Room enables FKs without that
callback (production builder has none); MigrationTestHelper connections do
not enable FKs. First spawn got an unsubstituted packet (orchestrator error),
re-spawned fresh with the packet inline.

### 2026-09-16 — design signed off; plan written

Owner approved the design spec. Implementation plan written with six tasks
and a vocabulary table (orchestrator decision: the docs name only some enum
values; the rest are the minimal set Steps 3-4 need, stored as text so they
are additive). Tasks added to `.sdlc/state.json` with `lifecycle_state: null`
until packeted. Note: `validate-state.mjs` against HEAD reports the SS1
`released` states lack `.sdlc/evidence/` files — pre-existing, not caused by
this change; left as is.

### 2026-09-16 — design spec drafted; stopped for sign-off

Orient found nothing in flight: `feature/gradle-scaffold` and
`feature/platform-validation` were both merged to `main` by the owner. Stale
tracking fixed in passing: backlog item 2 marked `done`, SS1 and its tasks set
to `released` in `.sdlc/state.json`, `PROJECT_STATUS.md` roadmap advanced.

Owner picked backlog item 3 over the watch speech check (item 7). Sized as
"significant" (six tables, integrity invariants, migration infra), so a design
spec was written first. Technical calls made in the spec (§5): UUIDv7 via the
Kotlin stdlib with a UUIDv4 fallback; unique occurrence per raw capture;
`captured_zone_id` added to raw captures; no DB-level uniqueness on activity
names; corrections do not fabricate interpretations; no SQLite triggers;
vocab enums in `core-domain`, Room in `core-data`.

Next: on sign-off, write `IMPLEMENTATION_PLAN.md`. First task should verify the
UUIDv7 API at Kotlin 2.3.21 and pick the host-side Room test approach by
running it (spec §6).
