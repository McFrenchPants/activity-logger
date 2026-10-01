# Progress - Watch capture

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) (owner signed off 2026-10-01).
Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Branch `feature/watch-capture`,
stacked on `feature/wear-data-layer` on `feature/voice-capture` (both unmerged).
sdlc-tracked as `WC1`.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| WC1.1 | Protocol module: envelope, ack, state machine | todo | Spot-check |
| WC1.2 | Watch outbox (durable queue + retry) | todo | Verifier tier |
| WC1.3 | Phone receiver, idempotent, acks | todo | Verifier tier |
| WC1.4 | Watch recognizer adapter + UI states + haptics + no-silent-network guard | todo | |
| WC1.5 | Wire UI to outbox and transport; end-to-end fake test | todo | |
| WC1.6 | Device pass and documentation | todo | Needs phone + watch online |

## Session log

### 2026-10-01 - WC1 scaffolded
Owner chose the watch capture app after the watch was shown to transcribe offline. Signed off: launcher entry first (complication is a later item), watch copy as in WATCH_SPEC section 11.
