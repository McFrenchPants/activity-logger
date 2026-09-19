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
| VC1.1 | On-device recognition probe (watch + phone) | done | Spot-check. **Watch: on-device recognition is NOT available.** Phone: available, recognizer creates fine |
| VC1.2 | `SpeechTranscriber` + platform adapter in `core-speech` | todo | Depends on VC1.1 only for whether the watch needs a different path |
| VC1.3 | Mic button, listening state, failure card on Log | todo | Owner chose text field primary, mic beside it — divergence from UX_VISUAL_SPEC §4.1. Also declares RECORD_AUDIO, which settles the phone probe's question 3 |
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

### 2026-09-19 — VC1.1 done: the watch cannot do on-device recognition

Two probes written (one per app module, `androidTest`, hand-invoked, never part
of `./gradlew test`) and both run on real hardware by the orchestrator.

**OnePlus Watch 3** (OPWE242, Wear OS / API 34), `WearSpeechProbe`:

```
isRecognitionAvailable=true
isOnDeviceRecognitionAvailable=false
onDeviceStartListening=THREW_UnsupportedOperationException
recognitionServiceComponents=[com.google.android.tts/...GoogleTTSRecognitionService]
```

`createOnDeviceSpeechRecognizer()` throws outright. The Google TTS recognizer
*is* installed and registered as the default `RecognitionService`, which is why
the earlier read-only property probe looked positive — that evidence was
misleading, and the same class of evidence is all ADR-024 had for the phone.

**Pixel 10 Pro** (API 37), `PhoneSpeechProbe`:

```
isRecognitionAvailable=true
isOnDeviceRecognitionAvailable=true
onDeviceStartListening=ERROR_9(INSUFFICIENT_PERMISSIONS)
recognitionServiceComponents=[com.google.android.tts/...GoogleTTSRecognitionService]
```

On-device recognition is available and the recognizer was created successfully;
the session was refused only because `app-phone` does not yet declare
`RECORD_AUDIO` (so `adb shell pm grant` is a no-op). That declaration lands in
VC1.3, which settles question 3 for the phone. Reaching ERROR_9 rather than an
exception is itself the proof the on-device engine was reached.

**Consequence.** ADR-024 holds for the phone and the rest of VC1 proceeds
unchanged. It does **not** hold for the watch: the designed in-app Wear
Listening screen (ADR-020, UX_VISUAL_SPEC §3 D2 / §9, backlog item 7) cannot be
built on `createOnDeviceSpeechRecognizer()`. That is a decision for the watch
work item, not this one; recorded for the owner and written up in VC1.5.

Scope was widened mid-task by the orchestrator to add the phone probe, since
the watch result invalidated the kind of evidence the phone answer rested on.
