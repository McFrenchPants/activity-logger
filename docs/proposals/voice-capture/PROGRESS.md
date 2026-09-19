# Progress — Phone voice capture

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — owner chose the item and
answered both product questions 2026-09-19; scope sign-off pending.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/voice-capture`, off `main` at 7e3298f.

This work item is **sdlc-tracked** (`VC1` in `.sdlc/state.json`). Verification
tier: spec §6 (verifier for VC1.4).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| VC1.1 | Watch on-device recognition probe (OnePlus Watch 3) | todo | Runs first by owner choice; a negative answer reshapes ADR-020 and the watch plan |
| VC1.2 | `SpeechTranscriber` + platform adapter in `core-speech` | todo | Depends on VC1.1 only for whether the watch needs a different path |
| VC1.3 | Mic button, listening state, failure card on Log | todo | Owner chose text field primary, mic beside it — divergence from UX_VISUAL_SPEC §4.1 |
| VC1.4 | Wire voice through the capture pipeline | todo | Verifier tier (`raw_capture_immutability`). Depends on VC1.2 and VC1.3 |
| VC1.5 | Device pass and documentation | todo | Orchestrator-run on the Pixel 10 Pro |

## Session log

### 2026-09-19 — VC1 scaffolded

Nothing was in flight. Owner asked for voice logging before starting the watch
app, having just paired the OnePlus Watch 3 to the Pixel 10 Pro.

Read-only ADB probe of the watch (`192.168.50.251:43439`, OPWE242, Wear OS /
Android 14, API 34): a Wear build of `com.google.android.tts`
(`googletts.google-speech-apk_20260817.01_p0-wear`) is installed and is the
default `android.speech.RecognitionService`. That is suggestive but not proof —
`createOnDeviceSpeechRecognizer()` resolves a separate framework config — hence
VC1.1.

Owner decisions: Log screen keeps the text field as the primary control with a
mic button beside it (rather than the voice-first hero layout the approved
mockups show); and the watch check runs first.
