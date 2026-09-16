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

## Session log

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
