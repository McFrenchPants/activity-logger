# Progress — Explore (Ask, search and dashboard in one screen)

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch `feature/explore`. Design approved by the owner 2026-10-04.
Registered in `.sdlc/state.json` as DH1.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| DH1.1 | `stats` package: filter model + calculator (core-domain) | todo | |
| DH2.1 | `loadExploreEntries()` repository read, DAO, Room, fakes | todo | after DH1.1; verifier |
| DH3.1 | Explore view model + state (phone, no UI); rename ask -> explore | todo | after DH2.1; verifier |
| DH3.2 | Explore screen UI + tab rename | todo | after DH3.1 |
| DH3.3 | Device check on Moto G 2025; merge stage 3 | todo | after DH3.2 |
| DH4.1 | Temporal ranges ("in August", "this year") | todo | after DH3.3 |
| DH4.2 | Question reader q2 (core-ai) + domain seam | todo | after DH4.1; verifier |
| DH4.3 | Lookup -> Explore question result (count / how often) | todo | after DH4.2; verifier |
| DH4.4 | Question corpus + replay gate; Pixel recording | todo | after DH4.3; needs Pixel 10 Pro |
| DH5.1 | Device pass incl. voice; docs/ADRs; owner report | todo | needs owner to speak |

## Session log

### 2026-10-04 - Plan written
Implementation plan written from the approved design. Plan-level decisions P1-P4 (no Flow; questions without date words set All time; stage 3 needs no lookup change; presentation split) in the plan. Starting DH1.1.
