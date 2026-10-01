# Progress - Watch capture

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) (owner signed off 2026-10-01).
Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). Branch `feature/watch-capture`,
stacked on `feature/wear-data-layer` on `feature/voice-capture` (both unmerged).
sdlc-tracked as `WC1`.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| WC1.1 | Protocol module: envelope, ack, state machine | done | Spot-check. 16 JVM tests; max text 4096 chars, 5 alternatives. NEEDS_REVIEW maps to non-terminal PROCESSED: WC1.2 must treat PROCESSED as complete/removable. WC1.3 must pin SOURCE_WATCH_VOICE == CaptureSource.WATCH_VOICE.name with a test |
| WC1.2 | Watch outbox (durable queue + retry) | todo | Verifier tier |
| WC1.3 | Phone receiver, idempotent, acks | todo | Verifier tier |
| WC1.4 | Watch recognizer adapter + UI states + haptics + no-silent-network guard | todo | |
| WC1.5 | Wire UI to outbox and transport; end-to-end fake test | todo | |
| WC1.6 | Device pass and documentation | todo | Needs phone + watch online |

## Session log

### 2026-10-01 - WC1.1 done
Protocol contract in core-wear-protocol: CaptureEnvelope, CaptureAck, paths (/capture/<id>, /capture-ack), strict total codec with payload-free failure kinds, pure OutboxTransitions. Next: WC1.2 (outbox) and WC1.3 (phone receiver).

### 2026-10-01 - WC1 scaffolded
Owner chose the watch capture app after the watch was shown to transcribe offline. Signed off: launcher entry first (complication is a later item), watch copy as in WATCH_SPEC section 11.
