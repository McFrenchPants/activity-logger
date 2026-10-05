# WC1.5a -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Watch send engine (outbox sink, sender, ack handler, stale-ack recovery) + end-to-end fake test

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 9d0760c 2026-10-01 WC1.5a: watch send engine (sink, sender, ack handler), ack-deadline recovery in outbox, end-to-end fake test

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.5a | Watch send engine (OutboxCaptureSink, OutboxSender, AckHandler, stale-ack recovery) + end-to-end fake test | done | Verifier pass, no blocking. Pure JVM; Data Layer is the `CaptureTransport` interface. Outbox gained ack deadline (2 min), PHONE_RECEIVED resend edge, `recoverStale`, `earliestPendingAttemptAt`. Sender returns next wake time for the scheduler. Non-blocking: `drain()` ends its pass if a still-due record refuses markSendStarted; a SAVED ack after NEEDS_REVIEW is for an already-removed record so the screen stays on NeedsReview (decide in 5b/1.6) |
