# Project Status

## Current phase

**Phase 1 — Implementation architecture validation and project scaffolding**

## Overall status

Documentation baseline created. Platform/API validation complete: SDK baselines, build toolchain, AI stack and speech API are pinned (ADR-021 through ADR-024), The multi-module Gradle scaffold is built and merged (empty phone and watch apps plus the core modules; see `docs/proposals/gradle-scaffold/BUILD_NOTES.md`).

Room schema v1 (backlog item 3, `docs/proposals/room-schema/`) is implemented, tested and merged to `main` (2026-09-16) in `core-data`; `docs/DATA_MODEL.md` describes it and ADR-026 records the row-identifier decision. The domain services (work item DS1, `docs/proposals/domain-services/`) are implemented, tested and merged to `main` (2026-09-17): interpreter and repository contracts, candidate selection, deterministic time resolution (ADR-028), the two-outcome validation policy (ADR-027), capture processing, corrections and review resolution.

The AI vertical slice (Step 4, work item AI1, `docs/proposals/ai-vertical-slice/`) is built and merged to `main` (2026-09-17): the Gemini Nano interpreter and capability check in `core-ai`, wired into the phone app through `CapturePipeline` (ARCHITECTURE.md §5, §13). It has passed **once** on a real device (Pixel 10 Pro, 2026-09-17): the hard-coded sentence "I cut the grass yesterday." was matched to an existing "Mow lawn" activity, dated to the previous day at date-only precision, auto-accepted and stored with its raw text intact (`docs/proposals/ai-vertical-slice/RESULTS.md`). That is one sentence, not evidence of general accuracy; accuracy is what the semantic regression corpus (Step 5) is for. Decisions from the device run: ADR-025 amended (the phone keeps `ACCESS_NETWORK_STATE`, still no `INTERNET`), ADR-029 (on-device AI only while the app is in the foreground), ADR-030 (one-shot interpretation, no repair loop), ADR-031 (model download only on explicit request), ADR-032 (no DI framework). Capture by voice is not built yet.

The semantic regression corpus (Step 5, work item SR1) is built and merged to `main` (2026-09-18): 48 synthetic cases, JVM replay of recorded model answers through the real pipeline, the regression gate, and two recorders -- one on the phone (official) and one against a local stand-in model (iteration only). See `docs/SEMANTIC_CORPUS.md` and ADR-033. First official device recording (Pixel 10 Pro, 2026-09-18), re-scored after the owner accepted three near matches: 35 correct, 6 safe misses, 7 unsafe misses of 48 -- every unsafe miss at HIGH confidence, so ADR-027's HIGH -> auto-accept policy is not safe as measured (`docs/proposals/semantic-regression/RESULTS.md`). The baseline holds those 35 cases; the gate fails if any regresses.

**The app can now be used by hand (typing only).** Work item UI1 (backlog 10, `docs/proposals/typed-capture/`, merged to `main` 2026-09-19) adds the first screens: Log (type what you did, see a Saved / Needs review / Not categorized card, with Undo and Change activity on a Saved card, and a Recent list) and History (newest first, filters *All* / *Needs review* / *Not categorized*, resolving a waiting entry from its row). Passed a hands-on device check on the Pixel 10 Pro on 2026-09-19: auto-save, needs-review with suggestions, create activity, Undo, Change activity, History filters and resolving from History, light and dark theme, 200% text size. One fix came out of it (the keyboard now closes after *Log it* so the result card is visible). Interpretation took roughly 2-5 s per entry on the device. Not built yet: voice, Ask, Settings/model download, activity detail, editing an entry after the Undo window.

