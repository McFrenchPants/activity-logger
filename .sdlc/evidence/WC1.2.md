# WC1.2 -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Watch outbox

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- b6b826e 2026-10-01 WC1.2: durable watch outbox with atomic file store, backoff and ack handling

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.2 | Watch outbox (durable queue + retry) | done | Verifier pass, no blocking issues. Pure JVM in core-wear-protocol/outbox: FileOutboxStore (atomic temp+move), Outbox, OutboxPolicy (5s,30s,2m,10m,30m,1h cap, never permanent), cap 200 no eviction. 18 new tests. Follow-ups for WC1.4/1.5: PHONE_RECEIVED is never due, so a lost SAVED/NEEDS_REVIEW ack leaves a stuck pending item (no data loss; add staleness resend with same captureId); markSendStarted result must be checked by the sender; UI must let owner discard FAILED (they count toward cap) |
