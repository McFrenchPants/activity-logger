# Design spec — Semantic regression corpus (work item SR1)

Backlog item 9. Build-guide Step 5 (`docs/IMPLEMENTATION_HANDOFF.md`).
Branch: `feature/semantic-regression`, off `main` at 4076117.

**Approval.** The owner chose this work and approved its shape on 2026-09-17,
in plain-English form: every test runs on the PC; the phone is used only for
short (~5 minute) sessions that record the real model's answers; and a local
open-source stand-in model is installed on the PC for fast prompt iteration,
never counted as the official result. This spec writes that decision down
and adds no further product decision, so it was not sent back for a second
sign-off. The owner is to be asked again immediately before anything is
downloaded or installed on their machine (R6).

## 1. Problem

The interpreter has passed exactly one sentence on real hardware
(`docs/proposals/ai-vertical-slice/RESULTS.md`). REQUIREMENTS TST-001..004 and
AGENTS.md §6 require a machine-readable semantic corpus, and the build guide
says not to move on until synonym and near-neighbour behaviour is measurable.
Three provisional choices are waiting on this measurement: the auto-accept
confidence policy (ADR-027), schema-in-prompt (`INCLUDE_SCHEMA_IN_PROMPT`),
and one-shot decoding (ADR-030).

The constraint that shapes everything: Gemini Nano runs only on the phone,
only in the foreground (ADR-029), and the owner's phone is rarely available
for long sessions. There is no emulator path for the ML Kit Prompt API.

## 2. Goals

- G1. A machine-readable corpus covering TEST_STRATEGY §4 (seed synonyms and
  near-neighbours), §5 (temporal) and §6 (ambiguity), plus the three
  AGENTS.md §6 examples, with readable documentation.
- G2. Split the pipeline at the one non-deterministic step. The model's answer
  for each case is *recorded* once; everything downstream of it (candidate
  selection, validation, the confidence policy, temporal resolution, the
  capture outcome) is *replayed* on the PC's JVM, every build, with no device.
- G3. A short, scripted phone session that runs the whole corpus through the
  production interpreter on the Pixel 10 Pro and brings back a recording.
- G4. A local stand-in model on the PC, driven with the same prompt, producing
  a recording in the same format, so prompt changes can be iterated on
  without the phone.
- G5. A scored report: per category, per case, and the outcome split that
  matters to the user (below), plus confidence band vs correctness so ADR-027
  can be calibrated.
- G6. A regression gate: a case that passed in the accepted device baseline
  and fails on replay fails the build.

## 3. Non-goals

- The query corpus (TEST_STRATEGY §7). It needs the Step 9 query interpreter.
- Changing the prompt, the confidence policy or any provisional choice. This
  work item *measures*; tuning is follow-up work driven by the measurements.
- Speech. The corpus is typed text; recogniser noise is Step 6 / backlog 6.
- Any UI.
- Running the corpus against real user captures. The corpus is synthetic,
  authored text and must stay that way (AGENTS.md #11).

## 4. Requirements

- R1. **Corpus file.** JSON, one file, each case carrying TEST_STRATEGY §3's
  fields: id, category, the canonical activity catalog it runs against, raw
  text, capture time and zone, expected resolution, expected activity (by
  catalog id) or expected new name, expected state, expected temporal
  expression (with allowed alternatives) and its resolved local date +
  precision, must-not-match activities, and expected outcome. Catalogs are
  named, reusable fixtures with fixed ids, so recordings are stable.
- R2. **Outcome classes.** Every scored case lands in exactly one of:
  - *correct* — the outcome and every asserted field match;
  - *safe miss* — the capture went to review (or was left unresolved) when it
    should have been auto-accepted. The user is asked; nothing wrong is
    recorded;
  - *unsafe miss* — something wrong would be saved without asking: a wrong or
    must-not-match activity auto-accepted, a wrong date auto-accepted, or an
    ambiguous sentence auto-accepted.
  Unsafe misses are the headline number; they are what corrupts history.
- R3. **Replay runs the real domain path.** A recorded answer is fed through a
  fake interpreter into the real `CaptureInterpretationOrchestrator` with
  the real selector, resolver and validator. No re-implementation of any
  domain rule in the test harness.
- R4. **Deterministic temporal cases hard-fail.** Given an expected temporal
  expression, `TemporalResolver`'s date and precision are asserted exactly,
  with no model involved.
- R5. **Device recorder.** An instrumented test in `app-phone` that runs every
  case through `GeminiNanoActivityInterpreter`, holding the app in the
  foreground (ADR-029), skipping (not failing) when the model is not READY,
  and writing a recording file; plus one script that runs it over Wi-Fi ADB
  and copies the recording into the repository. Never downloads the model.
- R6. **Stand-in recorder.** A host-side runner that sends the *same* system
  instruction and prompt text (`buildInterpretationPrompt`) to a model served
  locally on 127.0.0.1, with greedy/fixed-seed settings mirroring the
  interpreter's and the response constrained to the same schema, and writes a
  recording in the same format. Opt-in only: skipped unless explicitly
  requested, so ordinary builds never need it. Installing the runtime and
  model requires the owner's go-ahead at that moment.
- R7. **Recordings carry provenance**: source (device / stand-in), model
  label, device model where known, prompt / schema / interpreter versions,
  a hash of the corpus, date, and per case the offered candidate ids,
  context hash, the answer or failure kind, and latency.
- R8. **Honest labelling.** Stand-in results are always labelled as such in
  the report and never satisfy the regression gate or count as the official
  measurement.
- R9. **Privacy.** Nothing here logs corpus text or model output to logcat or
  the console beyond the report; the stand-in talks only to loopback; the
  phone app gains no permission.

## 5. Constraints

- `core-ai` is the only module that may import ML Kit (ADR-023). The stand-in
  reaches the prompt builder from `core-ai`'s own host test source set.
- Kotlin stays on 2.3.x (ADR-022). Any new library (a JSON library for the
  corpus) goes in the version catalog, versions verified, not recalled.
- No production code behaviour changes. If measuring needs a production seam,
  that is a scope change to raise, not a quiet edit.

## 6. Verification tier

Replay pushes model output through the validator and the confidence policy
(project widen category `ai_output_validation_and_persistence`), the device
recorder runs the production interpreter, and the stand-in opens a network
socket (privacy invariant AGENTS.md #11). SR1.2, SR1.3 and SR1.4 go to the
verifier agent; the corpus-content and documentation tasks get an
orchestrator spot-check.

## 7. Open questions

- Which open model is the closest practical stand-in. Starting point: the
  Gemma 3n family, the open relative of current Gemini Nano, served by
  Ollama. To be confirmed against what fits the owner's GPU (8 GB) at
  install time.
- How far the stand-in's scores track the device's. Unknowable until both
  exist; the first device recording answers it and the report shows the two
  side by side.
