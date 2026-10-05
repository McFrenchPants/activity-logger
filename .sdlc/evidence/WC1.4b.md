# WC1.4b -- release evidence (backfilled 2026-10-05)

Task of work item WC1 (Watch capture (build-guide Step 8)): Watch capture UI states, session controller, haptics, permission

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

## Commits on main naming this id

- 86b1183 2026-10-01 WC1.4b: watch capture screen, session controller, haptics and microphone permission

## Tracking row

- `docs/proposals/watch-capture/PROGRESS.md`: | WC1.4b | Watch capture UI states, session controller, haptics, permission | done | Spot-check. 20 JVM tests. PlaceholderCaptureSink discards transcripts: WC1.5 must replace it with the outbox and call controller.onAck. UI/haptics only compile-checked; ambient hook deferred to WC1.6 (no androidx.wear dependency yet). New copy: Listening…, Allow microphone to capture., Voice capture isn't available on this watch. |
