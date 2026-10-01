# Semantic Regression Corpus

How the app's semantic accuracy is measured: which sentences it is tested on, how a model's answers are recorded and scored, what counts as a regression, and how to run it. Written for developers and coding agents. The requirement itself is in [TEST_STRATEGY.md](TEST_STRATEGY.md) §3 and [AGENTS.md](../AGENTS.md) §6; the decision behind this design is ADR-033 in [DECISIONS.md](DECISIONS.md).

## 1. Purpose and the record/replay split

The corpus is a fixed set of synthetic capture sentences, each with the product-correct result: which activity it should match (or which new activity it should create), its state, its time words, the date they resolve to, and whether the whole capture should be logged automatically or sent to review. Expected values are what the product *should* do, never what a particular model currently answers.

Gemini Nano runs only on a real phone, only while the app is in the foreground (ADR-029), and there is no emulator or JVM path to it. The project's only AI-capable test phone is rarely available. So the pipeline is split at the model call:

1. **Record** (rare, slow): every corpus sentence is sent once to a model, and its structured answer (or failure) is saved to a recording file. The official recording is made on the phone against Gemini Nano. For fast iteration without the phone, a local stand-in model can be recorded instead (§7); it is never official.
2. **Replay** (every build, on the PC JVM, seconds): each recorded answer is fed through the **real** deterministic pipeline -- the production `CaptureInterpretationOrchestrator` with its default `CandidateSelector`, `TemporalResolver` and `InterpretationValidator`, against an in-memory repository seeded with the case's catalog -- and the result is scored against the case's expectation.

So a change to selection, time resolution, validation policy or storage is measured on every `./gradlew :core-testing:test` without a phone, while a change to the prompt or model needs a new recording.

Both recorders and the replay build the model's input through one shared function, `CorpusInterpretationInput.forCase`, so the shortlist and interpreter input are byte-for-byte the same at record and replay time.

## 2. Where things live

| What | Where |
|---|---|
| Corpus | `core-testing/src/main/resources/semantic-corpus/corpus.json` (main resources, so phone instrumented tests can load it too) |
| Corpus model and loader | `core-testing/src/main/kotlin/com/mcfrenchpants/activityledger/core/testing/corpus/` -- `CorpusModel.kt`, `SemanticCorpus.kt` |
| Recording format | same package, `SemanticRecording.kt` (the header comment documents the JSON shape, `formatVersion` 1) |
| Replay and scorer, report, gate | same package, `SemanticReplay.kt`, `SemanticReport.kt`, `SemanticGate.kt` |
| Corpus tests | `core-testing/src/test/kotlin/.../core/testing/corpus/` -- `CorpusIntegrityTest` (structure), `CorpusTemporalTest` (time resolution), `SemanticReplayScorerTest`, `SemanticRecordingTest`, `SemanticRegressionGateTest` (replays the committed recordings) |
| Recordings and baseline | `core-testing/src/test/resources/semantic-corpus/recordings/` -- `device-latest.json`, `baseline.json`, `standin-latest.json` (see its `README.md`) |
| Reports (generated, not committed) | `core-testing/build/reports/semantic-corpus/device.md` and `standin.md` |
| Phone recorder | `app-phone/src/androidTest/kotlin/com/mcfrenchpants/activityledger/semantic/SemanticCorpusRecorderTest.kt`, run by `scripts/semantic/run-device-corpus.sh` |
| Stand-in recorder | `core-ai/src/test/kotlin/com/mcfrenchpants/activityledger/core/ai/semantic/` -- `StandInCorpusRecorderTest`, `OllamaStandInClient`, `StandInSchema`; run by `scripts/semantic/run-standin-corpus.sh` |

As of 2026-09-18 all three exist: the first device recording (Pixel 10 Pro), its baseline (36 case ids as of 2026-09-19), and the stand-in recording.

## 3. What is in the corpus

48 cases: SYNONYM 18, NEAR_NEIGHBOUR 8, NEW_ACTIVITY 4, TEMPORAL 11, AMBIGUITY 4, STATE 3. 44 expect auto-accept, 4 expect review (`CorpusIntegrityTest` pins these counts). Three catalog fixtures: `household` (ten activities, including the seed-corpus targets and their near-neighbours), `household-no-edge`, and `empty`. Every case uses the same capture context, `2026-09-15T20:00:00-04:00` in `America/Detroit`, so time words resolve identically everywhere.

The categories (`CorpusCategory`):