**Update 2026-10-04: the app is now a working phone + watch logger on subject + action tags.** Voice capture on the phone (VC1), spoken capture on the watch with a durable outbox and phone receiver (WD1, WC1; watch speech works, internet use by speech is allowed, ADR-035/036), and time handling ("just" means now, TM1) are on `main`. The AI no longer picks from a list of activities: it only extracts the user's own words (subject, action, time, duration), program logic matches them to subject and action tags and decides save / ask / review (work item TG1, ADR-038..050, `docs/proposals/subject-action-tagging/`). Schema v2 (ADR-040) stores a subject + action pair and duration; corrections teach the app the user's wording (ADR-041); tags can be renamed and merged (ADR-042, Tags screen, ADR-048). The Log screen shows a Saved card with subject, action, duration and time, or one "Check this" card for close matches and unclear entries (ADR-046); History rows and waiting entries use the same pieces (ADR-047); watch entries that need a question wait on the phone (ADR-045). Device-checked on the Pixel 10 Pro and the OnePlus Watch 3: on the tag corpus 64 of 72 correct, 6 safe questions, 0 wrong silent saves (before: 14 wrong), and 11 of 12 real watch entries correct. A repair for object nouns that the AI puts in the subject ("hot tub filter" / "change") is in the decision policy (ADR-049); a prompt rewrite for the same problem was measured and not adopted. The old single-activity screens and wiring are removed from the phone app (ADR-050); the older activity-matching classes stay in `core-domain` for the first semantic corpus gate. Not built yet: the Ask / lookup feature (TG Stage 4), Settings and model download, activity detail, finishing a waiting entry from History at the time the user said (it uses the capture time), and the older semantic corpus is not re-recorded for the new model.

Two small fixes from the corpus follow-ups (work item FX1, backlog item 14) are merged to `main` (2026-09-19): a refusal from the on-device model because it is busy is now retryable and the phone pipeline retries it twice (2 s, 4 s) before giving up (`BusyRetryInterpreter`, ADR-030 amended; the phone now uses the same retry through `BusyRetryExtractor`, ADR-050), and `TemporalResolver` resolves weekday + part-of-day phrases such as "Saturday morning" (ADR-028). The corpus has no known resolver gaps left and the regression baseline is 36 of 48 cases; the committed device recording predates that corpus edit, so it is flagged as recorded against a different corpus until the next Pixel 10 Pro recording.

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
- Platform/API validation completed and dependency versions pinned (ADR-021 through ADR-024). The Pixel 10 Pro is confirmed *eligible* for Gemini Nano (AICore installed and enabled, bootloader locked, listed by Google on nano-v3).
- AI vertical slice (Step 4) built, and passed once on the Pixel 10 Pro on 2026-09-17: a real on-device structured interpretation call, through validation, into Room. One sentence only; see `docs/proposals/ai-vertical-slice/RESULTS.md`.

## Next milestone

**Phase 1 — Implementation architecture validation and project scaffolding**

Recommended tasks:

1. ~~Verify current official Android API/device support.~~ Done 2026-09-15.
2. ~~Select exact `minSdk`, `targetSdk`, Wear OS baseline.~~ Done — ADR-021.
3. ~~Verify Gemini Nano Prompt API + Structured Output availability on target phone.~~ Done — ADR-023. Library-level availability confirmed from Google's device list and the device's AICore install; a real structured inference call succeeded on 2026-09-17 (item 9).
4. ~~Verify selected on-device speech recognition approach.~~ Done — ADR-024.
5. ~~Create Gradle project structure.~~ Done 2026-09-16 — work item SS1.
6. ~~Define Room schema version 1.~~ Done 2026-09-16 — work item DB1 (see `docs/DATA_MODEL.md`, ADR-026).
7. ~~Define domain interfaces.~~ Done 2026-09-17 — work item DS1 (merged to `main` 2026-09-17; see `docs/ARCHITECTURE.md` §5, ADR-027, ADR-028).
8. ~~Define watch/phone protocol.~~ Done — work items WD1 and WC1 (`core-wear-protocol`, ADR-037); spoken captures reach the phone and are acked.
9. ~~Build a minimal end-to-end technical spike:~~ Built 2026-09-17 — work item AI1 (Step 4), merged to `main` 2026-09-17, passed once on the Pixel 10 Pro (`docs/proposals/ai-vertical-slice/RESULTS.md`):
   - hardcoded text input
   - Gemini Nano structured interpretation
   - Room persistence
