# Implementation plan — Semantic regression corpus (work item SR1)

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — shape approved by owner 2026-09-17.
Branch: `feature/semantic-regression`, off `main` at 4076117.
This work item is **sdlc-tracked** (`SR1` in `.sdlc/state.json`).

Seven tasks, ordered. SR1.1-SR1.4 are code; SR1.5 and SR1.7 are runs (the
orchestrator does them, not an implementer); SR1.6 is documentation.

Verification tier (spec §6): **SR1.2, SR1.3, SR1.4 go to the verifier**;
SR1.1 and SR1.6 get an orchestrator spot-check.

Standing rules for every task: no ML Kit import outside `core-ai` (ADR-023);
no production behaviour change in `core-domain`, `core-data`, `core-ai/src/main`
or `app-phone/src/main` — needing one is `scope_change_requested`; no corpus
text, prompt text or model output in logcat/console output beyond the report
itself (AGENTS.md #11); no model download from any path (ADR-031); Kotlin
stays 2.3.x (ADR-022).

Where things live:

| What | Where |
|---|---|
| Corpus (shared by JVM, device, stand-in) | `core-testing/src/main/resources/semantic-corpus/corpus.json` |
| Corpus model + loader + recording format | `core-testing/src/main/kotlin/.../core/testing/corpus/` |
| Replay scorer, report, gate (JVM tests) | `core-testing/src/test/kotlin/.../core/testing/corpus/` |
| Committed recordings + baseline | `core-testing/src/test/resources/semantic-corpus/recordings/` |
| Device recorder | `app-phone/src/androidTest/.../semantic/` |
| Stand-in recorder | `core-ai/src/test/.../semantic/` (host test, opt-in) |
| Phone-session and stand-in scripts | `scripts/semantic/` |

## SR1.1 — Corpus format, loader and cases

Spec: R1, G1.

Scope. Add a JSON library to the version catalog (kotlinx.serialization,
version verified against Maven Central and compatible with Kotlin 2.3.21; its
compiler plugin comes from the Kotlin version already pinned) and apply it to
`core-testing` only. Define the corpus model, a loader reading the corpus
from the classpath, and conversion of a named catalog fixture into
`List<CatalogActivity>` with the fixture's fixed ids. Write the cases.

Case content: TEST_STRATEGY §4 (every seed sentence), §5 (every temporal
phrase, with fixed capture context 2026-09-15T20:00:00-04:00,
America/Detroit), §6 (every ambiguity sentence), the three AGENTS.md §6
examples, plus: near-neighbour pairs from the prompt's own worked examples
(raked vs blow leaves, washed vs wax car), new-activity cases (sentence with
no equivalent in the catalog, and an empty catalog), edging run against a
catalog *without* Edge lawn (must become new, must not match Mow lawn),
completed vs in-progress, and generic "dryer" phrasing. Roughly 40-50 cases.

Expected outcome per case is what the *correct product behaviour* is, not what
the current model does. Ambiguity cases expect review, not a guess.

Acceptance:
1. Corpus JSON parses via the loader; every case has TEST_STRATEGY §3's fields
   (id, category, catalog ref, raw text, capture time + zone, expected
   resolution, expected activity id or new name, expected state, expected
   temporal expression + allowed alternatives + resolved local date +
   precision where applicable, must-not-match ids, expected outcome).
2. Case ids unique; every catalog reference and every activity id a case
   names exists in its catalog; every catalog fits within
   `CandidateSelector.DEFAULT_BOUND`. Asserted by a test, so a bad edit fails
   the build.
3. Every TEST_STRATEGY §4/§5/§6 sentence and the three AGENTS.md §6 examples
   are present (a test lists them and asserts presence).
4. For every case with an expected temporal expression, `TemporalResolver`
   applied to that expression gives the expected local date and precision
   (R4). Hard assertion. If the resolver genuinely cannot resolve a
   TEST_STRATEGY phrase, the case records the resolver's *current* behaviour,
   the test names it, and the report says so — the resolver is not changed
   here.
5. `./gradlew :core-testing:test` and `./gradlew test` pass.

## SR1.2 — Recording format, replay scorer, report, regression gate

Spec: R2, R3, R7, R8, G5, G6. Depends on SR1.1. **Verifier.**

Scope. In `core-testing` main: the recording model (provenance header + one
entry per case: case id, offered candidate ids, context hash, the answer as
the seven `InterpretationCandidate` fields *or* a failure kind, latency ms)
with read/write. `InMemoryActivityRepository.seedActivity` may gain an
optional explicit id so replay can seed the corpus's fixed ids.

In `core-testing` tests: the replay. For each recorded case: seed the case's
catalog, create the capture, feed the recorded answer through
`FakeActivityInterpreter` into the real `CaptureInterpretationOrchestrator`
(real selector, resolver, validator), then classify as correct / safe miss /
unsafe miss (spec R2) from the orchestrator outcome and the stored
occurrence/interpretation. Replay asserts the recorded context hash equals
the replayed selection's hash — a mismatch means the recording is stale
against the corpus and fails loudly.

Report: written to `core-testing/build/reports/semantic-corpus/<source>.md`
plus a short console summary: totals per outcome class, per category, a list
of every unsafe and safe miss with the reason, a confidence-band vs
correctness table (for ADR-027 calibration), latency median/max, and the
provenance header. Stand-in reports are titled as stand-in.

Gate: `recordings/baseline.json` lists case ids that were correct in the
accepted *device* recording. Replay of `recordings/device-latest.json` fails
if any baseline case is not correct. Stand-in replay never fails the build.
Absent recordings are a skip with a message, not a failure (none exist
until SR1.5/SR1.7). Include a small committed synthetic recording under
test resources used only to test the scorer itself — never named like a real
recording.

Acceptance:
1. Scorer tests cover each outcome class with a synthetic recording: a
   correct match, a safe miss (confident answer demoted to review), an unsafe
   miss (must-not-match activity auto-accepted), a malformed/failure entry,
   and a stale-hash recording failing loudly.
2. No domain rule is re-implemented in the harness: grep shows the
   orchestrator, selector, resolver and validator are the real classes.
3. Gate behaviour tested: baseline case failing → test fails with the case id;
   stand-in never gates; missing recording → skip.
4. Report file generated with every section above; no raw corpus text is
   printed to the console beyond case ids and short reasons.
5. `./gradlew test` passes.

## SR1.3 — Device recorder and phone-session script

Spec: R5, R7, R9. Depends on SR1.2. **Verifier.**

Scope. `app-phone` androidTest `SemanticCorpusRecorderTest`: skips via
`Assume` unless readiness is READY; launches and holds `MainActivity`
(ADR-029, as `CaptureVerticalSliceTest` does); warms the model once; for each
case builds `InterpretationInput` with `CandidateSelector` over the case's
catalog and calls `GeminiNanoActivityInterpreter.interpret` once; writes the
recording (provenance: source DEVICE, `Build.MODEL`, the interpreter's
provenance, corpus hash) to the app's external files dir. Excluded from a
plain `connectedDebugAndroidTest` run unless an instrumentation argument asks
for it, so the vertical-slice test stays quick. `app-phone` may depend on
`core-testing` for androidTest only.

`scripts/semantic/run-device-corpus.sh` (Git Bash): checks `adb devices` shows
exactly one device, runs just this test with the argument, pulls the
recording to `core-testing/src/test/resources/semantic-corpus/recordings/device-latest.json`,
then runs the replay and prints the summary. States expected duration up
front. Never downloads the model; if not READY it says so and stops.

Acceptance:
1. `./gradlew :app-phone:assembleDebugAndroidTest` succeeds; `./gradlew test`
   passes.
2. Skip-not-fail when not READY; model never downloaded (grep: no
   `download(` call on this path).
3. One interpret call per case; no retries.
4. No corpus text or model output to logcat (grep `Log.`/`println` in the
   diff); recording written only to the app's own files dir.
5. No change to `app-phone/src/main`, no new manifest permission.
6. Script is idempotent, fails clearly with no device or two devices, and
   its pull path matches SR1.2's replay path.
7. Report states plainly: compiled only, or executed on a device (a device
   may not be available; that is fine and is SR1.7's job).

## SR1.4 — Stand-in recorder (local model)

Spec: R6, R7, R8, R9. Depends on SR1.2. **Verifier.**

Scope. `core-ai` host test `StandInCorpusRecorder` (test source set, so it
can use the internal `INTERPRETATION_SYSTEM_INSTRUCTION` and
`buildInterpretationPrompt`). Skipped unless opted in (Gradle property or
environment variable, wired in `core-ai/build.gradle.kts`'s test task). Talks
to an Ollama-compatible HTTP API on 127.0.0.1 only (host/port configurable,
host restricted to loopback) using `java.net.http`, with temperature 0,
top-k 1, the interpreter's seed and max tokens, and a JSON schema for the
seven fields as the response format. The system instruction is sent as-is;
the prompt is `buildInterpretationPrompt` output plus a plain rendering of
the schema (the device's `includeSchemaInPrompt` text is ML Kit's and not
reproducible; note the approximation in provenance). The JSON answer is
decoded through the same `InterpretationResponse` → `InterpretationResponseDecoder`
path the device uses, so decode failures are counted identically. Writes the
recording to `recordings/standin-latest.json` (source STAND_IN, model label
from config). `scripts/semantic/run-standin-corpus.sh` runs it then the replay.

Acceptance:
1. Default `./gradlew test` does not contact any server and passes.
2. Tests against an in-process fake HTTP server on loopback cover: request
   carries the exact system instruction and prompt text, greedy settings,
   schema; a valid answer decodes; an invalid enum → MALFORMED; server down →
   clear skip/failure message, not a hang (timeouts set).
3. Non-loopback host is refused.
4. No ML Kit type leaves `core-ai`; no change to `core-ai/src/main`.
5. No corpus text or model output printed beyond the recording file.

## SR1.5 — Install the stand-in and take its first recording

Orchestrator run, not an implementer task. Depends on SR1.4.
Ask the owner immediately before installing (runtime + model size stated).
Install Ollama, pull the chosen Gemma 3n variant that fits 8 GB VRAM, run the
stand-in script, commit `standin-latest.json` and summarise the report.
Nothing here gates the build.

## SR1.6 — Documentation

Orchestrator spot-check. `docs/SEMANTIC_CORPUS.md` (what the corpus is, the
three outcome classes, how to add a case per AGENTS.md §6, how to run the
phone session and the stand-in); TEST_STRATEGY §3 pointing at it;
a new ADR (record-and-replay split, stand-in never official, baseline gate);
AI spec §19 and PROJECT_STATUS updated; `docs/PLATFORM_REFERENCES.md` gains
the stand-in runtime and model version.

## SR1.7 — First device recording and baseline

Orchestrator run on the owner's phone. **Needs the Pixel 10 Pro for ~5-10
minutes** — the owner schedules it. Run the phone-session script, commit
`device-latest.json`, write `baseline.json` from its correct cases, write
`RESULTS.md` (numbers, stand-in vs device comparison, what the
provisional choices look like), update backlog item 9 to done.
