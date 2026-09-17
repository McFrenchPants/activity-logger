# Implementation plan — AI vertical slice (work item AI1)

Design spec: [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — approved by owner 2026-09-17.
Branch: `feature/ai-vertical-slice`, off `main` at a949941.
This work item is **sdlc-tracked** (`AI1` in `.sdlc/state.json`).

Six tasks, strictly ordered: each one compiles and is testable on its own, and
nothing after AI1.1 has to guess at the shape of what came before. Tasks AI1.1
to AI1.4 are `core-ai` only; AI1.5 is the phone side; AI1.6 is documentation.

Verification tier (spec §7): **AI1.1-AI1.5 go to the verifier agent**; AI1.6
gets an orchestrator spot-check.

Repository-wide standing rules apply to every task: no ML Kit type leaves
`core-ai` (ADR-023), model output is never trusted without the domain
validator (ADR-010), raw captures are immutable (ADR-007), and no capture
text, prompt text or model output may be logged or transmitted (AGENTS.md
#11). `core-domain` and `core-data` are read-only for this whole work item —
needing to change either is a `scope_change_requested`, not a quiet edit.

## Tasks

### AI1.1 — Structured output type and decode mapping

Spec requirements: R1, R2.

Scope. In `core-ai`: one `@Generable` data class carrying the model's answer,
with `@Guide` constraints (`description`, and `enumValues` for every field that
is a domain enum); and an internal, pure decode function from it to
`core-domain`'s `InterpretationCandidate`. Delete `ScaffoldPlaceholder.kt`,
`ScaffoldPlaceholderGenerable.kt` and `ScaffoldPlaceholderTest.kt` — the
placeholder's own documentation says to remove it when the real schema lands.
Keep the `@param:Guide` annotation-target pattern the placeholder demonstrates.

Decode rules: an unrecognised enum string, a structurally impossible
combination of *types* (not semantics), or a missing required field is a decode
failure the caller turns into `MALFORMED`; a blank optional string is absent;
whitespace is trimmed on names and temporal expressions. No candidate-ID
checking, no name-rule checking, no resolution/field consistency checking —
all of that is `InterpretationValidator`'s, and duplicating it here is a defect.

Acceptance criteria:
1. The `@Generable` type covers every field of `InterpretationCandidate` and
   uses only ADR-023's supported field types.
2. Every domain enum is constrained via `@Guide(enumValues = ...)` whose values
   match the domain enum's own constants exactly (a test asserts this against
   the enum class, so the two cannot drift).
3. The decode function is pure and has no ML Kit dependency beyond the
   annotated type itself, and does not appear in a hand-written public
   signature of `core-ai`.
4. Host-side tests cover: a full valid decode; each unknown-enum case; missing
   required field; blank/whitespace optional fields; and a case proving no
   semantic validation happens here (e.g. `EXISTING_ACTIVITY` with a null
   matched id decodes fine and is left for the validator to reject).
5. The scaffold placeholder files are gone and `:core-ai:test` passes.

### AI1.2 — Prompt: system instruction, builder, versioning

Spec requirement: R3. Depends on AI1.1 (the prompt describes the output shape).

Scope. In `core-ai`: the system instruction text, the per-capture prompt
builder, and a `promptVersion` constant. Structure per AI_INTERPRETATION_SPEC
§6: `## Task`, `## Rules`, `## Current context`, `## Candidate activities`,
`## User utterance`. Few-shot examples covering synonym match, near-neighbour
rejection, new activity, ambiguous, completed vs in-progress, and a relative
time phrase. The nine system-instruction principles of §5 must each be
present. Candidate activities are rendered with their ids and aliases so the
model can only pick an id that was offered; the prompt states explicitly that
ids must not be invented and that the time *phrase* is to be extracted rather
than a timestamp computed.

The builder is a pure function of `InterpretationInput` (raw text, capturedAt,
zoneId, candidates) returning the prompt string — no clock reads, no
randomness, no I/O.

Acceptance criteria:
1. Same input produces a byte-identical prompt across repeated calls.
2. All five §6 sections are present, in order, for any input.
3. Every candidate's id, display name and aliases appear; a capture with no
   candidates still produces a well-formed prompt.
4. The capture's raw text appears exactly once, unmodified, in the
   `## User utterance` section.
5. `promptVersion` is a source constant and is covered by a test asserting the
   prompt text and the version change together (e.g. a checksum fixture that
   must be updated deliberately).
6. No logging of prompt or capture text anywhere in the task's code.

### AI1.3 — Capability detection and model client lifecycle

Spec requirements: R4, §5.3, §5.4. Depends on AI1.1.

Scope. In `core-ai`: a plain-Kotlin capability API reporting model feature
status and structured-output availability, plus an explicit download operation,
plus ownership of a single `GenerativeModel` instance with `close()`.

API facts to use verbatim (spec §5.1): `Generation.getClient()` /
`getClient(GenerationConfig)` returns `GenerativeModel`;
`suspend checkStatus(): Int` compared against `FeatureStatus.UNAVAILABLE` /
`DOWNLOADABLE` / `DOWNLOADING` / `AVAILABLE`;
`suspend isStructuredOutputFeatureAvailable(): Boolean`;
`download(): Flow<DownloadStatus>`; `suspend warmup()`; `close()`.

Behaviour: readiness requires **both** `AVAILABLE` and structured output
supported — installation never implies readiness. Download is only ever started
by an explicit call to the download operation (owner decision, spec §5.4);
nothing on the interpretation path may trigger it.

Acceptance criteria:
1. The capability API's public surface uses plain Kotlin types only; no ML Kit
   type is nameable by a consumer.
2. `DOWNLOADABLE` and `DOWNLOADING` both report "not ready", distinctly from
   `UNAVAILABLE`, so a later UI can tell the difference.
3. Structured-output unavailability makes the device not ready even when the
   model status is `AVAILABLE`.
4. The download operation exists, is separate, and is not called from any
   interpretation code path (a test or an explicit review note demonstrates
   this).
5. One client instance per capability object; `close()` releases it and a
   second `close()` is harmless.
6. Host-side tests drive these paths through a seam that does not require a
   device (an internal indirection over the ML Kit client is acceptable and
   expected; it must stay internal to `core-ai`).

### AI1.4 — GeminiNanoActivityInterpreter

Spec requirement: R5, §5.2. Depends on AI1.1, AI1.2, AI1.3.

Scope. In `core-ai`: the `ActivityInterpreter` implementation. Builds the
request via `generateContentRequest(systemInstruction, textPart) { ... }` with
deterministic generation settings (low temperature, constrained topK,
`candidateCount` 1, a bounded `maxOutputTokens`, thinking disabled, seed fixed
where supported), wraps it with `generateTypedContentRequest(request,
OutputType::class, includeSchemaInPrompt)`, calls
`generateContent(...)`, takes the first candidate's typed response, decodes it
via AI1.1, and returns `InterpretationResult.Success` with the structured JSON
where available.

Failure mapping: not ready (AI1.3) → `UNAVAILABLE`; a `GenAiException` whose
`retryDelay` is non-zero, or a cancellation-free transient failure → `RETRYABLE`;
a decode failure or an empty candidate list → `MALFORMED`; anything else →
`OTHER`. It must never throw and never put user text or an exception message
into a result. Provenance reports interpreter version, `promptVersion` from
AI1.2, and the schema version. One shot only — no repair loop, no retry (§5.2).

Acceptance criteria:
1. `interpret` never throws for any simulated client behaviour, including a
   thrown `GenAiException`, an arbitrary `RuntimeException`, an empty candidate
   list, and an undecodable response.
2. Each failure kind is produced by the right cause, per the mapping above.
3. No `Failure` carries user text or an exception message — asserted by a test
   that feeds a distinctive capture string and an exception whose message
   contains it, then asserts neither appears anywhere in the result.
4. `Success.structuredResultJson` carries the model's own structured output
   when the client provides it, and null otherwise.
5. Provenance values are non-blank and the prompt version matches AI1.2's
   constant.
6. Exactly one `generateContent` call per `interpret` call.
7. `./gradlew :core-ai:dependencies --configuration debugApiElements` reports
   no dependencies (ADR-023 containment condition 1), recorded in the task's
   verification output.

### AI1.5 — Phone wiring and the on-device vertical slice

Spec requirements: R6, R8, R9, R10, §5.7. Depends on AI1.4.

Scope. In `app-phone` plus `gradle/libs.versions.toml`:
- A composition factory building the real `CaptureInterpretationOrchestrator`
  from the real `RoomActivityRepository` (via the existing database factory),
  the AI1.4 interpreter and a system clock. No DI framework (§5.5).
- Minimal `androidTest` infrastructure: AndroidX test runner/rules and the JUnit
  extension added to the version catalog, an `androidTest` source set and the
  test-instrumentation runner configured for `app-phone`.
- The instrumented vertical-slice test: seed canonical activity "Mow lawn";
  create the raw capture "I cut the grass yesterday." through the repository;
  run `process(captureId)`; assert the outcome and the database.
- The host-side unavailable-path guard (R9), which needs no device.

Assertions the device test must make: the capture is matched to the seeded
"Mow lawn" activity rather than proposing a new one; the occurrence's resolved
time is yesterday's local date at DAY precision (ADR-028); exactly one
occurrence exists and it points at that activity; an interpretation row was
written with provenance; and the stored raw text is character-identical to what
was inserted. Timings for the interpret call and for the whole capture-to-save
path are logged as numbers only (R10).

The test skips — with a message naming the reported capability state — when the
device is not AI-ready, so the suite is meaningful on the Pixel 7 Pro and in CI
with no device model.

Acceptance criteria:
1. `./gradlew :app-phone:assembleDebug :app-phone:assembleDebugAndroidTest`
   succeeds; the whole project still builds and all host tests pass.
2. The composition factory is the only place the three collaborators are wired,
   and it introduces no DI library.
3. The device test exists, is correctly annotated, and skips rather than fails
   when the model is unavailable.
4. The unavailable-path guard proves: raw capture retained, no occurrence, no
   interpretation row, capture still reprocessable.
5. No capture text is written to any log, including in the instrumented test's
   own output.
6. The task report states plainly whether the test was actually executed on a
   device or only built — an unexecuted device test must not be described as
   passing.

### AI1.6 — Documentation

Spec requirement: R11. Depends on AI1.5.

Scope. `docs/DECISIONS.md`: ADRs for the decisions that outlive this work item
— one-shot decoding with no repair loop (§5.2), explicit-only model download
(§5.4, owner decision), no DI framework (§5.5). `docs/ARCHITECTURE.md` §5/§6
updated so `core-ai` is described as implemented rather than conceptual;
`docs/AI_INTERPRETATION_SPEC.md` §4/§6/§19 given the implemented prompt version
and output-schema shape; `docs/PROJECT_STATUS.md` moved on. A short results
note in this proposal folder recording what the first real run on the Pixel 10
Pro actually did — including anything the model got wrong, which is the single
most useful input Step 5 will have.

Acceptance criteria:
1. New ADRs follow the existing numbering and format, and state the reason, not
   just the decision.
2. No document claims device verification that did not happen.
3. Prompt version and schema version in the docs match the source constants.
4. `BACKLOG.md` and `PROGRESS.md` are untouched (orchestrator owns those).

## Sequencing

AI1.1 → AI1.2 → AI1.3 → AI1.4 → AI1.5 → AI1.6. No two tasks may run
concurrently (`max_concurrent_implementers` is 1, and each task builds on the
previous one's types).

The run's natural stopping point is after AI1.5's code lands but before its
device test has actually been run on the Pixel 10 Pro: that needs the owner to
plug the phone in. Building through AI1.6 with the device run still pending is
acceptable **only** if AI1.6 says so honestly.
