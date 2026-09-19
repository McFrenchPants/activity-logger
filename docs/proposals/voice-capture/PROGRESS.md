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
| VC1.2 | `SpeechTranscriber` + platform adapter in `core-speech` | done | Spot-check. Payload-free failure enum; 30 JVM tests, no device needed |
| VC1.3 | Mic button, listening state, failure card on Log | done | Spot-check. `ResultCard` restructured into `ForCapture`/`Unresolved` + `RecognitionFailed`. 21 tests |
| VC1.4 | Wire voice through the capture pipeline | done | **Verifier pass.** One capture path shared with typing; session-identity token drops late events |
| VC1.5 | Device pass and documentation | blocked | Docs done. **Device pass never ran** — both test devices went offline mid-run |

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

### 2026-09-19 — VC1.2, VC1.3, VC1.4 done; VC1.5 docs done, device pass blocked

Three implementer tasks plus the documentation half of VC1.5. Full
`./gradlew test assembleDebug` green; 594 project unit tests, 0 failures.

- **VC1.2** — `core-speech` is real. `SpeechTranscriber` is a cold flow of
  `SpeechEvent` per explicit session, with no Android type in the interface or
  any type in its signatures. `SpeechFailure` is a payload-free enum, so a
  failure is *structurally* unable to carry the user's words, and the module
  contains no logging call at all. `PlatformSpeechTranscriber` wraps the
  platform recognizer with every call on the main thread and cancel-then-destroy
  in `awaitClose` on every path including flow cancellation. 30 host-JVM tests,
  no device or Robolectric needed. One approved divergence: `ERROR_CLIENT` maps
  to `CANCELLED` rather than the generic error, since it is the only signal the
  platform gives for "this side ended the session" and `CANCELLED` would
  otherwise be unreachable.
- **VC1.3** — the Log screen's voice UI, with no recognizer behind it yet.
  `RECORD_AUDIO` declared (ADR-025's INTERNET removal re-verified in the merged
  manifest). Microphone icon button beside the text field per the owner's
  decision. Listening state: polite live region, partial in the shared evidence
  style, Recent dimmed *and* cleared from the semantics tree, typed submit
  refused in the state holder rather than only by a disabled button.
  `ResultCard` was restructured rather than widened — `ForCapture` carries
  `captureId`/`rawText`, `Unresolved` narrows to the resolvable cards, and
  `RecognitionFailed` is a bare `data object`, because nothing was heard and
  nothing was saved. `resolve`/`decideLater`/`onPickerChoice` became more
  precise as a result, not more defensive.
- **VC1.4** (verifier tier) — spoken words become captures. A final transcript
  becomes exactly one `RawCapture` (`PHONE_VOICE`, `log_voice` surface, the
  recognizer's confidence and alternatives) and then takes the *same*
  `orchestrator.process` and `cardFor` path typed text takes: one capture path,
  no voice-specific branch. Failures persist nothing; a missing permission, no
  engine, and a busy recognizer get plain-words messages rather than a
  "Try again" card that would be a dead end. A session-identity token means a
  second tap cannot open a second session and a result arriving after the user
  tapped stop is dropped rather than stored. The verifier independently re-ran
  the build and checked ADR-007, ADR-010, ADR-029 and AGENTS.md §11 against the
  diff: **pass**, with four non-blocking notes (an untested blank-transcript
  guard, float-to-double confidence widening, hand-written JSON escaping with
  control characters untested, and two VC1.3 stub methods deleted rather than
  filled in).
- **VC1.5, documentation half** — ADR-024 amended with the measured answers and
  an explicit note that the original 2026-09-15 evidence answered a different
  question; **ADR-035** added for the watch finding and its four options;
  `UX_VISUAL_SPEC.md` §4.1 amended with the owner's layout decision and §9
  closed out; `ARCHITECTURE.md` §5, §7, §14 and §21 updated (`SpeechTranscriber`
  is no longer "conceptual", speech capability detection is no longer "not
  built yet"); `BACKLOG.md` items 6 and 7 resolved and items 15, 16, 17 added.

**VC1.5's device half did not run.** The Pixel 10 Pro and the watch were both
reachable at the start of this session and both went offline before the device
pass. Voice capture has therefore never met a real recognizer — every test
drives a scripted fake. What specifically needs a device:

1. That the real engine's events map as `PlatformSpeechTranscriber` expects —
   in particular whether silence arrives as an empty results bundle or as
   `ERROR_NO_MATCH`, and whether the engine reports confidence at all.
2. The phone probe's question 3, still unanswered: it returned
   `ERROR_9(INSUFFICIENT_PERMISSIONS)` because `RECORD_AUDIO` was not declared
   at the time. VC1.3 declared it, so re-running the probe should now settle it.
3. The accessibility claims VC1.3 flagged as reasoned-but-unverified: whether
   the "Listening…" announcement is useful to a screen reader as partials
   change, whether Recent's dimming reads correctly, the failure card's contrast
   in both themes, and where focus lands after *Type instead*.
4. Real transcription quality, which is the input backlog item 6 needs.
