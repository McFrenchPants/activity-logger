# Progress — AI vertical slice

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — approved by owner 2026-09-17.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/ai-vertical-slice`, off `main` at a949941.

This work item is **sdlc-tracked** (`AI1` in `.sdlc/state.json`). Verification
tier: spec §7 (verifier for every non-doc task).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| AI1.1 | Structured output type and decode mapping | todo | Deletes the core-ai scaffold placeholder |
| AI1.2 | Prompt: system instruction, builder, versioning | todo | Depends on AI1.1 |
| AI1.3 | Capability detection and model client lifecycle | todo | Owner decision: never download implicitly |
| AI1.4 | GeminiNanoActivityInterpreter | todo | Depends on AI1.1-AI1.3 |
| AI1.5 | Phone wiring and the on-device vertical slice | todo | Adds androidTest infrastructure; device run needs the Pixel 10 Pro |
| AI1.6 | Documentation | todo | Must not claim a device run that did not happen |

## Open items (carry forward)

- The real device run (Pixel 10 Pro, USB) is the actual proof of this work
  item and needs the owner present. Until it happens, AI1.5 is code that
  builds, not behaviour that is verified.
- `includeSchemaInPrompt` on vs off, and the exact temperature/topK/seed, can
  only be settled against a real run (spec §6).

## Session log

### 2026-09-17 — AI1 scaffolded

Nothing was in flight at the start of the run: `feature/domain-services` had
been merged into `main` by hand, so the run began with DS1's post-merge
bookkeeping (state `released`, backlog item 8 `done`, PROJECT_STATUS wording),
committed to `main` as a949941.

Backlog item 4 was the only unblocked actionable item; the owner chose it.
Design spec written and approved, with one owner decision recorded: when the
on-device model is downloadable but not installed, the app reports AI as
unavailable and never starts the download by itself (spec §5.4).

The ML Kit GenAI API surface in spec §5.1 was extracted from the pinned
artifacts in the local Gradle cache (`genai-prompt:1.0.0-beta4`,
`genai-common:1.0.0-beta4`, `genai-schema:1.0.0-alpha1`) with `javap`, rather
than recalled — the library is beta/alpha and its shape is the one thing task
packets most need to be right about.

Next: AI1.1.
