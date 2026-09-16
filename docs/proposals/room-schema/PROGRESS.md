# Progress — Room schema v1 + migration test infrastructure

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — **awaiting owner sign-off**.
Implementation plan: not yet written (written only after sign-off).
Branch: `feature/room-schema`, off `main` at 2640ca2.

This work item is **sdlc-tracked** (`DB1` in `.sdlc/state.json`). Every task
routes to the verifier agent: all of it touches the data-persistence floor
trigger, and most of it touches raw-capture immutability.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| — | Tasks are defined in the implementation plan after design sign-off. | — | — |

## Session log

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
