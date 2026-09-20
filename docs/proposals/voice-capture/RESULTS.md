# Results — Phone voice capture (VC1) on real hardware

Device pass run 2026-09-19/20 on the **Pixel 10 Pro** (Android 17 / API 37) over
Wi-Fi ADB, debug build, and on the **OnePlus Watch 3** (Wear OS 5 / API 34) for
the recognizer probe only.

## Summary

Voice capture works end to end on the phone. The full chain — microphone →
platform on-device recognizer → immutable `RawCapture` → Gemini Nano →
validator → result card → history — ran for real, not against a fake.

Two defects were found that no host-side test could have caught, and both are
fixed and re-verified on the device. One check remains outstanding.

## What was verified

### 1. The on-device recognizer genuinely starts (VC1.1 question 3)

`PhoneSpeechProbe`, with `RECORD_AUDIO` granted:

```
isRecognitionAvailable=true
isOnDeviceRecognitionAvailable=true
onDeviceStartListening=READY_FOR_SPEECH
recognitionServiceComponents=[com.google.android.tts/...GoogleTTSRecognitionService]
```

`READY_FOR_SPEECH` means the recognizer was created, took the microphone, and
began listening. The earlier `ERROR_9(INSUFFICIENT_PERMISSIONS)` result was
purely the missing permission declaration, now added by VC1.3. **ADR-024 is
confirmed on hardware for the phone**, no longer inferred from package
inspection.

### 2. The privacy guarantee survives the new permission

Against the app as actually installed (`dumpsys package`):

```
requested permissions:
  com.google.android.apps.aicore.service.BIND_SERVICE
  android.permission.ACCESS_NETWORK_STATE
  com.mcfrenchpants.activityledger.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  android.permission.RECORD_AUDIO
```

`android.permission.INTERNET` appears **zero** times in the package dump.
Adding the microphone did not weaken ADR-025: the app still has no route to the
network, so spoken words have nowhere to go.

### 3. Real captures through the real pipeline

Three voice captures in the ledger at the time of the pass, two made by the
owner and one triggered by the orchestrator:

| Words | Source | Outcome |
|---|---|---|
| "Add sanitizer to the hot tub" | `PHONE_VOICE` / `log_voice` | matched **Hot tub**, `PERSISTED` |
| "I just cut the grass" | `PHONE_VOICE` / `log_voice` | matched **Mow lawn**, `PERSISTED` |
| "Wow" (ambient noise) | `PHONE_VOICE` / `log_voice` | **Needs review**, nothing logged |

The "Wow" case is the interesting one: a meaningless utterance was transcribed,
stored as raw words, handed to the model, and correctly refused — "Your words
are saved. Nothing was guessed." That is the designed behaviour for input the
model cannot honestly interpret, exercised by accident on real input.

Alternatives were populated with the recognizer's genuine competing
transcriptions (e.g. `["Added sanitizer to the hot tub","Acid sanitizer to the
hot tub",...]`), and typed captures still carry `null` for both speech columns.

### 4. Silence maps correctly to "nothing heard"

A listening session left running in a quiet room ended with the
recognition-failure card showing exactly the specified copy — "Couldn't make out
any words. Nothing was saved." — plus *Try again* and *Type instead*, and the
database confirmed **no new row was written**. The real engine's silence
behaviour maps onto `SpeechFailure.NOTHING_HEARD` as the adapter assumed.

### 5. The listening state reads correctly

Screenshot-verified: the "Listening…" line appears, the Recent list is visibly
dimmed, and Android's own green microphone indicator lights in the status bar
(independent confirmation that the mic is live and that it stops when the
session ends).

## Defects found and fixed

### D1 — Confidence was being recorded as a fabricated zero

Every voice capture stored `speech_confidence = 0.0`, including ones transcribed
perfectly. The platform on-device engine returns a score array of the correct
length with every entry `0.0`: it does not report confidence for on-device
recognition and emits zero as filler.

`RecognitionResults.confidenceAt` accepted it, because `0.0` is finite and
within `0..1`. The effect was that the ledger asserted the engine had *zero*
confidence in words it got exactly right — precisely the fabricated number the
"null means unknown, never substitute a value" rule exists to prevent. It had
simply been fabricated by the engine rather than by us.

**Fix:** a score of exactly zero is now read as "not reported" and stored as
null. The cost is that a genuine 0.0 is also read as unknown, which is harmless:
a transcript the engine has zero confidence in carries nothing a caller could
act on. Two unit tests added, using the real transcripts observed here.

This was invisible to every host-side test, because the scripted fake supplied
plausible confidences — the fake was more generous than the real engine.

### D2 — The stop-listening button looked like a broken image

The stop affordance was a bare filled square, which at that size on a dark
background reads as a missing asset rather than a control. `UX_VISUAL_SPEC` §4.1
specifies a "stop button with rings".

**Fix:** redrawn as a stop square inside a ring. Re-installed and
screenshot-verified on the device; it now reads unambiguously as a button.

## Outstanding

- **D1's fix is not yet confirmed against a live transcription.** It is covered
  by unit tests, and the three pre-fix rows still show `0.0`, but no new voice
  capture was made after the fix — the room was too quiet to trigger the
  recognizer. The next real spoken capture settles it: `speech_confidence`
  should be null, not `0.0`.
- **Three pre-fix rows keep the bogus `0.0`.** Deliberately left alone. Raw
  captures are immutable (ADR-007, AGENTS.md §4), and rewriting history to
  correct a metadata field is exactly what that rule forbids.
- **Accessibility claims remain partly unverified.** The live region and the
  Recent list's removal from the semantics tree are asserted by tests, and the
  dimming is now confirmed visually. Not verified: whether the "Listening…"
  announcement is genuinely useful to a screen reader as partial text changes,
  and where focus lands after *Type instead*. Both need a TalkBack pass.
- **Transcription quality is unmeasured.** The alternatives lists show the
  engine hesitating on ordinary phrases ("I just got the grass"), which is the
  input backlog item 6 needs. No conclusion drawn from three samples.

## Watch

Covered in `PROGRESS.md` and ADR-035: the OnePlus Watch 3 reports no on-device
recognition and `createOnDeviceSpeechRecognizer()` throws there. Nothing about
the phone result changes that.
