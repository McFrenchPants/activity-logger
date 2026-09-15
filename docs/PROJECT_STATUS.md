# Project Status

## Current phase

**Phase 0 — Product and architecture definition**

## Overall status

Documentation baseline created.

Implementation has not yet been started.

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

## Next milestone

**Phase 1 — Implementation architecture validation and project scaffolding**

Recommended tasks:

1. Verify current official Android API/device support.
2. Select exact `minSdk`, `targetSdk`, Wear OS baseline.
3. Verify Gemini Nano Prompt API + Structured Output availability on target phone.
4. Verify selected on-device speech recognition approach.
5. Create Gradle project structure.
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
- On-device speech API support must be verified for chosen phone baseline.
- Wear transport must handle disconnection/idempotency.
- Semantic catalog growth may eventually require candidate preselection improvements.
- Temporal language can create false precision if poorly modeled.

## Open implementation decisions

These should be resolved during architecture validation rather than guessed:

- exact Android min/target SDK
- exact ML Kit Prompt API version
- exact on-device speech API
- exact Wear OS minimum version
- UUID vs UUIDv7 library/implementation
- whether `IN_PROGRESS` ships in first functional milestone or immediately after completed-state capture
- exact policy thresholds for auto-accept vs needs-review
- whether the watch uses in-app on-device speech (designed Listening screen) or system dictation UI

## Discrepancy log

None currently. (Resolved 2026-09-15: UX_SPEC §6 example showed a clock time for "this afternoon", contradicting ADR-018; example corrected.)

## Environment lessons

None yet.

When tooling/build/device issues occur repeatedly, record causes and working solutions here or in a dedicated troubleshooting section.

## Status update rule

Agents should update this document after:

- completing a milestone
- discovering a major blocker
- changing an architecture assumption
- identifying a significant compatibility constraint
- learning a repeatable workaround
