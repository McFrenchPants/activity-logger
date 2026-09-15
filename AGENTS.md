# AGENTS.md

This repository is intended to be developed substantially by coding agents such as Codex, Claude Code, or similar systems.

This file defines how agents must operate.

## 1. Read before coding

Before making architectural or implementation changes, read:

1. `README.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/REQUIREMENTS.md`
4. `docs/ARCHITECTURE.md`
5. `docs/DATA_MODEL.md`
6. `docs/AI_INTERPRETATION_SPEC.md`
7. `docs/DECISIONS.md`
8. `docs/PROJECT_STATUS.md`

For Wear OS work, also read `docs/WATCH_SPEC.md`.

For semantic or AI changes, also read `docs/TEST_STRATEGY.md`.

## 2. Product boundary

Activity Ledger is an **activity logger**, not a task manager.

Do not add planning features simply because they are conventional in productivity software.

Without an approved design change, do not add:

- due dates
- project boards
- task priorities
- recurring task scheduling
- calendar planning
- productivity scores
- cloud accounts
- team features
- push reminder systems

## 3. Architectural invariants

The following are hard constraints unless superseded by an explicit decision record:

- The phone owns the authoritative activity database.
- The phone performs semantic interpretation.
- Wear OS does not run semantic inference.
- MVP is local-first.
- MVP does not require an Internet connection for normal capture.
- Gemini Nano is the primary semantic interpreter.
- AI output must be structured and validated before persistence.
- Raw user input must be preserved.
- Raw capture text must never be silently rewritten or overwritten.
- Corrections must be represented as auditable changes.
- Canonical activity identity is separate from individual activity occurrences.
- Speech recognition and semantic interpretation are separate components.
- Natural-language queries must resolve to deterministic database operations wherever practical.

## 4. Data integrity

Never discard the user's original dictated text.

Every captured utterance must retain:

- immutable raw text
- capture timestamp
- source device
- speech recognition metadata when available
- semantic interpretation metadata
- chosen canonical activity
- any later correction history

A correction changes the interpreted record, not the original evidence.

Do not implement database migrations that destroy raw capture history.

## 5. AI behavior

Do not treat model output as trusted application state.

All model output must pass through:

1. structured-output decoding
2. schema validation
3. deterministic business-rule validation
4. activity-resolution policy
5. persistence layer

Do not store arbitrary generated prose as the canonical record.

Do not allow the model to fabricate timestamps, activity IDs, or database identifiers outside the allowed contract.

## 6. Semantic regression requirement

Any user-visible semantic bug must become a regression test.

Examples:

- "cut the grass" incorrectly creates a new activity instead of matching "Mow lawn"
- "edged the lawn" incorrectly maps to "Mow lawn"
- "changed the furnace filter yesterday" records today instead of yesterday

When fixing semantic behavior:

1. add a failing corpus case
2. reproduce the failure
3. implement the fix
4. verify the corpus passes
5. document material prompt or policy changes

## 7. Self-improvement log

Agents must learn from failures instead of repeatedly retrying them.

If a tool, build, emulator, dependency, Gradle configuration, device API, or environment issue requires multiple attempts:

- identify the cause
- document the working solution
- document commands that failed if useful
- update appropriate project documentation
- prevent future agents from repeating the same failed approach

Use `docs/PROJECT_STATUS.md` for current blockers and implementation notes.

For durable architecture decisions, update `docs/DECISIONS.md`.

## 8. Design discrepancies

If implementation constraints conflict with the design:

1. do not silently change product behavior
2. record the discrepancy
3. explain the technical reason
4. propose the smallest viable resolution
5. add or update an ADR in `docs/DECISIONS.md` if architectural
6. continue only where the existing design still allows safe progress

## 9. Dependency discipline

Prefer first-party Android/Google libraries where they directly satisfy the requirement.

Do not introduce:

- cloud platforms
- analytics SDKs
- ad SDKs
- remote AI APIs
- generic networking stacks
- unnecessary dependency injection frameworks

unless the feature requires them and the decision is documented.

Pin dependency versions.

Record device/API compatibility assumptions.

## 10. Implementation quality

Use:

- Kotlin idioms
- coroutines / Flow where appropriate
- clear interfaces around speech, inference, transport, and persistence
- dependency inversion for testability
- deterministic parsing/validation around AI results
- Room migrations
- instrumented tests for Android-specific behavior
- unit tests for domain logic

Avoid oversized ViewModels containing business logic.

## 11. Privacy

Do not transmit captured activity text off-device in MVP.

Do not add telemetry containing raw utterances.

If diagnostics are added, they must not expose user content by default.

## 12. Project status

Update `docs/PROJECT_STATUS.md` at meaningful milestones.

The status document should always answer:

- What phase are we in?
- What has been completed?
- What is being worked on?
- What is blocked?
- What is the next milestone?
- What known design/implementation discrepancies exist?
- What technical lessons should future agents know?

## 13. Definition of done for a feature

A feature is not complete until:

- implementation is complete
- unit/integration tests pass
- semantic corpus is updated when applicable
- documentation matches behavior
- relevant acceptance criteria are satisfied
- project status is updated
