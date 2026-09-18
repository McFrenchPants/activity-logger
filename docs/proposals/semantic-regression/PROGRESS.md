# Progress — Semantic regression corpus

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — shape approved by owner 2026-09-17.
Implementation plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/semantic-regression`, off `main` at 4076117.

This work item is **sdlc-tracked** (`SR1` in `.sdlc/state.json`). Verification
tier: spec §6 (verifier for SR1.2-SR1.4).

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| SR1.1 | Corpus format, loader and cases | todo | |
| SR1.2 | Recording format, replay scorer, report, regression gate | todo | Depends SR1.1. Verifier |
| SR1.3 | Device recorder and phone-session script | todo | Depends SR1.2. Verifier. Compile-only is acceptable |
| SR1.4 | Stand-in recorder (local model) | todo | Depends SR1.2. Verifier |
| SR1.5 | Install stand-in, first stand-in recording | todo | Orchestrator run. Ask owner right before installing |
| SR1.6 | Documentation | todo | Spot-check |
| SR1.7 | First device recording and baseline | todo | Needs the Pixel 10 Pro ~5-10 min; owner schedules |

## Session log

### 2026-09-17 — SR1 scaffolded

Nothing was in flight; backlog items 6 and 7 are blocked (Step 6 / watch
hardware). Owner asked whether most testing could run on the PC because the
phone is rarely available for long sessions. Gemini Nano has no emulator or
desktop path, so the design splits the pipeline at the model: record the
model's answers (phone, short sessions; or a local stand-in model), replay
everything downstream on the JVM every build. Owner approved including a
local stand-in (Gemma via Ollama) on this laptop (RTX 4070 Laptop 8 GB, 16 GB
RAM), to be asked again right before the download.
