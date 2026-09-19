# Implementation plan — Phone voice capture (work item VC1)

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md).
Branch: `feature/voice-capture`, off `main` at 7e3298f.
Tracking: sdlc-tracked (`VC1` in `.sdlc/state.json`); task table in
[`PROGRESS.md`](PROGRESS.md).

Flat task list, ordered. VC1.1 runs first by owner choice and can change the
shape of everything after it if the watch answer is negative.

---

## VC1.1 — Does the OnePlus Watch 3 have on-device recognition?

**Scope.** Add an instrumented (`androidTest`) source set to `app-wear` and one
test class that answers, on real hardware, three questions and prints them as
structured assertions/log lines containing no user content:

1. `SpeechRecognizer.isRecognitionAvailable(context)`
2. `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`
3. Whether `createOnDeviceSpeechRecognizer(context)` followed by
   `startListening(...)` reaches `onReadyForSpeech` within a bounded wait
   (proof the engine actually starts), or which `SpeechRecognizer.ERROR_*`
   code it fails with.

The test must run on the main thread (the platform recognizer requires it),
grant `RECORD_AUDIO` via a `GrantPermissionRule`, and always release the
recognizer in a `finally`. It stops the session as soon as
`onReadyForSpeech` arrives — it does not attempt to transcribe real speech,
because no audio can be supplied to an automated run.

Add `RECORD_AUDIO` to `app-wear`'s manifest. Add the minimum instrumented-test
dependencies to `app-wear/build.gradle.kts`, reading every version from
`gradle/libs.versions.toml` (add catalog entries if the needed ones are
missing; no literal versions in a build file).

This test is a measurement, not a regression gate: it must not fail the normal
`./gradlew test` run, and it is expected to be run by hand against the watch.

**Acceptance criteria.**
- `./gradlew :app-wear:assembleDebug :app-wear:assembleDebugAndroidTest` succeeds.
- The test class exists, is annotated to run on the main thread where the
  platform requires it, and releases the recognizer unconditionally.
- No `com.google.mlkit` coordinate appears in `app-wear/build.gradle.kts`
  (ADR-023), and no capture text or transcript is logged (AGENTS.md §11).
- The orchestrator can run it against the connected watch and read a clear
  answer to all three questions from the output.

**Notes.** Do not modify `app-wear`'s `MainActivity` or add product UI. The
watch is reachable at `192.168.50.251:43439`; the orchestrator runs the device
command, not the implementer.

---

## VC1.2 — `SpeechTranscriber` and the platform adapter in `core-speech`

**Scope.** Replace `core-speech`'s `ScaffoldPlaceholder` with the real module:

- A `SpeechTranscriber` interface whose signatures contain no Android types, a
  result type carrying the final transcript plus confidence and alternatives,
  a partial-result channel, and a closed set of failure kinds (no microphone
  permission, no on-device engine, nothing heard, engine busy, engine error,
  cancelled). Failures carry a kind only, never text (AGENTS.md §11).