10. ~~Run seed semantic corpus~~ Done 2026-09-18 -- work item SR1, merged to `main` (ADR-033). Core synonym and near-neighbour cases are measurable; first device baseline 35/48 correct. Follow-ups: BACKLOG item 13 (wrong confident matches) still open; item 14 (AICore BUSY retry, weekday + part-of-day dates) done 2026-09-19.
11. ~~Then add actual voice capture and Wear OS transport.~~ Done — VC1 (phone voice), WC1 (watch). Next: TG Stage 4 (lookup), then settings / model download.

The technical spike is not a throwaway architecture. It is a vertical validation of the intended MVP stack.

## Known risks

- Gemini Nano capability varies by supported device/configuration.
- AICore/model preparation can temporarily be unavailable.
- Structured Output availability must be feature-detected.
- **On-device AI calls are refused unless the phone app is the top foreground app** (ADR-029). Background interpretation is impossible; watch captures (Step 8) arriving in the background must wait for the app to be opened. A refused call currently lands in review as `INTERPRETER_FAILED` rather than being retried, so callers must check they are in the foreground.
- **The model download path is not well understood.** On the Pixel 10 Pro the download only completed after `ACCESS_NETWORK_STATE` was restored, but whether that caused it is unproven (ADR-025 amendment). The user-facing download flow (Step 7) should be tested on a device where the model is not yet installed.
- Interpretation takes seconds, not milliseconds: about 6.5 s for one interpretation on the Pixel 10 Pro in the single device run. The Step 7 UX needs to account for that; it has not been measured beyond one call.
- **ML Kit Structured Output is alpha** (`genai-schema-compiler:1.0.0-alpha1`) with no SLA and an explicit backward-compatibility warning. Contained to `core-ai` by ADR-023; a breaking change is expected to cost a one-module repair, and that containment must be maintained.
- **Kotlin cannot advance past 2.3.x** while Room needs KSP and KSP has no 2.4.x release (ADR-022). A routine dependency bump can break the build here.
- On-device speech API support must be verified for chosen phone baseline.
- Watch speech was verified on the OnePlus Watch 3 (WD1.3, WC1.6; ADR-035/036). The speech engine can mishear short phrases ("I just" heard as "Adjust" on 2026-10-04); the user corrects those on the phone.
- The on-device model's wording varies (e.g. it can put an object word into the subject); the decision policy repairs known cases and asks when unsure, but each new pattern needs a corpus case and a device re-record.
- Wear transport must handle disconnection/idempotency.
- Semantic catalog growth may eventually require candidate preselection improvements.
- Temporal language can create false precision if poorly modeled.

## Open implementation decisions

These should be resolved during architecture validation rather than guessed.

**Resolved 2026-09-15** by Phase 1 platform validation (backlog item 1):

- ~~exact Android min/target SDK~~ — ADR-021: phone `minSdk` 33, `targetSdk` 36, `compileSdk` 37 (amended from 36).
- ~~exact ML Kit Prompt API version~~ — ADR-023: `genai-prompt:1.0.0-beta4`, with `genai-schema-compiler:1.0.0-alpha1` for Structured Output, contained in `core-ai`.
- ~~exact on-device speech API~~ — ADR-024: platform `SpeechRecognizer.createOnDeviceSpeechRecognizer()`.
- ~~exact Wear OS minimum version~~ — ADR-021: `app-wear` `minSdk` 34 (Wear OS 5), matching the OnePlus Watch 3.
- ~~whether the watch uses in-app on-device speech or system dictation UI~~ — the designed in-app Listening screen is *feasible*: the platform on-device recognizer is a Wear OS API and ADR-024 uses one implementation across phone and watch. Not yet confirmed on the actual OnePlus Watch 3, which has not arrived; verify a real on-device recognition round-trip on that hardware before Step 8 commits to the Listening screen.

**Resolved 2026-09-16** by the Room schema v1 work (backlog item 3):

