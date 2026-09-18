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
| SR1.1 | Corpus format, loader and cases | done | Spot-check. 48 cases, 3 catalogs. One known resolver gap ("Saturday morning"). Orchestrator bumped kotlinx-serialization to 1.11.0 |
| SR1.2 | Recording format, replay scorer, report, regression gate | done | Verifier pass. Gate: `core.testing.corpus.SemanticRegressionGateTest`; skips until recordings exist |
| SR1.3 | Device recorder and phone-session script | todo | Depends SR1.2. Verifier. Compile-only is acceptable |
| SR1.4 | Stand-in recorder (local model) | todo | Depends SR1.2. Verifier |
| SR1.5 | Install stand-in, first stand-in recording | todo | Orchestrator run. Ask owner right before installing |
| SR1.6 | Documentation | todo | Spot-check |
| SR1.7 | First device recording and baseline | todo | Needs the Pixel 10 Pro ~5-10 min; owner schedules |

## Session log

### 2026-09-18 — SR1.2 done

Replay runs each recorded answer through the real orchestrator (fresh
`InMemoryActivityRepository` seeded with the corpus's fixed ids via the new
`seedActivity(id=...)`), refusing stale recordings (context hash / candidate
ids / unknown case). Classes: NOT_RUN, CORRECT, SAFE_MISS, UNSAFE_MISS.
Reports go to `core-testing/build/reports/semantic-corpus/{device,standin}.md`.
Recorders MUST build entries with `CorpusInterpretationInput.forCase` +
`RecordingEntry.of`. Verifier's non-blocking note: a malformed recording's
parse error may quote recorded (synthetic) answer text in test output.

A Motorola moto g 2025 was found attached over USB during this run. It is
not a Gemini Nano test device; nothing was installed on it and SR1.3 is
compile-only.

### 2026-09-17 — SR1.1 done

Corpus at `core-testing/src/main/resources/semantic-corpus/corpus.json` (48
cases: synonym 18, near-neighbour 9, new-activity 4, temporal 11, ambiguity 5,
state 3). Corpus file forced to LF so its SHA-256 is stable across checkouts.
Known resolver gap: `TemporalResolver` has no weekday + part-of-day rule, so
"Mowed Saturday morning." is Unresolvable (should be 2026-09-12 APPROXIMATE);
the temporal test pins the gap set exactly. Candidate follow-up, not in SR1.
SR1.2 must map orchestrator `Rejected` to the NEEDS_REVIEW expectation and
treat a null in allowed states as "model left state empty".

Framework note: `scripts/sdlc/validate-state.mjs` flags unchanged `released`
items (SS1..AI1) as needing evidence files; transitions for SR1 are validated
per item and pass. Not fixed here.

### 2026-09-17 — SR1 scaffolded

Nothing was in flight; backlog items 6 and 7 are blocked (Step 6 / watch
hardware). Owner asked whether most testing could run on the PC because the
phone is rarely available for long sessions. Gemini Nano has no emulator or
desktop path, so the design splits the pipeline at the model: record the
model's answers (phone, short sessions; or a local stand-in model), replay
everything downstream on the JVM every build. Owner approved including a
local stand-in (Gemma via Ollama) on this laptop (RTX 4070 Laptop 8 GB, 16 GB
RAM), to be asked again right before the download.
