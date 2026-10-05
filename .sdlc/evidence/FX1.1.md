# FX1.1 -- release evidence (backfilled 2026-10-05)

Task of work item FX1 (Two small fixes the corpus turned up (backlog item 14)): AICore BUSY -> RETRYABLE, plus a short bounded wait-and-retry in the phone pipeline

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 1365c44 2026-09-19 FX1.1: treat AICore BUSY as retryable; short bounded retry in the phone pipeline

## Tracking row

- `PROGRESS.md`: | FX1.1 | AICore BUSY treated as "try again" + short wait-and-retry in the phone pipeline (backlog item 14b) | done | Verifier pass. Busy refusal now RETRYABLE; `BusyRetryInterpreter` waits 2 s then 4 s. ADR-030 amended. Not yet seen on a device. |