- `SYNONYM` -- another wording of an existing activity; must match it.
- `NEAR_NEIGHBOUR` -- related but distinct; must not be merged into its neighbour.
- `NEW_ACTIVITY` -- nothing equivalent exists; a new, well-named activity.
- `TEMPORAL` -- the time wording and its deterministic resolution are the point.
- `AMBIGUITY` -- not clear enough to log; must go to review.
- `STATE` -- completed versus in progress.

### Case fields

Every field without a default is required, including nullable ones: write `"activityId": null` rather than leaving it out, so an omission can never mean "anything goes". Unknown keys are rejected.

| Field | Meaning |
|---|---|
| `id` | Stable kebab-case id; recordings, reports and the baseline refer to it. |
| `category` | One of the categories above. |
| `catalog` | Name of a catalog fixture in the file's `catalogs` map. Fixture activities have fixed ids so recorded answers replay. |
| `rawText` | The user's sentence, verbatim. Synthetic only (§9). |
| `capturedAt`, `zoneId` | ISO offset date-time of the capture and its IANA zone. |
| `expected.resolution`, `allowedResolutions` | Preferred activity resolution and any other equally correct ones. |
| `expected.activityId` | Catalog id to match (existing-activity cases only). |
| `expected.allowedActivityIds` | Other existing catalog ids the owner has ruled equally acceptable matches; usually `[]`. An auto-accepted match to `activityId` or any of these scores `CORRECT`. Never repeats `activityId` or a `mustNotMatch` id; non-empty only where `EXISTING_ACTIVITY` is an acceptable resolution and the outcome is `AUTO_ACCEPT`, and always empty for `AMBIGUITY` cases. On a new-activity case it does not make creating the new activity a duplicate. |
| `expected.newActivityName`, `allowedNewNames` | Preferred and acceptable new names (new-activity cases only), compared after `NameNormalizer.normalize`. |
| `expected.allowedStates` | Every acceptable state; a `null` entry means an empty state is acceptable (only where the outcome is review anyway). |
| `expected.temporalExpression`, `allowedTemporalExpressions` | The time words the model should copy, and acceptable alternatives (a `null` entry means leaving it empty is also correct). |
| `expected.resolvedLocalDate`, `precision` | The date (in the case's zone) and precision the time words must resolve to; set exactly when there are time words. |
| `expected.mustNotMatch` | Catalog ids that must never be the matched activity. |
| `expected.outcome` | `AUTO_ACCEPT` (logged automatically) or `NEEDS_REVIEW` (nothing logged; the user reviews it). |
| `note` | Optional explanation of a non-obvious expectation. |
| `knownResolverGap` | Set only when the real `TemporalResolver` does not produce the expected date/precision; describes the difference. |

`CorpusTemporalTest` resolves every acceptable time expression through the real `TemporalResolver` and requires the set of cases that do not resolve as expected to equal **exactly** the set marked `knownResolverGap`, so both a resolver fix and a resolver regression fail loudly. Today there are none. The last one, `time-mowed-saturday-morning` ("Mowed Saturday morning."), was cleared on 2026-09-19 (FX1.2) when the resolver gained a weekday + part-of-day rule (ADR-028); its `knownResolverGap` field was removed in the same change. A future case the resolver cannot yet handle gets the field again until the resolver is fixed.

## 4. Outcome classes

Every replayed entry gets exactly one `ReplayClass` (`SemanticReplay.kt`). The scorer only reads what the real pipeline did; it never decides confidence, validity or time itself.

- `NOT_RUN` -- the pipeline ended `InterpreterUnavailable` (recorded failure `UNAVAILABLE` or `RETRYABLE`): the model did not run. Excluded from rates.
- `CORRECT` -- the pipeline ended where the product expects: nothing saved for a review case, or an occurrence saved that passes every check below.
- `SAFE_MISS` -- the case should have been logged automatically but nothing was saved (the pipeline ended `NeedsReview` or `Rejected`). The user is asked instead; nothing wrong is stored. A `MALFORMED` or `OTHER` interpreter failure also lands here for an auto-accept case, because the orchestrator sends it to review.
- `UNSAFE_MISS` -- an occurrence was saved and at least one check failed. The checks (`UnsafeCheck`): `EXPECTED_REVIEW` (the case must go to review, but something was logged), `MUST_NOT_MATCH`, `WRONG_EXISTING_ACTIVITY`, `DUPLICATE_NEW_ACTIVITY` (created a new activity where an existing one was expected), `MATCHED_EXISTING_WHEN_NEW_EXPECTED`, `WRONG_NEW_NAME`, `STATE_NOT_ALLOWED`, `WRONG_DATE`, `WRONG_PRECISION`.

**Unsafe misses are the headline number**: they are wrong data the user did not see being saved. A safe miss costs the user a review tap; an unsafe miss corrupts their history.

## 5. The regression gate and the baseline

`SemanticRegressionGateTest` runs as part of `./gradlew :core-testing:test`:

- If `device-latest.json` exists it is replayed and `device.md` is written. If `baseline.json` also exists, every case id listed in it must be `CORRECT`; any other class, a listed id missing from the recording, or a listed id not in the corpus fails the build (`SemanticGate`). Cases not listed are reported but never fail the build.
- If `standin-latest.json` exists it is replayed and `standin.md` is written. **The stand-in never gates**: its scores are informational only.
- A missing recording or baseline skips that test (JUnit Assume). A malformed or stale recording (§6) always fails, and an error report is written in place of the normal one.

Reports are written before the gate is evaluated, so they exist even when it fails.

`baseline.json` is a plain JSON array of case ids, e.g. `["case-a", "case-b"]`. **It is written by a human, from a reviewed device run** -- never generated automatically, never from a stand-in run, never hand-invented. It lists the cases the real on-device model is known to get right, so the gate catches any change that breaks one of them. Adding a case to the baseline is a deliberate decision after reading the device report.

## 6. Adding or changing a case

Follow [AGENTS.md](../AGENTS.md) §6 -- every user-visible semantic bug becomes a corpus case:

1. **Add a failing case** to `corpus.json`. Write the product-correct expectation, fill every required field, and give it a new kebab-case id. If the real resolver cannot produce the expected date, add `knownResolverGap` (otherwise `CorpusTemporalTest` fails). `CorpusIntegrityTest` checks structure: unique kebab-case ids, parseable capture context, catalog references and ids that exist, consistent resolution fields, consistent `allowedActivityIds`, auto-accept expectations that are actually auto-acceptable, fixtures within the candidate selector's bound, and a total of 40-60 cases.
2. **Reproduce** it: run `./gradlew :core-testing:test`, then re-record (stand-in to iterate, §7; phone for the official answer, §8) and confirm the case shows up as a miss in the report.
3. **Implement the fix** (prompt, selector, resolver or validation policy).
4. **Verify**: re-record, replay, and check the case is `CORRECT` and no baseline case regressed.
5. **Document** material prompt or policy changes: bump `PROMPT_VERSION` for a material prompt change ([AI_INTERPRETATION_SPEC.md](AI_INTERPRETATION_SPEC.md) §19) and record policy decisions in [DECISIONS.md](DECISIONS.md).

### The corpus hash and stale recordings

`corpus.json` is LF-only (the repository's `.gitattributes` normalizes text files to LF; do not let an editor save it as CRLF). Its SHA-256 is computed over the file's **exact bytes**, and every recording stores the hash it was recorded against. Any byte change -- a new case, an edited expectation, even reformatting or line endings -- changes the hash.

What replay does with an older recording (verified against `SemanticReplay` and `SemanticGate`):

- **Refused** (`StaleRecordingException`, the test fails, "re-record") if a recorded case id is no longer in the corpus, or if a case's candidate shortlist (the offered activity ids or the shortlist's context hash) differs from what the recording saw -- for example because its sentence or its catalog fixture changed.
- **Replayed, but flagged** otherwise: the report shows `corpus matches | NO - recorded against a different corpus`. Old answers are scored against the current expectations; cases added since the recording are simply absent from it and not scored.
- **Gate failure** if the device recording was made against a different corpus and lacks any baseline case.

An expectations-only change (no change to a case's sentence, catalog fixture or capture context) keeps each case's shortlist, so an existing recording still replays and is re-scored against the new expectations; its report shows `corpus matches: NO` until it is re-recorded.

So a hash mismatch alone does not stop a replay. Treat any report whose `corpus matches` is not `yes` as not a measurement of the current corpus, and re-record after every corpus change before drawing conclusions.

## 7. Running the stand-in (local, not official)

For iterating on the prompt without the phone. Prerequisites, all done by you -- **the script never installs or pulls anything**:

- Install Ollama from https://ollama.com and start it (the Ollama app, or `ollama serve`). It must answer on `http://127.0.0.1:11434`.
- Pull the model: `ollama pull gemma3n:e4b` (the default).
- `curl` on the PATH.

Then, from Git Bash (Windows) or a macOS/Linux shell:

```bash
scripts/semantic/run-standin-corpus.sh
MODEL=gemma3n:e2b scripts/semantic/run-standin-corpus.sh   # a different pulled model
```

It checks the server and model, runs only `StandInCorpusRecorderTest` (which overwrites `standin-latest.json`), then runs the gate test and prints where `standin.md` is. Exit codes: `0` recorded and reported, `1` setup or run problem.

The recorder can also be run directly: `./gradlew :core-ai:testDebugUnitTest --tests "*StandInCorpusRecorderTest" -PsemanticStandIn=true`, optionally with `-PsemanticStandIn.model=<ollama model>` and `-PsemanticStandIn.baseUrl=<loopback URL>`. Without `-PsemanticStandIn=true` it skips and contacts nothing. If every call fails at the transport level it writes nothing, so a dead server never overwrites a previous recording.

The stand-in sends what the phone sends: the same system instruction, prompt text, greedy generation settings (temperature 0, top-k 1, fixed seed) and response decoder, with the answer constrained by a JSON schema. The schema text appended to the prompt approximates ML Kit's; the recording's `notes` say so. It is a different model from Gemini Nano, so its numbers indicate direction only: **the stand-in is never the official measurement, never gates, and its reports carry a "STAND-IN MODEL -- NOT THE OFFICIAL RESULT" banner.** Runtime and model versions are pinned in [PLATFORM_REFERENCES.md](PLATFORM_REFERENCES.md).

## 8. Running the phone session (official)

Prerequisites:

- The Pixel 10 Pro connected over ADB (in practice Wi-Fi ADB: `adb connect <phone-ip>:<port>`; USB also works). If more than one device is connected, pick one with `ANDROID_SERIAL=<serial>`.
- The on-device model already `READY`. **Nothing is downloaded** (ADR-031): if it is not ready the recorder skips and the script exits `2`.
- The phone unlocked with the screen on for the whole run: the model only answers while the app is in the foreground (ADR-029), and the recorder holds `MainActivity` resumed around the whole loop.

Then, from the repository root in Git Bash or a macOS/Linux shell:

```bash
scripts/semantic/run-device-corpus.sh
```

It builds and installs the app and its test package (neither uninstalled afterwards; app data left alone), runs only `SemanticCorpusRecorderTest` with the opt-in instrumentation argument `semanticCorpus=true`, pulls the recording, checks it, and only then overwrites `device-latest.json`, then runs the gate test. Expect roughly 6-7 seconds per case -- about 5-6 minutes for the corpus -- plus a few minutes of build and install.

The recorder records one model answer per case with no repair (ADR-030); a failure is recorded as data. The one exception is a fast refusal (a failure in under 1 s, before any inference could run -- AICore throttling back-to-back calls): it waits and asks again, up to six times, and records the retry count in the recording's `notes`. It writes to a temp file and renames it, so an interrupted run never looks complete.

Exit codes: `0` recorded and gate passed; `1` setup or run problem (nothing overwritten); `2` model not ready, recorder skipped (nothing overwritten); `3` recording saved but the regression gate failed.

## 9. Reading the report

`device.md` / `standin.md` sections, in order:

1. **Provenance** -- source (`DEVICE`, `STAND_IN` or `SYNTHETIC`), model label, device model, interpreter/prompt/schema versions, recording time, corpus hash recorded vs current and whether they match. Non-device reports open with a "not the official result" banner. Check this first: a wrong source, an unexpected prompt version or `corpus matches: NO` changes what the numbers mean.
2. **Totals** -- count per class and rate of scored entries (`NOT_RUN` excluded).
3. **By category** -- the same classes per category.
4. **UNSAFE_MISS**, then **SAFE_MISS** -- one row per case: id, category, expected outcome, pipeline outcome, reason codes, and the case's (synthetic, committed) sentence. For unsafe misses the reasons are the failed checks (§4); for safe misses they are the validator's review reasons (for example `STATE_MISSING`, `TIME_UNRESOLVABLE`, `EXISTING_ACTIVITY_NOT_SUPPLIED`).
5. **Confidence band vs class** -- for calibrating the auto-accept policy: how often each recorded confidence band ended correct, safe or unsafe.
6. **Secondary diagnostics** -- how often the model's time words matched an acceptable expression, how often its resolution was acceptable, and latency (median and max).
7. **NOT_RUN** -- cases where the model did not run, with the failure kind.

Read unsafe misses first. The console prints a shorter summary: source, counts, and case ids with reason codes only.

## 10. Privacy

- `corpus.json` is **synthetic text only**. Never put a real capture, or anything derived from one, into it. The tag corpus (§12) is the one exception: its REAL_ENTRY and EMPTY_START sentences are real captures the owner chose and approved by quoting them in the TG1 implementation plan. Never copy a capture into any corpus without that explicit, per-sentence owner approval, and never extract one automatically from a device database.
- No corpus text or model output in logs or on the console. The recorders log counts, timings and the output path only; the console summary carries ids, counts and reason codes only; error messages name case ids and fields. The recording format has no field for the model's raw text output. The Markdown report may show the committed synthetic sentence, never model text such as a proposed name.
- The stand-in client talks to loopback only: a base URL whose host is not `127.0.0.1`, `::1` or `localhost` is refused, no proxy is used and redirects are not followed, so corpus text never leaves the machine.

## 11. Results so far

The only recording is the stand-in run of 2026-09-18 (`gemma3n:e4b` via Ollama, prompt version `2`): 48 of 48 answered, 0 malformed; CORRECT 20, SAFE_MISS 20, UNSAFE_MISS 8; every answer HIGH confidence; 17 of the 20 safe misses were `STATE_MISSING` (the model left the state empty). **Not official.**

First device recording, 2026-09-18 (Pixel 10 Pro, Gemini Nano, prompt version `2`): 48 of 48 answered, 0 malformed; CORRECT 32, SAFE_MISS 6, UNSAFE_MISS 10. All 10 unsafe misses were HIGH confidence -- mostly a new or ambiguous activity matched to an existing near-neighbour -- so HIGH confidence alone does not make an answer safe to auto-accept. `baseline.json` lists the 32 correct cases. Details and the stand-in comparison: [`proposals/semantic-regression/RESULTS.md`](proposals/semantic-regression/RESULTS.md).

The phone throttles back-to-back requests (AICore `BUSY`, after about 20 calls in a row); the device recorder waits and retries such fast refusals and records the retry count in the recording's `notes`.

## 12. Tag corpus (subject + action), TG1

A second, separate test set for the subject + action tagging redesign. Instead of matching a capture to one activity, every entry gets a **subject** tag (what it was done to: furnace, hot tub, lawn mower), an **action** tag (what was done: change filter, add gas, mow) and an optional **duration**. The AI is expected to extract the user's own words; deterministic logic then resolves them against existing tags (exact name, then alias, then close match) and a policy decides the outcome. corpus.json is untouched, so its hash and recordings stay valid.

| What | Where |
|---|---|
| Tag corpus | `core-testing/src/main/resources/semantic-corpus/tag-corpus.json` (UTF-8, LF only) |
| Model and loader | same package as the corpus model -- `TagCorpusModel.kt`, `TagCorpus.kt` (`TagCorpus.load()`, `parse(bytes)`, `case(id)`, `sha256`) |
| Tests | `TagCorpusIntegrityTest` (structure, faithful ports, exact real-entry sentences, pinned counts, LF only), `TagCorpusTemporalTest` (time words through the real `TemporalResolver`; no gaps today) |

Shape: `{ schemaVersion: 1, description, catalogs, cases }`. Decoding is strict exactly as for corpus.json (unknown keys rejected, every field without a default required, including nullable ones).

- **Fixtures** (`catalogs`): each has `subjects` and `actions` (`{ id, displayName, aliases }`, ids prefixed `subj-` / `act-`) and `pairs` (`{ subjectId, actionId, displayName }`: a known combination, i.e. an old "activity", used to infer an omitted subject or action). Four fixtures: `empty`; `household` (tag form of corpus.json's household catalog, ten pairs); `household-no-edge` (without the edge action and Edge lawn); `owner-2026-10-01` (tag form of the owner's real catalog when the real entries were recorded: Mow lawn, Trim hedges, Dishes, Hot tub, Walk dogs, Furnace maintenance, Reboot WiFi, Weed garden).
- **Case fields**: `id`, `group`, `category` (the corpus.json categories), `catalog`, `rawText`, `capturedAt`, `zoneId` (all `2026-09-15T20:00:00-04:00`, `America/Detroit`), `expected`, optional `note`, `knownResolverGap`, `sourceCaseId`.
- **`expected`**: `subject` and `action` (each an expected tag: `existingId` *or* `newName`, plus `allowedExistingIds`, `allowedNewNames` -- compared case-insensitively after trimming -- `mayBeEmpty` and `mustNotMatch`), `allowedStates`, the same time fields as corpus.json, `durationExpression` / `allowedDurationExpressions` / `durationMinutes` (a duration is never a time: "for half an hour" leaves the time empty), `outcome` and `allowedOutcomes`. An `existingId` means creating a new tag would be a duplicate; `mustNotMatch` pins wrong tags, including the ones the real entries were actually filed under.
- **Groups** (72 cases): `PORTED` 48 -- one per corpus.json case (`p-<source id>`, `sourceCaseId` set), same sentence, context, category, states and time expectations, with the product-correct tag reading; `REAL_ENTRY` 12 -- the owner's real watch transcriptions, verbatim with speech errors, against `owner-2026-10-01`; `SIBLING` 8 -- synthetic variations on the real entries' tags; `EMPTY_START` 4 -- four real-entry sentences against the empty catalog (every tag new).
- **Outcomes** (`TagOutcome`): `AUTO_SAVE` (logged silently), `CONFIRM` (a quick "did you mean X?" card; nothing saved silently), `NEEDS_REVIEW` (nothing logged). Today 68 cases prefer AUTO_SAVE and 4 NEEDS_REVIEW; CONFIRM appears only as an acceptable alternative where the words differ from an existing tag or a new tag is close to an existing one.

Privacy: unlike corpus.json, the `REAL_ENTRY` and `EMPTY_START` sentences are real captures the owner supplied for this purpose. Do not add any other real capture. The §10 logging rules apply unchanged.

### Extraction prompt (v4) and tag recordings, TG1.2

- **Extractor.** `GeminiNanoActivityExtractor` (core-ai) asks the model ONLY to extract, in the user's own words: `operation`, `subject`, `action`, `activityState`, `temporalExpression` and `durationExpression` (`ExtractionResponse`, no confidence field). It is shown no activity or tag list. Prompt version **4** (`EXTRACTION_PROMPT_VERSION`, pinned by `ExtractionPromptDriftTest`), extractor version `gemini-nano-extract-1`, schema version 1 -- all separate from the v3 constants. It is built **beside** the v3 interpreter, which the app keeps using until the switch-over (ADR-038). Same generation settings, readiness gate (never downloads), one call, failure mapping and no-logging rules as v3. `ExtractionPromptTest` asserts no tag-corpus or corpus.json sentence appears in the prompt or system instruction, so the worked examples are not the test.
- **Input.** `TagCorpusExtractionInput.forCase(case)` is the one place deciding what the extractor sees for a tag case: sentence, capture instant, zone -- nothing from the catalog fixture.
- **Recording format.** `TagRecording.kt` (core-testing; `formatVersion` 1, strict like `SemanticRecording`): header `source`, `modelLabel`, `deviceModel`, `interpreterVersion`, `promptVersion`, `schemaVersion`, `tagCorpusSha256` (= `TagCorpus.sha256`), `recordedAt`, `notes`; one entry per case with `caseId`, `answer` (the six extracted fields) XOR `failureKind`, and `latencyMs`. No raw model text and no sentence text.
- **Recorders.** On the phone: `app-phone` androidTest `TagCorpusRecorderTest` (opt-in `tagCorpus=true`; same safety behaviour as the v3 device recorder: READY or skip, foreground held, BUSY backoff, numbers-only logs, atomic write) writing `<externalFilesDir>/semantic-corpus/tag-device-recording.json`. Stand-in: core-ai `StandInTagCorpusRecorderTest` (opt-in `-PtagStandIn=true`, loopback Ollama only, reuses `-PsemanticStandIn.model` / `.baseUrl`).
- **Scripts.** `scripts/semantic/run-device-corpus.sh --tags` writes `recordings/tag-device-latest.json`; `scripts/semantic/run-standin-corpus.sh --tags` writes `recordings/tag-standin-latest.json`. Both then run only `TagRecordingFilesTest`, a structural check (decodes, matches the current tag corpus hash, right source, exactly one entry per case; skips when the file is absent). Without `--tags` both scripts behave exactly as before.

There is still **no replay, scorer, report or gate** for the tag corpus (planned as TG1.4), and no resolver (TG1.3).
