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
| DB1.1 | Host-side Room test tooling + ID factory | todo | Settles spec §6 by running it |
| DB1.2 | Domain vocabularies in core-domain | todo | After DB1.1 |
| DB1.3 | Schema v1: entities, converters, database | todo | Needs DB1.1, DB1.2 |
| DB1.4 | Data-layer operations and integrity tests | todo | Needs DB1.3 |
| DB1.5 | Migration harness, production builder, destructive-fallback guard | todo | Needs DB1.4 |
| DB1.6 | Documentation: DATA_MODEL, identifier ADR, PROJECT_STATUS | todo | Needs DB1.5 |

## Session log

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
