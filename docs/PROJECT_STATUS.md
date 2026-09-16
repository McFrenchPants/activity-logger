# Project Status

## Current phase

**Phase 1 — Implementation architecture validation and project scaffolding**

## Overall status

Documentation baseline created. Platform/API validation complete: SDK baselines, build toolchain, AI stack and speech API are pinned (ADR-021 through ADR-024), which unblocks the Gradle scaffold.

No application code has been written yet.

## Completed

- Product scope defined.
- MVP boundary defined.
- Phone/watch responsibility split defined.
- Gemini Nano selected as primary semantic interpreter.
- Local-first architecture selected.
- Room selected as authoritative MVP data store.
- Raw utterance preservation defined as a hard requirement.
- Correction/audit model defined.
- Cloud sync explicitly deferred.
- Semantic regression strategy defined.
- Visual design system, phone/watch mockups, and UI decisions approved (`docs/UX_VISUAL_SPEC.md`, ADR-019, ADR-020).
- Platform/API validation completed and dependency versions pinned (ADR-021 through ADR-024). The Pixel 10 Pro is confirmed *eligible* for Gemini Nano (AICore installed and enabled, bootloader locked, listed by Google on nano-v3); a real inference call is not proven until Step 4.

## Next milestone

**Phase 1 — Implementation architecture validation and project scaffolding**

Recommended tasks:

1. ~~Verify current official Android API/device support.~~ Done 2026-09-15.
2. ~~Select exact `minSdk`, `targetSdk`, Wear OS baseline.~~ Done — ADR-021.
3. ~~Verify Gemini Nano Prompt API + Structured Output availability on target phone.~~ Done — ADR-023. Library-level availability confirmed from Google's device list and the device's AICore install; a real inference call is still unproven until Step 4.
4. ~~Verify selected on-device speech recognition approach.~~ Done — ADR-024.
5. Create Gradle project structure. **← next**
6. Define Room schema version 1.
7. Define domain interfaces.
8. Define watch/phone protocol.
9. Build a minimal end-to-end technical spike:
   - hardcoded text input
   - Gemini Nano structured interpretation
   - Room persistence
10. Run seed semantic corpus.
11. Then add actual voice capture and Wear OS transport.

The technical spike is not a throwaway architecture. It is a vertical validation of the intended MVP stack.

## Known risks

- Gemini Nano capability varies by supported device/configuration.
- AICore/model preparation can temporarily be unavailable.
- Structured Output availability must be feature-detected.
- **ML Kit Structured Output is alpha** (`genai-schema-compiler:1.0.0-alpha1`) with no SLA and an explicit backward-compatibility warning. Contained to `core-ai` by ADR-023; a breaking change is expected to cost a one-module repair, and that containment must be maintained.
- **Kotlin cannot advance past 2.3.x** while Room needs KSP and KSP has no 2.4.x release (ADR-022). A routine dependency bump can break the build here.
- On-device speech API support must be verified for chosen phone baseline.
- On-device recognition is unverified on the OnePlus Watch 3 (hardware not yet in hand); the watch Listening screen design depends on it.
- Wear transport must handle disconnection/idempotency.
- Semantic catalog growth may eventually require candidate preselection improvements.
- Temporal language can create false precision if poorly modeled.

## Open implementation decisions

These should be resolved during architecture validation rather than guessed.

**Resolved 2026-09-15** by Phase 1 platform validation (backlog item 1):

- ~~exact Android min/target SDK~~ — ADR-021: phone `minSdk` 33, `targetSdk`/`compileSdk` 36.
- ~~exact ML Kit Prompt API version~~ — ADR-023: `genai-prompt:1.0.0-beta4`, with `genai-schema-compiler:1.0.0-alpha1` for Structured Output, contained in `core-ai`.
- ~~exact on-device speech API~~ — ADR-024: platform `SpeechRecognizer.createOnDeviceSpeechRecognizer()`.
- ~~exact Wear OS minimum version~~ — ADR-021: `app-wear` `minSdk` 34 (Wear OS 5), matching the OnePlus Watch 3.
- ~~whether the watch uses in-app on-device speech or system dictation UI~~ — the designed in-app Listening screen is *feasible*: the platform on-device recognizer is a Wear OS API and ADR-024 uses one implementation across phone and watch. Not yet confirmed on the actual OnePlus Watch 3, which has not arrived; verify a real on-device recognition round-trip on that hardware before Step 8 commits to the Listening screen.

**Still open:**

- UUID vs UUIDv7 library/implementation — not in scope of the platform-validation pass; decide during Step 2 (Room schema), since it affects primary-key generation.
- whether `IN_PROGRESS` ships in first functional milestone or immediately after completed-state capture — product decision, not a platform constraint.
- exact policy thresholds for auto-accept vs needs-review — needs the semantic seed corpus (Step 5) to calibrate against; guessing before there is measurable data would set them arbitrarily.

## Discrepancy log

None currently. (Resolved 2026-09-15: UX_SPEC §6 example showed a clock time for "this afternoon", contradicting ADR-018; example corrected.)

## Environment lessons

- Pixel 10 Pro (primary AI test device): bootloader confirmed locked on 2026-09-15 via `adb shell getprop ro.boot.flash.locked` (prints `1`). Gemini Nano / AICore APIs refuse to run on an unlocked bootloader, so check this first on any new test device before debugging AI availability failures.
- Pixel 7 Pro is a test-only device (Tensor G2, predates the Gemini Nano hardware baseline). Use it for the "AI unavailable" capability-detection path, not for validating interpretation.
- Pixel 10 Pro verified state (2026-09-15, via `adb` over Wi-Fi): Android 17 / API 37, build `CP2A.260805.005`, security patch 2026-08-05, arm64-v8a, 16 GB RAM, locale en-US. `com.google.android.aicore` installed and enabled at `prod_aicore_20260723.00_RC11`; `com.google.android.as` and `com.google.android.as.oss` also enabled. Google's device list places the Pixel 10 Pro on **Gemini Nano nano-v3** for the Prompt API.
- The on-device speech recognizer role (`android.app.role.SYSTEM_SPEECH_RECOGNIZER`) is held by `com.google.android.tts` on the Pixel 10 Pro. `pm query-services android.speech.RecognitionService` returns nothing useful — check the role holder via `dumpsys role` instead.
- `pm list features` does **not** advertise any AICore/GenAI feature flag. Do not capability-detect Gemini Nano with a system feature check; use the ML Kit `checkStatus()` / `isStructuredOutputFeatureAvailable()` APIs (ADR-023).
- Local Android SDK has platforms up to `android-36` and build-tools up to `35.0.1`, on JDK 21. AGP 9.4.0 will want newer build-tools; let the SDK manager fetch them on first sync rather than pinning a stale build-tools version.

When tooling/build/device issues occur repeatedly, record causes and working solutions here or in a dedicated troubleshooting section.

## Status update rule

Agents should update this document after:

- completing a milestone
- discovering a major blocker
- changing an architecture assumption
- identifying a significant compatibility constraint
- learning a repeatable workaround
