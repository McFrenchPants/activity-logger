# Design spec — AI vertical slice (backlog item 4)

Status: **approved by owner** (2026-09-17). The owner chose this work item as
the next thing to build, and confirmed §5.4: the app never downloads the model
on its own — it treats the model as unavailable and waits to be asked.
Work item `AI1`. Branch: `feature/ai-vertical-slice`, off `main` at a949941.
Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 4 — AI vertical slice",
`docs/AI_INTERPRETATION_SPEC.md` §4-12, §16-19, `docs/ARCHITECTURE.md` §5-6,
§21, `docs/TEST_STRATEGY.md` §5 (AI contract tests, semantic device tests),
§12, ADR-010, ADR-018, ADR-021/022/023, ADR-027, ADR-028,
`docs/proposals/domain-services/DESIGN_SPEC.md` (the contracts this consumes).

Audience: agents. The owner-facing summary is given in chat, not here.

## 1. Goal

Make the app actually interpret a sentence with the real on-device model and
persist the result. Concretely: implement `ActivityInterpreter` in `core-ai`
against the ML Kit GenAI Prompt API with Structured Output, wire it to the
existing `CaptureInterpretationOrchestrator` and the real Room database, and
prove on the Pixel 10 Pro that one hardcoded sentence ("I cut the grass
yesterday.") produces a correctly matched, correctly dated, persisted
occurrence with the raw text retained unchanged.

Every deterministic piece downstream of the model already exists and is tested
(work item DS1). This work item adds exactly the adapter, the prompt, the
capability detection, and the on-device proof.

## 2. Non-goals

- Speech capture. Input is hardcoded text (Step 6 owns the microphone).
- Any UI, ViewModel, navigation or user-visible diagnostics screen. The
  ARCHITECTURE §21 requirement to *expose* capability state to the user is
  Step 7; this work item only produces the state a screen will later read.
- The semantic regression corpus and its runner (Step 5). Fixture-level tests
  for the specific cases below are in scope; an automated corpus is not.
- Wear transport, query interpretation, re-interpretation of an accepted
  capture, alias promotion, canonical-activity merges.
- Cloud fallback of any kind (ADR-013: there is none, ever).
- Prompt tuning beyond making the seed cases in AI_INTERPRETATION_SPEC §20
  pass. Systematic prompt comparison is Step 5's job, once measurement exists.
- Model-download UX. §5.4 decides what the code does; presenting it to a user
  is Step 7.

## 3. Requirements

**R1 — Structured output type.** A `@Generable` Kotlin data class in `core-ai`
modelling exactly the fields of `InterpretationCandidate`, using `@Guide`
`enumValues` for every field that is an enum in the domain (operation,
activity resolution, activity state, confidence band) rather than free strings,
per AI_INTERPRETATION_SPEC §4. Supported field types are only `String`,
`Double`, `Float`, `Int`, `Long`, `Boolean`, `List<T>` and nested `@Generable`
classes (ADR-023), so the enums are carried as constrained strings and mapped
in Kotlin. Optional fields must be expressible; the mapping layer decides what
"absent" means, never the model.

**R2 — Mapping to the domain type.** A pure, host-testable function from the
`@Generable` type to `InterpretationCandidate`. It performs *decoding* only:
unknown enum strings, blank names and absent fields map to the domain's own
"unknown/absent" representations or to a decode failure. It performs no
semantic validation — candidate-ID membership, resolution/field consistency
and name rules stay in `InterpretationValidator` (ADR-010, AGENTS.md #5). The
`@Generable` type must not appear in any hand-written public signature of
`core-ai` (ADR-023 condition 2).

**R3 — Prompt.** A versioned system instruction and user-prompt builder in
`core-ai`, following AI_INTERPRETATION_SPEC §5 (nine instruction principles),
§6 (Task / Rules / Current context / Candidate activities / User utterance
sections; few-shot examples covering synonym match, near-neighbour rejection,
new activity, ambiguous, completed vs in-progress, relative time phrase), §8
(match vs do-not-match rule), §9 (verb-first concise canonical naming) and §10
(extract the time *phrase*; never fabricate a timestamp). Generation is
configured for deterministic classification: low temperature, small output
budget, fixed seed where the API allows it. The prompt builder is a pure
function of `InterpretationInput` — same input, same prompt string — so it is
fully host-testable, and the prompt text lives in version-controlled source
with an explicit `promptVersion` identifier (§19).

**R4 — Capability detection.** A `core-ai` API, in plain Kotlin types only,
reporting the device's AI readiness: model feature status (ML Kit's
`checkStatus()`: unavailable / downloadable / downloading / available) and
structured-output availability (`isStructuredOutputFeatureAvailable()`), per
ADR-023 and AI_INTERPRETATION_SPEC §16. Installation must never be assumed to
imply readiness. No ML Kit type crosses the module boundary.

**R5 — The interpreter.** `ActivityInterpreter` implemented in `core-ai` over
`GenerativeModel.generateContent(GenerateTypedContentRequest<T>)`. It must:
never throw (failures are returned as `InterpretationResult.Failure`); map
unavailability to `UNAVAILABLE`, transient failures carrying a retry delay to
`RETRYABLE`, decode failures to `MALFORMED`, everything else to `OTHER`; carry
the model's own structured JSON in the result where available, for audit; and
carry **no user text and no exception message** in a failure (AGENTS.md #11,
AI_INTERPRETATION_SPEC §18). It reports `InterpreterProvenance` (interpreter
version, prompt version, schema version).

**R6 — Wiring.** A single composition point that builds the real
`CaptureInterpretationOrchestrator` from the real `RoomActivityRepository`, the
real interpreter and a real clock, on the phone. No dependency-injection
framework is introduced (§5.5).

**R7 — Host-side tests, no device.** Prompt determinism and section
structure; decode mapping including every malformed/unknown-enum case;
failure-kind mapping; the guarantee that no failure path carries user text;
and the ADR-023 containment check that `:core-ai`'s `debugApiElements`
reports no dependencies.

**R8 — On-device vertical-slice test.** An instrumented test, runnable on the
connected Pixel 10 Pro via `connectedAndroidTest`, that: seeds a canonical
activity "Mow lawn"; stores the raw capture "I cut the grass yesterday."
through the real repository; runs the real orchestrator with the real
interpreter against a real Room database on the device; and asserts the five
things Step 4 names — structured output decoded, matched to "Mow lawn" rather
than a new activity, "yesterday" resolved to the correct local date at DAY
precision (ADR-028), an occurrence persisted, and the raw capture's text
byte-identical to what was stored (ADR-007 immutability). The test must skip
with a clear message, not fail, when the device reports the model unavailable,
so the same suite is meaningful on the Pixel 7 Pro.

**R9 — Capability-unavailable path, proven.** A test showing that when the
interpreter reports `UNAVAILABLE`/`RETRYABLE`, the capture is retained, no
interpretation row is written, no occurrence is created, and the capture is
left in a state that can be processed again later (AI_INTERPRETATION_SPEC §17).
This is host-side; the domain already implements the behaviour, so this is a
regression guard on the wired-up path, not new logic.

**R10 — Measured, not asserted.** The on-device test records
interpretation latency and total capture-to-save latency (TEST_STRATEGY §12) to
the test log, so Steps 5 and 10 have a real baseline instead of a guess.
Timing values only — never capture text.

**R11 — Documentation.** ADRs for the decisions in §5 that outlive this work
item; `docs/PROJECT_STATUS.md` updated; a short note recording what the first
real model run actually did, including anything the model got wrong.

## 4. Constraints

- **ADR-023 containment.** Every ML Kit import, every `@Generable` type and all
  schema-compiler output stays in `core-ai`; `genai-*` stay `implementation`
  dependencies so nothing reaches a consumer's compile classpath.
- **ADR-010 / AGENTS.md #5.** The typed decoder is a parser, never a trust
  boundary. Model output reaches Room only through the existing validator.
- **ADR-007 / AGENTS.md #4.** The raw capture is immutable; nothing here
  rewrites captured text.
- **AGENTS.md #11 / §18.** No prompt, capture text or model output may be
  logged, and nothing leaves the device.
- **ADR-021/022.** SDK and toolchain versions are pinned; Kotlin cannot move
  past 2.3.x. New dependencies must be added to `gradle/libs.versions.toml`,
  never written as literals.
- Structured Output is alpha with no SLA. Its API surface used here was read
  directly from the pinned artifacts, not from memory (§5.1).
- The watch is irrelevant to this work item; the phone owns inference
  (AGENTS.md #2).

## 5. Decisions taken for this spec

### 5.1 The API surface is read from the pinned artifacts, not recalled

The ML Kit GenAI API shape used by this spec was extracted from the pinned
`genai-prompt:1.0.0-beta4`, `genai-common:1.0.0-beta4` and
`genai-schema:1.0.0-alpha1` artifacts in the local Gradle cache. The relevant
facts, which task packets must carry verbatim rather than re-derive:

- `Generation.getClient()` / `Generation.getClient(GenerationConfig)` returns
  `GenerativeModel`.
- `GenerativeModel` is coroutine-first: `suspend checkStatus(): Int` against
  `FeatureStatus` constants `UNAVAILABLE`/`DOWNLOADABLE`/`DOWNLOADING`/
  `AVAILABLE`; `suspend isStructuredOutputFeatureAvailable(): Boolean`;
  `download(): Flow<DownloadStatus>`; `suspend warmup()`;
  `suspend <T> generateContent(GenerateTypedContentRequest<T>):
  GenerateTypedContentResponse<T>`; `close()`.
- `GenerateTypedContentResponse<T>.candidates: List<TypedCandidate<T>>`, and
  `TypedCandidate<T>.response: T` plus a nullable `finishReason: Int`.
- A typed request is built with the `generateTypedContentRequest(request,
  KClass<T>, includeSchemaInPrompt)` builder over a `GenerateContentRequest`,
  which itself is built by `generateContentRequest(systemInstruction, textPart)
  { ... }` and carries `temperature`, `seed`, `topK`, `candidateCount`,
  `maxOutputTokens`, `enableThinking`.
- `GenAiException` exposes `errorCode: Int` and `retryDelay: Duration` — the
  retry delay is the honest signal for `RETRYABLE` vs `OTHER`.
- `@Generable(description)` and `@Guide(description, enumValues, minimum,
  maximum, minItems, maxItems)` are the schema annotations; the working
  pattern (including the `@param:` annotation target Kotlin 2.3 requires) is
  already demonstrated by `ScaffoldPlaceholderGenerable`, which this work item
  deletes once the real type lands.

### 5.2 One shot, no repair loop

If the model returns something that does not decode, the result is
`MALFORMED` and the capture goes to review. No retry-with-repair-prompt, no
second call, no "ask the model to fix its own JSON". The domain already has a
correct destination for an unusable answer, and a repair loop is exactly the
trust-the-model failure mode ADR-010 exists to prevent. Revisit only with
measured evidence from Step 5.

### 5.3 The interpreter owns the model client's lifecycle

`GenerativeModel` is closeable and warmup is expensive. The `core-ai`
implementation owns one client instance, exposes `close()`, and is constructed
once per app process — not per capture. The on-device test constructs and
closes its own.

### 5.4 Download is explicit, never implicit

If the model reports `DOWNLOADABLE`, the interpreter does **not** silently
start a large download during a capture. It returns `UNAVAILABLE`, and
downloading is a separate, explicitly-called operation on the capability API,
so the eventual Step 7 UI can ask the user first. For this work item the device
test calls it explicitly and reports progress to the test log. **Owner decision
2026-09-17: confirmed — nothing large downloads as a side effect of logging an
activity.**

### 5.5 No DI framework

Wiring is a hand-written factory function on the phone side. Hilt or Koin would
be a large, hard-to-reverse structural commitment made at the moment the app
has exactly three collaborators to wire. Revisit when the object graph is
genuinely painful, not before.

### 5.6 Where things live

- `core-ai`: `@Generable` output type, decode mapping, prompt builder + prompt
  text, capability API, `GeminiNanoActivityInterpreter`, its host-side tests.
- `app-phone`: the composition factory (R6) and the instrumented on-device
  test (R8, R9's device half).
- `core-domain`, `core-data`: **unchanged**. If this work item finds it needs
  to change either, that is a scope-change report, not a quiet edit — the
  contracts were designed for exactly this consumer one work item ago.

### 5.7 Instrumented-test infrastructure is part of this work item

The project has no `androidTest` source set or runner dependencies yet; R8
cannot exist without them. Adding the AndroidX test runner/rules to the
version catalog and an `androidTest` configuration to `app-phone` is therefore
in scope, kept to the minimum R8 needs.

## 6. Open questions

None for the owner. §5.4 was the one owner question and is settled: never
download implicitly.

For the plan to settle by running code: whether `includeSchemaInPrompt` should
be on or off (it trades prompt tokens against decode reliability and can only
be judged against a real run), and the exact temperature/topK/seed values that
make the seed cases stable.

## 7. Verification tier

Every task here touches `ai_output_validation_and_persistence` (project widen
list); R8/R9 also touch `data_persistence_migrations` (floor) and
`raw_capture_immutability` (widen). **Every task except pure documentation goes
to the verifier agent**; documentation tasks get an orchestrator spot-check.
