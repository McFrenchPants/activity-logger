# Design spec — Watch capture (work item WC1)

Build-guide Step 8 (`docs/IMPLEMENTATION_HANDOFF.md`): watch capture, durable
outbox, Data Layer transport, acknowledgement, idempotency. Unblocked by
ADR-035 (watch transcribes offline) and WD1 (phone<->watch link works).
Branch: `feature/watch-capture`, stacked on `feature/wear-data-layer`
(itself on unmerged `feature/voice-capture`).

**Approval.** Owner signed off scope 2026-10-01: launcher entry first, complication as a follow-up item (Q1); watch copy as written in WATCH_SPEC §11 (Q2).

## 1. Problem

The watch app is an empty placeholder. The product's reason for the watch
(WATCH_SPEC §1: lowest-friction capture) is unbuilt. Every prerequisite is now
answered: the watch recognizes speech offline (ADR-035), phone and watch
exchange a message and a DataItem (WD1), and the phone already has one
capture pipeline that typed and spoken text share (VC1).

## 2. Goals

- **G1. Listen on the watch.** App-launcher entry opens straight into the
  Listening state, using the in-app recognizer (ADR-035 path b), showing
  partials, no continuous listening, one explicit session.
- **G2. Durable outbox.** A completed transcript becomes a queued capture on
  the watch (own `captureId` UUID, `capturedAtEpochMillis`, `source =
  WATCH_VOICE`, `sourceSurface`), persisted before anything is sent, surviving
  process death and reboot, never silently discarded (WATCH_SPEC §2, §4).
- **G3. Transport + idempotency.** Queued captures go to the phone over the
  Data Layer (durable DataItem for the capture, message for the quick ack, per
  ARCHITECTURE §9) using the compact envelope of WATCH_SPEC §5. The phone
  handles a resend of the same `captureId` without a duplicate (WATCH_SPEC §7).
- **G4. Phone receiver.** The phone accepts a watch capture, stores a
  `RawCapture` (`WATCH_VOICE`), and runs it through the *same* orchestrator
  path as typed and phone-voice captures. No watch-specific interpretation.
- **G5. Acknowledgement.** The phone returns the WATCH_SPEC §6 acknowledgement;
  the watch shows Queued / Saved / Needs review / Failure with the copy,
  haptics and ambient rendering of UX_VISUAL_SPEC §4.6 / WATCH_SPEC §11, and
  retries retryable failures without blocking the capture screen.
- **G6. (Dropped 2026-10-01, ADR-036: network use is fine, no guard or notice.) Was: no silent network fallback.**

## 3. Non-goals

- **Watch-face complication and Tile** are deferred to a follow-up work item
  (see §8 Q1); this item ships the launcher entry only.
- No inference on the watch (AGENTS.md); `app-wear` never depends on `core-ai`.
- No undo, edit, history or review UI on the watch (UX_VISUAL_SPEC §4.6).
- No audio retention or transmission: audio never leaves the recognizer
  session; only transcript text is stored/sent.
- No Settings screen, no standalone (phone-less) operation, no cloud.
- No change to the interpretation path or to phone schema beyond what a new
  `sourceSurface` value needs.

## 4. Requirements

- **R1.** The envelope, ack types and state machine live in
  `core-wear-protocol` with no Android dependency, JVM-testable, versioned
  (`protocolVersion`).
- **R2.** A capture is persisted on the watch *before* the first send attempt.
- **R3.** Resending the same `captureId` any number of times yields exactly one
  `RawCapture`, one interpretation and one occurrence on the phone, and the
  phone may re-send the last ack.
- **R4.** Raw captures stay immutable (AGENTS.md #4); the phone stores the
  watch's text as received, plus confidence and alternatives if present.
- **R5.** An empty recognition writes nothing and shows the Failure state
  ("Couldn't capture. Tap to retry.").
- **R6.** Recognizer is released on every exit path; no transcript or partial
  in any log at any level (AGENTS.md #11). Failure reporting carries a kind
  only (reuse `SpeechFailure`).
- **R7.** Phone and watch merged manifests keep `INTERNET` removed (ADR-025).
- **R8.** Retry is bounded and battery-friendly: no aggressive polling, no
  long-running service; rely on Data Layer delivery plus WorkManager-style
  deferred retry.
- **R9.** Transport tests run on the JVM against a fake; one on-device pass on
  the Pixel 10 Pro + OnePlus Watch 3 closes the work item (cf. VC1.5).

## 5. Constraints

ADR-005 (no *silent* cloud fallback), ADR-020, ADR-021 (watch `minSdk` 34),
ADR-025, ADR-035, WATCH_SPEC §2/§10/§13, ARCHITECTURE §8-§9. The WD1 echo
service and the Dictation Probe are debug-only and must stay out of release.

## 6. Verification tier

Verifier agent for the tasks that touch the phone receiver, idempotency and the
durable outbox (data persistence, wear-transport idempotency, raw-capture
immutability, all in `verification_profile`). Spot-check for UI-only tasks.

## 7. Proposed task shape (for the plan, not a commitment)

1. Protocol module: envelope, ack, state machine (JVM).
2. Watch outbox: durable store + retry policy (JVM + on-device).
3. Phone receiver: Data Layer listener -> existing pipeline, idempotent, ack.
4. Watch UI + recognizer adapter: Listening, Queued, Success, Needs review,
   Failure, haptics, ambient; G6 guard.
5. Wire watch UI to outbox and transport; end-to-end fake test.
6. Device pass (phone + watch) and documentation.

## 8. Open questions for the owner

- **Q1. Complication in this item or after?** ADR-020 says the complication is
  the fastest capture path (one tap from the watch face). It adds a separate
  surface to build and test. Recommendation: ship launcher capture end to end
  first, then add the complication as its own small item.
- **Q2. Success wording on the watch.** WATCH_SPEC §11 shows "Needs review:
  Saved — review on phone" and "Success: ✓ <activity name>". Confirm those as
  the final copy, or give changes.
