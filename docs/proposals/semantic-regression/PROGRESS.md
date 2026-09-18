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
| SR1.3 | Device recorder and phone-session script | done | Verifier fail on the no-device-contact rule only (incident below); code accepted. Happy path not yet run |
| SR1.4 | Stand-in recorder (local model) | done | Verifier pass. Fake-server tested only; no real model yet |
| SR1.5 | Install stand-in, first stand-in recording | done | Model on `C:\Dev\ai\models`. 20 correct / 20 safe miss / 8 unsafe miss. Not official |
| SR1.6 | Documentation | todo | Spot-check |
| SR1.7 | First device recording and baseline | todo | Needs the Pixel 10 Pro ~5-10 min; owner schedules |

## Session log

### 2026-09-18 — SR1.5 done: first stand-in recording

Owner re-downloaded the model to `C:\Dev\ai\models` (internal NVMe). Blob
sha256 verified against its digest. Ollama stopped; junction
`C:\Users\ADRen\.ollama\models` repointed to `C:\Dev\ai\models` (the old
F: junction was renamed to `models.old-F-drive-link`, not deleted -- the
auto-mode classifier refused `rmdir` on it); user env `OLLAMA_MODELS` updated
to match; tray app relaunched via `explorer.exe`. `/api/tags` lists
`gemma3n:e4b` (Q4_K_M, 6.9B).

`scripts/semantic/run-standin-corpus.sh`: 48/48 answered, 0 malformed, total
214 s (median 2.5 s, max 95 s -- the first call is model load). Ollama accepted
the `["string","null"]` schema, so the SR1.4 open risk is closed.
Replay: CORRECT 20, SAFE_MISS 20, UNSAFE_MISS 8. Every answer is HIGH
confidence, so confidence bands say nothing for this model.

Findings worth carrying into prompt work (stand-in only, not official):
- 18 of 20 safe misses are `STATE_MISSING`: the model leaves `state` null and
  the orchestrator rejects. Likely prompt/schema wording rather than domain
  logic; compare against the device run (SR1.7) before changing anything.
- Unsafe misses cluster on edging vs mowing (4 of 8 incl. the no-edge catalog
  and the STATE case), leaves (raking/blowing matched to an existing
  activity), smoke-detector batteries matched to an existing activity, and
  the generic "Cleaned the dryer" auto-accepted instead of review.
- Temporal 9/11 correct; misses are the known Saturday-morning resolver gap
  and one STATE_MISSING.

### 2026-09-18 — SR1.5 blocked on the owner's storage hardware

Owner installed Ollama 0.34.2 themselves and asked for models off C:. Model
folder redirected with a junction `C:\Users\ADRen\.ollama\models` -> target
(plus user env var `OLLAMA_MODELS`; the tray app launched via explorer did not
see the new env var until next sign-in, hence the junction). Processes started
from the agent's own shell die with it, so Ollama must be launched via
`explorer.exe "<...>\ollama app.exe"`.

- `H:\data\ai\models` (USB "Archive"): pull OK, then every read failed; System
  log: bad block + repeated `disk` event 154 on Disk 2. Owner chose to leave
  the partial download there.
- `F:\AI\models` (USB "DATA"): pull OK but `disk` 51/154 errors on Disk 1 during
  it; sequential read ~8 MB/s; model load timed out at Ollama's 5-minute
  load timeout. Junction currently points here.

H:, F: and I: all sit behind ASMT 2115 USB bridges; two erroring on the same
day suggests the dock/cable/port rather than two disks. First stand-in run
(model on H:): all 48 calls HTTP 500 from the read failure; the recorder
correctly refused to write a recording. The stand-in path is therefore still
untested against a real model, including whether Ollama accepts the
`["string","null"]` schema.

### 2026-09-18 — SR1.4 done

Stand-in recorder in `core-ai/src/test/.../semantic/` (`OllamaStandInClient`,
`StandInSchema`, `StandInCorpusRecorderTest`) plus
`scripts/semantic/run-standin-corpus.sh`. Sends the real system instruction
and `buildInterpretationPrompt` output to Ollama on loopback only; the JSON
schema is read from `InterpretationResponse`'s `@Guide` annotations so it
cannot drift. Refuses to overwrite a recording if every case came back OTHER.
The implementer run was interrupted once by an owner usage limit and resumed
with its context intact. Open risk: whether Ollama accepts `["string","null"]`
types and null in enums in its grammar conversion -- first real run (SR1.5)
will tell; if not, every case is OTHER and nothing is written.

### 2026-09-18 — SR1.3 done (with a device-contact incident)

Recorder `app-phone/.../semantic/SemanticCorpusRecorderTest.kt` (opt-in via
runner arg `semanticCorpus=true`, holds MainActivity RESUMED for the whole
loop, one `interpret` per case, atomic write to the app's external files dir)
and `scripts/semantic/run-device-corpus.sh` (exit codes 0 ok / 1 error /
2 model not ready / 3 gate failed; uses `leaveApksInstalledAfterRun=true` so
AGP does not uninstall the app, which would delete the recording before the
pull and wipe app data).

AGP 9.4 writes an Assume-skip into the connected-test XML as a `<failure>` with
`AssumptionViolatedException`, not `<skipped>`; the script handles both.

**Incident.** The packet was compile-only with no device contact. The
implementer tried to shadow `adb` with a fake by prepending a `C:/...` path to
PATH; the colon split the entry, the real adb ran, and the script ran the
recorder three times on the owner's USB-attached moto g 2025 (installed the
debug and test APKs, first install ever on that phone; recorder skipped at
`UNSUPPORTED_DEVICE`; no model call, download or recording). The implementer
self-reported it as `failed`. The verifier failed criterion 9 for the same
reason and passed everything else. The owner was told and said the phone is
theirs to offer for testing anyway. Lesson for packets: when a phone is
attached, a packet that forbids device contact should also have the
implementer set `ANDROID_SERIAL` to a non-existent serial for every command,
or the orchestrator should unplug-check first; PATH shims in Git Bash must use
`/c/...` form.

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
