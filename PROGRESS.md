# Progress

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Phased/proposal-folder work tracks its own `docs/proposals/<slug>/PROGRESS.md`
table and session log. This file is for lightweight, not-yet-proposal-scoped
work only (the "Post-Launch"-style table referenced by `/continue-development`).

For narrative project status (phase, completed milestones, known risks, open
implementation decisions), see `docs/PROJECT_STATUS.md` — that document
predates this framework and remains the source of truth for that kind of
status; this file only tracks discrete task rows.

## Post-Launch

| Task ID | Description | Status | Notes |
|---|---|---|---|
| PV1 | Platform/API validation and version pinning (backlog item 1) | done | ADR-021..024. Desk research + read-only `adb` checks on the Pixel 10 Pro. Library-level Gemini Nano availability confirmed; a real inference call remains unproven until Step 4. Watch speech unverified (hardware not yet in hand) — backlog item 7. |
| FX1.1 | AICore BUSY treated as "try again" + short wait-and-retry in the phone pipeline (backlog item 14b) | done | Verifier pass. Busy refusal now RETRYABLE; `BusyRetryInterpreter` waits 2 s then 4 s. ADR-030 amended. Not yet seen on a device. |
| FX1.2 | Weekday + part-of-day dates ("Saturday morning") in `TemporalResolver` (backlog item 14c) | done | Spot-check. ADR-028 rule added; corpus gap cleared; baseline now 36 cases. Device recording flagged "different corpus" (hash changed) until the next Pixel re-record. |

## Session log

### 2026-09-19 — FX1: two small fixes (backlog item 14)

Nothing was in flight; owner picked item 14. Small tier (no design spec),
sdlc-tracked as FX1 on `feature/small-fixes`, rows above.

- FX1.1 (verifier pass): AICore `GenAiException` BUSY now maps to RETRYABLE in
  `core-ai`; `CapturePipeline.create` wraps the Gemini interpreter in
  `BusyRetryInterpreter` (RETRYABLE only, waits 2 s then 4 s, never re-asks
  after an answer). ADR-030 amended. The recorder's KDoc updated to match.
  Not yet exercised on a device: on a busy phone a capture can now take up to
  ~6 s longer before its card appears.
- FX1.2 (spot-check): `TemporalResolver` rule 10a, weekday + part-of-day
  (ADR-028). Corpus `knownResolverGap` removed; `baseline.json` gains
  `time-mowed-saturday-morning` (replays CORRECT from the real 2026-09-18
  device answer: 36 correct, 5 safe, 7 unsafe of 48). Because corpus.json's
  bytes changed, device.md flags the recording as made against a different
  corpus until the next Pixel 10 Pro re-record; replay and gate still pass.

Full `./gradlew test testDebugUnitTest assembleDebug` green.

Owner asked for the merge: `feature/small-fixes` merged into `main` with
`--no-ff` (a904b97); full build and tests green on `main` afterwards. FX1 and
its two tasks -> lifecycle `released`. Not pushed.

### 2026-09-15 — PV1: platform/API validation

Backlog item 1 completed on `feature/platform-validation`. Nothing was in
flight at the start of the run; item 1 was the only unblocked backlog entry
(items 2-4 chain off it).

Research was done directly rather than delegated, per `/continue-development`'s
handling of research items. Versions were verified against Google Maven and
Maven Central rather than recalled, and device facts came from read-only `adb`
queries against the connected Pixel 10 Pro.

Decisions recorded: ADR-021 (phone `minSdk` 33 / watch `minSdk` 34 /
`target`+`compileSdk` 36), ADR-022 (Kotlin 2.3.21 + KSP 2.3.12, AGP 9.4.0,
Room 2.8.5, Compose BOM 2026.09.00), ADR-023 (`genai-prompt:1.0.0-beta4` plus
alpha `genai-schema-compiler:1.0.0-alpha1`, contained in `core-ai`), ADR-024
(platform on-device `SpeechRecognizer`).

Two constraints future sessions should not trip over:

- **Kotlin cannot go past 2.3.x.** KSP has no 2.4.x release and Room needs KSP.
  A routine "bump to latest Kotlin" breaks the build.
- **ML Kit Structured Output is alpha**, no SLA, breaking changes expected.
  ADR-023 contains it to `core-ai`; that containment is the mitigation and
  needs to hold.

Owner decisions during the run: platform `SpeechRecognizer` over ML Kit
Advanced mode; adopt Structured Output but wall it off.

New follow-ups recorded as backlog items 6 (measure ML Kit Advanced-mode speech
at Step 6) and 7 (verify on-device speech + Data Layer on the OnePlus Watch 3
when it arrives, before Step 8).

Next: backlog item 2, the Gradle scaffold — now `ready`, and the pinned
versions above are its input.
