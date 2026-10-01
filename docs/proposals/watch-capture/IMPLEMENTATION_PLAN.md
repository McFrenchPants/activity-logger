# Implementation plan - Watch capture (work item WC1)

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md). Branch `feature/watch-capture`.
Tracking: sdlc-tracked (`WC1` in `.sdlc/state.json`); table in [`PROGRESS.md`](PROGRESS.md).
Flat ordered list. WC1.2 and WC1.3 are verifier tier (persistence, transport idempotency,
raw-capture immutability). Task details beyond WC1.1 are refined when reached.

## WC1.1 - Protocol module: envelope, acknowledgement, state machine

Replace `core-wear-protocol`'s scaffold placeholder with the real contract (pure
Kotlin/JVM, no Android, no other project module): `CaptureEnvelope`, `CaptureAck`,
path constants, strict total JSON encode/decode with payload-free failure kinds,
and the watch-side `OutboxState` machine from WATCH_SPEC section 4 as a pure
transition function (illegal edges refused, duplicate ack idempotent). Full
objective and acceptance criteria are in `.sdlc/task-packets/WC1.1.packet.json`.

## WC1.2 - Watch outbox (durable queue + retry policy) - verifier tier
## WC1.3 - Repository accepts caller-supplied capture id, idempotent (done) - verifier tier
## WC1.3b - Phone receiver (Data Layer listener -> existing pipeline, idempotent, ack) - verifier tier
## WC1.4 - Watch recognizer adapter + UI states + haptics + no-silent-network guard
## WC1.5 - Wire watch UI to outbox and transport; end-to-end fake test
## WC1.6 - Device pass (Pixel 10 Pro + OnePlus Watch 3) and documentation
