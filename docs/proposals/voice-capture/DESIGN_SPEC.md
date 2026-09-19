# Design spec — Phone voice capture (work item VC1)

Build-guide Step 6 (`docs/IMPLEMENTATION_HANDOFF.md`), the step typed capture
(UI1) deliberately skipped ahead of. Owner chose it on 2026-09-19 after
pairing the OnePlus Watch 3, on the reasoning that the watch app is voice-only
and so voice must exist on the phone first.
Branch: `feature/voice-capture`, off `main` at 7e3298f.

**Approval.** Owner chose this item on 2026-09-19 and answered the two product
questions in §8 the same day. Scope of this spec awaiting sign-off.

## 1. Problem

`core-speech` is an empty placeholder. Every capture that exists today was
typed. `SpeechTranscriber` is a conceptual interface in
`docs/ARCHITECTURE.md` §5 with no implementation, and ADR-024 pinned
`SpeechRecognizer.createOnDeviceSpeechRecognizer()` for it eight days ago
without anything calling it. Three things are blocked behind that gap:

- The product's stated primary input is speech (UX_VISUAL_SPEC §4.1). The app
  cannot currently be used the way it is designed to be used.
- The watch app is voice-only. Building it before a working, proven
  transcription adapter exists would mean debugging a new transport and a new
  recognizer at the same time.
- Backlog item 6 (measure ML Kit Advanced mode against the platform
  recognizer) is explicitly blocked until "Step 6 provides a real capture
  pipeline".

## 2. Goals

