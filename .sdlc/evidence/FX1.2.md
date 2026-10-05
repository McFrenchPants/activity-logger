# FX1.2 -- release evidence (backfilled 2026-10-05)

Task of work item FX1 (Two small fixes the corpus turned up (backlog item 14)): TemporalResolver: weekday + part-of-day rule ("Saturday morning")

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- b704bce 2026-09-19 FX1.2: resolve weekday + part-of-day phrases ("Saturday morning")
- 94a61c0 2026-09-19 FX1.2: task packet

## Tracking row

- `PROGRESS.md`: | FX1.2 | Weekday + part-of-day dates ("Saturday morning") in `TemporalResolver` (backlog item 14c) | done | Spot-check. ADR-028 rule added; corpus gap cleared; baseline now 36 cases. Device recording flagged "different corpus" (hash changed) until the next Pixel re-record. |
