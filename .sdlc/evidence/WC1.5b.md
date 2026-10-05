# WC1.5b -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Android wiring: Data Layer transport, ack listener, deferred retry, MainActivity

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 4d2b2c0 2026-10-01 WC1.5b: watch Android wiring (Data Layer transport, ack service, alarm retry, outbox in MainActivity), ADR-037

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.5b | Android wiring: DataClient transport (attempt key, retract), ack listener service, deferred retry wake-up from `drain()` result, Application holder for Outbox/FileOutboxStore, MainActivity uses OutboxCaptureSink + AckHandler->controller.onAck, remove PlaceholderCaptureSink, discard-FAILED path | done | Verifier pass, no blocking. DataLayerCaptureTransport, CaptureAckListenerService (/capture-ack), CaptureRuntime + DrainLoop (single-flight), AlarmManager retry (ADR-037), MainActivity on OutboxCaptureSink, placeholder removed; Full outbox drops oldest FAILED. Compile-verified only: WC1.6 must check on device that DataItem put/delete, the ack service, the alarm and screen updates work, that a SAVED ack after NEEDS_REVIEW is acceptable, and that ambient mode is handled |
