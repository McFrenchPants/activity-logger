# WC1 -- release evidence (backfilled 2026-10-05)

Watch capture (build-guide Step 8)

Backfilled when .sdlc/state.json lifecycle values were normalised to the framework enum; built from the record, not re-run. All listed work is merged to `main`.

Tasks (all status done): WC1.1, WC1.2, WC1.3, WC1.3b, WC1.4a, WC1.4b, WC1.5, WC1.6, WC1.5a, WC1.5b

## Commits on main naming this id

- b85f772 2026-10-01 WC1.6: device pass; keep pending/finished capture on wake, tap result to capture again
- 4d2b2c0 2026-10-01 WC1.5b: watch Android wiring (Data Layer transport, ack service, alarm retry, outbox in MainActivity), ADR-037
- 9d0760c 2026-10-01 WC1.5a: watch send engine (sink, sender, ack handler), ack-deadline recovery in outbox, end-to-end fake test
- 86b1183 2026-10-01 WC1.4b: watch capture screen, session controller, haptics and microphone permission
- ea1731d 2026-10-01 WC1.4a: watch speech adapter (ordinary recognizer, prefer-offline) and offline assurance check
- 577074a 2026-10-01 WC1.3b: phone receiver for watch captures (idempotent, acks, listener service)
- 0150757 2026-10-01 WC1.3: raw capture creation idempotent on a caller-supplied id
- b6b826e 2026-10-01 WC1.2: durable watch outbox with atomic file store, backoff and ack handling
- 0c17e43 2026-10-01 WC1.1: wear protocol contract, codec and outbox state machine
- 384162a 2026-10-01 WC1: plan, tracking and first task packet for watch capture
- f2ef910 2026-10-01 WC1: design spec for watch capture (awaiting owner sign-off)

## Tracking row

- `PROGRESS.md`: | WC1 | Watch capture: speech, outbox, transport, phone receiver (backlog item 17) | done | 10 tasks; spoken watch captures reach the phone, saved and acked (device pass WC1.6). Tracking: `docs/proposals/watch-capture/PROGRESS.md`. Now on `main`. |