- **G1. `SpeechTranscriber` in `core-speech`.** A real interface and one
  Android implementation over
  `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (ADR-024), plus an
  availability check. Callback-based platform API adapted to a coroutine
  surface that reports partial results, one final result, and a typed failure
  kind. No `com.google.mlkit` dependency may appear in this module (ADR-023).
- **G2. Voice on the Log screen.** A microphone button beside the existing
  text field (owner decision, §8 Q1), the listening state of
  UX_VISUAL_SPEC §4.1 (live "Listening…" region, live transcript in evidence
  style, stop affordance, Recent dimmed and hidden from accessibility), and
  the recognition-failure card ("Couldn't make out any words. Nothing was
  saved.", *Try again* / *Type instead*).
- **G3. Voice captures through the same pipeline.** A final transcript becomes
  a `RawCapture` with `source = PHONE_VOICE`, a voice `sourceSurface`, and the
  recognizer's confidence and alternatives recorded in the existing
  `speechConfidence` / `speechAlternativesJson` columns — then goes through
  `CaptureInterpretationOrchestrator` exactly as typed text does. One pipeline,
  not two.
- **G4. Microphone permission.** `RECORD_AUDIO` requested at the first tap of
  the mic button, with an honest permanently-denied state that leaves typing
  available. The permission is declared only in `app-phone` and `app-wear`,
  never in a library manifest.
- **G5. Speech capability detection.** A capability answer ("can this device
  transcribe on-device, and in which language") that `ARCHITECTURE.md` §21
  already lists as one of the four capability rows, exposed as a plain
  function so the future Settings screen can read it without new work.
- **G6. Watch feasibility answered.** A one-off instrumented check on the
  OnePlus Watch 3 that records whether `createOnDeviceSpeechRecognizer()` is
  actually available on Wear OS 5 and can start listening — the open question
  in backlog item 7, UX_VISUAL_SPEC §9 and ADR-020.

## 3. Non-goals

- **No Settings screen.** G5 delivers the capability answer, not a screen to
  show it on. Settings is its own later slice.
- **No watch app.** G6 is a throwaway measurement, not the start of the Wear
  capture UI. Nothing user-visible ships on the watch in this work item.
- **No ML Kit Advanced-mode comparison.** That is backlog item 6, and it needs
  this work item finished before it can be measured at all.
- **No change to the interpretation path.** The orchestrator, validator,
  temporal resolver and prompt are untouched. A voice capture and a typed
  capture of the same words must reach the model identically.
- **No re-layout of the Log screen.** Owner chose to keep the text field as the
  primary control (§8 Q1), so the "Tap to speak" hero layout of the approved
  mockups is deliberately not built. This is a recorded divergence from
  UX_VISUAL_SPEC §4.1, not an oversight.
- **No audio retention.** Audio is never written to disk, never buffered
  beyond the recognizer's own session, and never leaves the device. Only the
  transcript is persisted.
- **No continuous or background listening.** Recognition runs only while the
  Log screen is in the foreground and only after an explicit tap.

## 4. Requirements

- **R1.** The transcriber surface is testable on the JVM without a device: the
  interface and its result/failure types live in code with no Android
  dependency in their signatures, and the Android adapter is the only part
  that needs an instrumented test.
- **R2.** Exactly one `RawCapture` per completed recognition. A recognition
  that ends with no words writes nothing at all (UX_VISUAL_SPEC §6:
  "Nothing was saved.").
- **R3.** Partial transcripts are display-only. They are never persisted, never
  sent to the interpreter, and are discarded when the session ends.
- **R4.** Cancelling (stop without speech, navigating away, the screen
  stopping) releases the recognizer and the microphone deterministically. No
  recognizer instance may outlive the screen.
- **R5.** Nothing is logged that contains the user's words — neither transcript
  nor partial, at any log level, ever (AGENTS.md §11). Failure reporting
  carries a kind, never content.
- **R6.** `core-speech` declares no `com.google.mlkit` dependency and no
  `INTERNET` permission, and the phone app's merged manifest still has
  `INTERNET` removed after this work (ADR-025).
- **R7.** A denied or permanently-denied microphone permission never blocks
  typing, and never shows a dead-end: the failure state always offers
  *Type instead*.
- **R8.** Voice and typed captures are distinguishable in the ledger
  (`CaptureSource.PHONE_VOICE` vs `PHONE_TEXT`) so the semantic corpus and any
  later accuracy measurement can separate transcription error from
  interpretation error.

## 5. Constraints

- **ADR-024** fixes the implementation to the platform
  `createOnDeviceSpeechRecognizer()`. ML Kit speech is out of bounds here.
- **ADR-023** confines every ML Kit artifact to `core-ai`; `core-speech` is
  named in its own build file as the module most likely to breach this.
- **ADR-029**: interpretation only runs while the app is the top foreground
  app. Voice capture inherits this — the existing `isStarted` gate on the Log
  screen applies unchanged.
- **ADR-005 / AGENTS.md §11**: no capture text leaves the device, and
  on-device recognition must fail explicitly rather than fall back to a
  network recognizer. Phone `minSdk` 33 exists precisely for this (ADR-021).
- **`core-speech` `minSdk` is the phone floor**, because the watch app
  consumes it too. Nothing added here may require API 34+ without a guard.
- **Room schema is unchanged.** `speechConfidence` and
  `speechAlternativesJson` already exist and have been unused until now; this
  work fills them, it does not migrate anything.

## 6. Verification tier

Per `.sdlc/project.yaml`:

- **VC1.4** (voice capture writing to the ledger) is in the widened tier
  `raw_capture_immutability` — a voice capture must create exactly one
  immutable raw capture and must never rewrite one. Verifier required.
- **VC1.2** (the transcriber adapter) is not in the floor or widen tiers, but
  is the module ADR-023's containment rule names explicitly, so its diff gets
  an orchestrator spot-check against that rule specifically.
- Everything else: orchestrator spot-check.

## 7. Risks

- **R-a. The on-device recognizer may not be available on the watch.** The
  read-only probe on 2026-09-19 found a Wear build of
  `com.google.android.tts` registered as the default recognition service, which
  is suggestive but not proof — `createOnDeviceSpeechRecognizer()` reads a
  separate framework config. VC1.1 answers this first, before any phone work,
  so a bad answer changes the plan early rather than late.
- **R-b. Recognizer behaviour varies by OEM and by recognizer version.** The
  adapter is written against the documented callback contract and treats
  anything unexpected as a typed failure rather than trusting the callback
  order.
- **R-c. Transcription error becomes indistinguishable from interpretation
  error** in the accuracy baseline. Mitigated by R8: the source is recorded, so
  the corpus can keep measuring typed text while real use is voice.
- **R-d. Partial results can leak into persistence** if the final-result
  callback is missed. Mitigated by R2/R3 and a JVM test for the case where a
  session ends after partials with no final result.

## 8. Questions for the owner (product) — answered 2026-09-19

**Q1. Should the Log screen become voice-first, as the approved mockups show?**
**Answer: no.** Keep the text field as the main control and put a microphone
button beside it. Recorded as a deliberate divergence from
UX_VISUAL_SPEC §4.1 (§3 above); the visual spec is amended rather than the
layout being treated as a bug.

**Q2. Verify speech on the OnePlus Watch 3 in this run?**
**Answer: yes, first.** Done as VC1.1 before any phone work, so a negative
answer reshapes the plan early.

## 9. Owner decisions

- 2026-09-19: work item chosen; Q1 and Q2 above answered.
