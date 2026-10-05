# WC1.3 -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Repository: caller-supplied capture id (idempotent receive)

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 0150757 2026-10-01 WC1.3: raw capture creation idempotent on a caller-supplied id

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.3 | Repository: caller-supplied capture id (idempotent receive) | done | Verifier pass. NewRawCapture.id (nullable); Room does exists-check+insert in one transaction; no schema change; InMemory mirrors. Watch captureId becomes the raw capture id |