- ~~UUID vs UUIDv7 library/implementation~~ — ADR-026: app-generated UUIDv7 text via Kotlin stdlib `kotlin.uuid.Uuid`, behind an `IdFactory` seam; UUIDv4 via `java.util.UUID` is the fallback, no third-party library.

**Still open:**

- whether `IN_PROGRESS` ships in first functional milestone or immediately after completed-state capture — product decision, not a platform constraint.
- calibration of the implemented confidence policy (ADR-027) — auto-accept currently requires confidence band `HIGH` and speech confidence of at least 0.5 (a single constant); both are provisional and need the semantic seed corpus (Step 5) and real recognizer output (Step 6) to calibrate against.
- ~~hide/restore of an occurrence~~ — hide done in UI1 (`hideOccurrence`), a visibility change with no correction row (ADR-034). Restore is not built.
- ~~listing captures waiting for review~~ — done in UI1 (`loadHistory()` returns captures without an occurrence with their processing state).
- re-interpretation of an already accepted capture (`CorrectionSource.REINTERPRETATION`) — no code path exists yet; `recordOutcome` and `process` refuse or skip captures that already have an occurrence.

## Discrepancy log

None currently. (Resolved 2026-09-15: UX_SPEC §6 example showed a clock time for "this afternoon", contradicting ADR-018; example corrected.)

## Environment lessons

- Pixel 10 Pro (primary AI test device): bootloader confirmed locked on 2026-09-15 via `adb shell getprop ro.boot.flash.locked` (prints `1`). Gemini Nano / AICore APIs refuse to run on an unlocked bootloader, so check this first on any new test device before debugging AI availability failures.
- Pixel 7 Pro is a test-only device (Tensor G2, predates the Gemini Nano hardware baseline). Use it for the "AI unavailable" capability-detection path, not for validating interpretation.
- Pixel 10 Pro verified state (2026-09-15, via `adb` over Wi-Fi): Android 17 / API 37, build `CP2A.260805.005`, security patch 2026-08-05, arm64-v8a, 16 GB RAM, locale en-US. `com.google.android.aicore` installed and enabled at `prod_aicore_20260723.00_RC11`; `com.google.android.as` and `com.google.android.as.oss` also enabled. Google's device list places the Pixel 10 Pro on **Gemini Nano nano-v3** for the Prompt API.
- The on-device speech recognizer role (`android.app.role.SYSTEM_SPEECH_RECOGNIZER`) is held by `com.google.android.tts` on the Pixel 10 Pro. `pm query-services android.speech.RecognitionService` returns nothing useful — check the role holder via `dumpsys role` instead.
- On-device AI instrumented tests must hold an app Activity in the foreground (e.g. `ActivityScenario.launch(MainActivity::class.java)`) around the interpretation call; without one the call fails in ~250 ms with `INTERPRETER_FAILED` (ADR-029). Wi-Fi ADB can drop during long model downloads, so a download attempt over Wi-Fi debugging may be inconclusive.
- `pm list features` does **not** advertise any AICore/GenAI feature flag. Do not capability-detect Gemini Nano with a system feature check; use the ML Kit `checkStatus()` / `isStructuredOutputFeatureAvailable()` APIs (ADR-023).
- Local Android SDK has platforms up to `android-36` and build-tools up to `35.0.1`, on JDK 21. AGP 9.4.0 will want newer build-tools; let the SDK manager fetch them on first sync rather than pinning a stale build-tools version.

- Driving the phone app over adb for a device check: `uiautomator dump` + `input tap/text` works, but each dump takes ~1-2 s, so the 8 s Undo window needs a tight poll-then-tap loop. `input keyevent 111` (Escape) does not reliably close Gboard; `keyevent 4` (Back) does. Always restore any display setting you change (`cmd uimode night`, `settings put system font_scale`) and only change them with the owner's OK.

When tooling/build/device issues occur repeatedly, record causes and working solutions here or in a dedicated troubleshooting section.

## Status update rule

Agents should update this document after:

- completing a milestone
- discovering a major blocker
- changing an architecture assumption
- identifying a significant compatibility constraint
- learning a repeatable workaround