- `PlatformSpeechTranscriber`, an Android implementation over
  `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (ADR-024) exposing the
  callback API as a cold `Flow` of listening events that completes on the
  final result or a failure, cancels cleanly, and always destroys the
  recognizer.
- A `SpeechCapability` check (is on-device recognition available on this
  device, and the recognizer's language) — cheap, no side effects, no
  download (spec G5).
- JVM tests for everything that does not need a device: the failure mapping,
  the "partials but no final result" case, and the confidence/alternatives
  extraction from a `Bundle`-shaped input behind a small seam.

**Acceptance criteria.**
- `core-speech` compiles and its JVM tests pass under
  `./gradlew :core-speech:test`.
- The transcriber interface and its result/failure types name no Android type.
- `core-speech/build.gradle.kts` declares no `com.google.mlkit` dependency and
  no `INTERNET` permission appears in the module (spec R6, ADR-023, ADR-025).
- The recognizer is destroyed on every exit path including cancellation
  (spec R4), demonstrated by a test on the seam.
- No partial transcript is returned as a final result (spec R3, R-d).
- Nothing containing user words is logged (spec R5).

**Notes.** `minSdk` is the phone floor because the watch consumes this module
too; nothing may require API 34+ without a guard. Do not touch `app-phone`.

---

## VC1.3 — Microphone button, listening state and failure card on Log

**Scope.** The UI half, `app-phone` only:

- Declare `RECORD_AUDIO` in `app-phone`'s manifest.
- Add a microphone button to the Log input row beside the existing text field
  (owner decision; the text field stays the primary control). Content
  descriptions "Log by voice" / "Stop listening" (UX_VISUAL_SPEC §5).
- The listening state of UX_VISUAL_SPEC §4.1: a "Listening…" live region, the
  live partial transcript in evidence style, a stop affordance, and the Recent
  list dimmed and hidden from accessibility while listening.
- The recognition-failure card: `errorContainer`, no fabricated text, exact
  copy "Couldn't make out any words. Nothing was saved." (UX_VISUAL_SPEC §6),
  *Try again* and *Type instead*.
- The permission flow: request on first mic tap; a denied result returns to the
  normal typing state with a plain-words message; a permanently-denied result
  says so once and leaves typing available (spec R7). No dead ends.
- Robolectric/Compose host tests for each new state, in the style of the
  existing `LogScreenTest`.

**Acceptance criteria.**
- `./gradlew :app-phone:testDebugUnitTest` passes, including new tests for the
  listening state, the failure card, and both denied-permission states.
- Typing still works unchanged in every permission state (spec R7).
- The mic button and stop control carry the content descriptions above, and the
  Recent list is hidden from accessibility while listening.
- No transcript or partial text appears in any log call.

**Notes.** This task adds the UI states and the permission flow. It does not
call the transcriber and does not write captures — VC1.4 wires those. Use a
state-holder call that VC1.4 will implement, rather than inventing persistence
here.

---

## VC1.4 — Wire voice through the capture pipeline

**Scope.** Connect VC1.2's transcriber to VC1.3's UI in `LogViewModel` and
`LogViewModelFactory`:

- Start a listening session on mic tap (only while `isStarted`, ADR-029);
  stream partials into the UI state; stop on the stop control, on screen stop,
  and on navigation away (spec R4).
- On a final transcript, create exactly one `RawCapture` with
  `source = CaptureSource.PHONE_VOICE`, a voice `sourceSurface` constant beside
  the existing `LOG_TYPED_SOURCE_SURFACE`, the recognizer's confidence in
  `speechConfidence` and its alternatives in `speechAlternativesJson`, then run
  the existing `orchestrator.process(captureId)` path and reuse the existing
  card mapping unchanged (spec G3).
- A session that ends with no words writes nothing and shows the failure card
  (spec R2).
- Wire the real `PlatformSpeechTranscriber` and the capability check in the
  factory; the view model takes the interface, so its tests stay on the JVM.
- Tests: one capture per session; nothing written when no words; partials never
  persisted; cancellation writes nothing; a voice capture and a typed capture
  of the same words produce the same card.

**Acceptance criteria.**
- `./gradlew :app-phone:testDebugUnitTest :core-speech:test` passes.
- Exactly one raw capture per completed recognition, with `PHONE_VOICE` and a
  voice surface, and its confidence/alternatives populated (spec R2, R8).
- No raw capture is ever updated after creation — voice captures are created
  and then only read (spec: raw-capture immutability, AGENTS.md §4, ADR-007).
- A no-words session and a cancelled session both persist nothing.
- Interpretation is reached through the existing orchestrator call only; no
  second pipeline (spec G3).
- **Verifier tier** (`raw_capture_immutability`).

**Notes.** Do not change `CaptureInterpretationOrchestrator`, the validator, the
temporal resolver or the prompt.

---

## VC1.5 — Device pass and documentation

**Scope.** Orchestrator-run, not delegated: install the debug build on the
Pixel 10 Pro and log real activities by voice end to end (saved, needs review,
no-words failure, permission denial, cancel mid-sentence). Then record the
outcome:

- `docs/proposals/voice-capture/RESULTS.md` — what was tried, what happened,
  transcription quality impressions, anything that needs a follow-up.
- Amend ADR-024 with what the adapter actually needed, and record VC1.1's watch
  answer as a new ADR or an amendment to ADR-020 as appropriate.
- Amend `docs/UX_VISUAL_SPEC.md` §4.1 and §9 with the owner's layout decision
  (text field primary, mic beside it) so the spec and the app agree.
- Update `docs/ARCHITECTURE.md` §5 (`SpeechTranscriber` moves out of
  "Conceptual (not built)"), §7 and §21 (speech capability detection now real).
- Update `BACKLOG.md`: new item for voice capture marked done, item 6
  unblocked, item 7 resolved or updated by VC1.1's answer.
- Update root `PROGRESS.md` and this folder's `PROGRESS.md`.

**Acceptance criteria.**
- Full `./gradlew test testDebugUnitTest assembleDebug` green.
- Voice capture demonstrated working on the Pixel 10 Pro, with the result
  written down honestly including anything that did not work.
- Every doc above reflects what was built, with no stale "not built yet" claims
  about speech.
