# Architecture

## 1. Architectural goal

Build a local-first Android/Wear OS system where the phone owns authoritative processing and storage while the watch provides low-friction capture.

```text
Wear OS
  |
  | captured transcript / capture envelope
  v
Wear Data Layer
  |
  v
Android phone
  |
  +--> speech / transcript handling
  |
  +--> semantic interpretation (Gemini Nano)
  |
  +--> validation + activity resolution
  |
  +--> Room persistence
  |
  +--> historical query engine
```

## 2. Hard boundaries

### Phone owns

- canonical activity catalog
- Room database
- semantic interpretation
- temporal resolution
- effective activity history
- natural-language query interpretation
- correction workflows

### Watch owns

- capture UX
- local pending queue
- capture IDs
- transport state
- brief acknowledgement UI

### Watch does not own

- canonical catalog
- Gemini Nano inference
- authoritative activity database
- historical analytics

## 3. Recommended modules

A multi-module Android project is recommended once complexity justifies it.

Possible structure:

```text
:app-phone
:app-wear
:core-domain
:core-data
:core-ai
:core-speech
:core-wear-protocol
:core-testing
```

Do not create modules merely for ceremony. Boundaries matter more than module count.

## 4. Phone layers

### UI

Jetpack Compose.

Responsibilities:

- capture UI
- history
- detail
- correction
- ask-history
- settings/diagnostics

### Application/domain

Responsibilities:

- capture orchestration
- interpretation policy
- temporal resolution
- canonical activity resolution
- historical query intents
- correction commands

### Infrastructure

Responsibilities:

- Room
- speech API adapter
- ML Kit Prompt API adapter
- Wear Data Layer adapter
- clock/time-zone providers
- feature-availability provider

## 5. Key interfaces

### Implemented (`core-domain`, work item DS1)

The interpreter and repository contracts live in `core-domain` (`...core.domain.interpretation` and `...core.domain.repository`). Abridged; the Kotlin sources and their KDoc are authoritative.

```kotlin
interface ActivityInterpreter {
    val provenance: InterpreterProvenance   // interpreterVersion, promptVersion, schemaVersion
    suspend fun interpret(input: InterpretationInput): InterpretationResult
}

// InterpretationInput(rawText, capturedAt, zoneId, candidates: List<CandidateActivity>)
// InterpretationResult = Success(candidate: InterpretationCandidate, structuredResultJson: String?)
//                      | Failure(kind: InterpreterFailureKind, structuredResultJson: String?)
// InterpreterFailureKind = UNAVAILABLE | RETRYABLE | MALFORMED | OTHER

interface ActivityRepository {
    suspend fun createRawCapture(capture: NewRawCapture): String
    suspend fun getCapture(id: String): StoredCapture?
    suspend fun loadCatalog(): List<CatalogActivity>          // ACTIVE activities only
    suspend fun recordOutcome(
        captureId: String,
        interpretation: InterpretationRecord?,
        processingState: ProcessingState,                       // NEEDS_REVIEW | FAILED_RETRYABLE | FAILED_FINAL
    )
    suspend fun acceptInterpretation(
        captureId: String,
        interpretation: InterpretationRecord,
        target: ActivityTarget,                                 // Existing(activityId) | New(displayName)
        occurredAt: Instant,
        timePrecision: TimePrecision,
        activityState: ActivityState,
    ): String                                                   // occurrence id
    suspend fun applyCorrection(
        occurrenceId: String,
        changes: CorrectionChanges,
        source: CorrectionSource,
        reason: String?,
        now: Instant,
    ): CorrectionOutcome                                        // Applied(correctionId) | NothingChanged
    suspend fun getOccurrence(id: String): OccurrenceView?
    suspend fun getActivity(id: String): ActivityView?
}
```

`ActivityInterpreter` is implemented only by `FakeActivityInterpreter` in `core-testing` so far; the Gemini Nano adapter (§13) is not built. `ActivityRepository` is implemented by `RoomActivityRepository` in `core-data` (the app obtains it through `createActivityRepository(context, clock)`) and by `InMemoryActivityRepository` in `core-testing`. Integrity violations (unknown ids, non-ACTIVE target activity, `recordOutcome` on a capture that already has an occurrence) throw `IllegalArgumentException` with nothing written; `acceptInterpretation` is idempotent per capture.

Domain services (`...core.domain.services`), each taking the repository and a `java.time.Clock`:

```kotlin
class CaptureInterpretationOrchestrator(repository, interpreter, clock, selector, resolver, validator) {
    suspend fun process(captureId: String): CaptureProcessingOutcome
}
// CaptureProcessingOutcome = AutoAccepted(occurrenceId) | NeedsReview(reasons) | Rejected(reasons)
//                          | InterpreterUnavailable(kind) | AlreadyHasOccurrence

class CorrectionService(repository, clock) {
    suspend fun correct(occurrenceId: String, request: CorrectionRequest, reason: String? = null): CorrectionResult
}
// CorrectionResult = Applied(correctionId) | NothingChanged | Refused(refusal: ServiceRefusal)

class ReviewResolutionService(repository, clock) {
    suspend fun resolve(
        captureId: String,
        activity: ActivityTarget,
        time: OccurrenceTime? = null,
        activityState: ActivityState = ActivityState.COMPLETED,
    ): ResolutionResult
}
// ResolutionResult = Resolved(occurrenceId) | Refused(refusal: ServiceRefusal)
```

Supporting deterministic components: `CandidateSelector` (§16), `TemporalResolver` (§15), `InterpretationValidator` (outcome `AUTO_ACCEPT` / `NEEDS_REVIEW` / `REJECT` with `ValidationReason` codes, ADR-027), `NewActivityNameCheck` and `NameNormalizer`.

`ServiceRefusal` values: `OccurrenceNotFound`, `OccurrenceHidden`, `CaptureNotFound`, `CaptureAlreadyHasOccurrence`, `ActivityNotFound`, `ActivityNotActive`, `OccurredAfterNow`, `InvalidName(reason)`, `NameMatchesExistingActivity(activityId)`. A refusal always means nothing was written.

- `CorrectionService.correct` records source `USER`. It refuses an unknown or `HIDDEN` occurrence, a missing or non-ACTIVE target activity, a time after now, an invalid new name, and a new name whose normalized form equals the name or an alias of an ACTIVE activity (returned with that activity's id so the UI can offer it). New names are trimmed. A correction to a new activity creates it ACTIVE in the same transaction.
- `ReviewResolutionService.resolve` logs a capture that has no occurrence (awaiting review or failed): it inserts a VALID user-resolution interpretation (`interpreter_version` `"user-resolution"`, `prompt_version` `"none"`, `schema_version` 1) and accepts it; earlier interpretations are untouched. The default time is the capture instant with `INFERRED_NOW`. It applies the same activity-target and name checks as corrections and refuses a capture that already has an occurrence.

Known seam: if the matched activity is archived between `loadCatalog` and `acceptInterpretation`, `process` throws `IllegalArgumentException` with nothing written; the caller must catch it and rerun `process`.

### Conceptual (not built)

These remain design sketches; signatures will be fixed when they are implemented.

```kotlin
interface SpeechTranscriber {
    suspend fun transcribe(request: SpeechRequest): SpeechResult
}

interface QueryInterpreter {
    suspend fun interpret(question: QueryInput): QueryIntent
}

interface WearCaptureTransport {
    fun observeIncomingCaptures(): Flow<WearCaptureEnvelope>
    suspend fun acknowledge(...)
}
```

## 6. Capture orchestration

Implemented by `CaptureInterpretationOrchestrator.process(captureId)`. The raw capture is persisted beforehand by `createRawCapture`; speech transcription and acknowledgement to the watch are outside `process` and not built yet.

```text
Raw input (transcript available)
   |
   v
Persist RawCapture first (createRawCapture)
   |
   v
process(captureId):
   |
   v
Load capture ------------------------------> already has occurrence: AlreadyHasOccurrence
   |                                          (interpreter not called, nothing written)
   v
Load ACTIVE catalog (loadCatalog)
   |
   v
Candidate selection (CandidateSelector: bound 40, candidate context hash)
   |
   v
ActivityInterpreter.interpret
   |
   +-- Failure UNAVAILABLE / RETRYABLE ---> recordOutcome(no interpretation, FAILED_RETRYABLE)
   |                                          -> InterpreterUnavailable
   +-- Failure MALFORMED / OTHER ---------> recordOutcome(INVALID interpretation: operation UNSUPPORTED,
   |                                          resolution UNRESOLVED, reason INTERPRETER_OUTPUT_MALFORMED /
   |                                          INTERPRETER_FAILED; capture NEEDS_REVIEW) -> Rejected
   v
Success: temporal resolution of the model's temporal phrase (TemporalResolver)
   |
   v
Validation (InterpretationValidator)
   |
   +-- AUTO_ACCEPT --> acceptInterpretation, one transaction: VALID interpretation,
   |                   new canonical activity if needed, occurrence, capture PERSISTED
   |                   -> AutoAccepted
   +-- NEEDS_REVIEW -> recordOutcome(NEEDS_REVIEW interpretation, capture NEEDS_REVIEW)
   |                   -> NeedsReview (nothing logged)
   +-- REJECT ------> recordOutcome(INVALID interpretation, capture NEEDS_REVIEW)
                       -> Rejected (nothing logged)
```

Each run appends at most one interpretation. A capture without an occurrence can be processed again; a capture in `NEEDS_REVIEW` is resolved by the user through `ReviewResolutionService` (§5). The stored interpretation's `matched_activity_id` is the model's id only if that id is in the catalog loaded for the run, otherwise null (it is a foreign key, and an invented id must still reach review); the model's full answer is kept in `structured_result_json`, and the validator judges the unmodified answer.

Persisting raw evidence early reduces data loss on process death.

## 7. Phone-originated voice capture

For phone capture:

```text
Compose UI
  -> speech transcriber
  -> RawCapture
  -> interpretation orchestrator
  -> Room
```

Speech recognition and semantic inference remain separate even if both use Google on-device technologies.

## 8. Watch-originated capture

Recommended envelope:

```json
{
  "protocolVersion": 1,
  "captureId": "uuid",
  "rawText": "I cut the grass",
  "capturedAtEpochMillis": 0,
  "source": "WEAR_OS",
  "speechConfidence": 0.91
}
```

Watch stores this envelope durably before transmission.

Phone processing is idempotent on `captureId`.

## 9. Wear transport choice

Use Wear OS Data Layer APIs for phone/watch communication.

Transport types should be chosen based on semantics:

- urgent message for immediate transient acknowledgement where appropriate
- durable DataItem / queue semantics for data that must survive disconnection

The Data Layer is transport, not authoritative storage.

The watch's own durable queue is still required for robust capture semantics.

## 10. Local persistence

Room is the selected persistence abstraction.

Reasons:

- structured relational data
- joins between captures, interpretations, activities, occurrences, corrections
- indexed historical queries
- migrations
- test tooling
- future export/sync feasibility

## 11. Identifier strategy

Use globally safe IDs from the start.

Recommended:

- UUIDv7 if implementation support is dependable
- otherwise UUID

Do not rely on auto-increment integers as long-term synchronization identity.

Internal Room row IDs may exist for performance, but domain identity should be globally unique.

## 12. Transaction boundaries

Recommended transaction when accepting an interpretation:

1. persist validated Interpretation
2. create canonical activity if needed
3. create/update occurrence
4. set effective interpretation
5. update RawCapture processing state

This prevents partial authoritative state.

## 13. AI adapter

The Gemini Nano adapter should hide ML Kit implementation details.

Responsibilities:

- capability detection
- model/session setup
- prompt construction
- structured output invocation
- quota/error translation
- cancellation
- lifecycle management

Domain code should not import model-specific response types.

## 14. Speech adapter

Speech implementation should likewise be replaceable.

The system may choose the best supported on-device recognition API for the target device.

The semantic pipeline only receives text and recognition metadata.

## 15. Temporal resolver

Create a dedicated deterministic temporal component.

Responsibilities:

- resolve `yesterday`
- resolve day names such as `Saturday`
- resolve `this morning`
- resolve `about an hour ago`
- preserve precision/uncertainty

Do not bury temporal arithmetic inside prompts or ViewModels.

Implemented as `TemporalResolver` in `core-domain` (`...core.domain.temporal`), returning `TemporalResolution.Resolved(occurredAt, precision)`, `Future` or `Unresolvable`; its fixed rule table is recorded in ADR-028.

## 16. Candidate activity selector

MVP strategy for small catalogs:

- include all active canonical activities if bounded and performant

As catalog grows:

- exact alias lookup
- normalized lexical matching
- recency/frequency candidate boost
- bounded candidate set

Embedding retrieval is deferred unless measurements show it is needed.

Implemented as `CandidateSelector` in `core-domain` (`...core.domain.candidates`): the whole ACTIVE catalog when it fits the bound (default 40); otherwise whole-phrase name/alias hits in the normalized text first, then most recent occurrence; output ordered by normalized name and identified by a SHA-256 candidate context hash stored on the interpretation. No fuzzy, stemmed, frequency or semantic matching yet.

## 17. Historical query engine

Pipeline:

```text
Question
  -> QueryInterpreter
  -> QueryIntent
  -> validate
  -> repository/domain query
  -> deterministic result
  -> present
```

The model never executes SQL.

## 18. Background processing

MVP should minimize background complexity.

Use background/retry mechanisms only for:

- queued watch captures
- retryable on-device inference preparation
- database maintenance if later required

Do not create broad always-on services.

## 19. Cloud boundary

MVP has no cloud backend.

However, schema and identifiers must support later sync.

Future cloud architecture should synchronize local domain records rather than replacing local ownership.

Potential future shape:

```text
Phone Room
   <-sync->
Cloud database
   <->
Web review / repair tool
```

This is post-MVP.

## 20. Security and privacy

- no raw utterance network transmission in MVP
- no cloud AI fallback
- avoid user text in logs
- use app-private storage
- respect Android backup behavior intentionally; decide whether sensitive DB is included
- future cloud sync must require explicit security design

## 21. Device capability

The phone app must detect:

- Gemini Nano / AICore feature availability
- Structured Output availability
- speech capability availability
- Wear Data Layer availability

If semantic AI capability is unavailable, the application may still preserve raw captures but must not pretend interpretation succeeded.

## 22. Supported platform baseline

Implementation agent must choose and document:

- `minSdk`
- `targetSdk`
- required Pixel/device capability for full MVP
- Wear OS minimum API
- Kotlin/Compose versions
- Room version
- ML Kit GenAI Prompt API version

Do not copy versions blindly from this document. Verify official current documentation.

## 23. Dependency policy

Prefer:

- AndroidX
- Google Play services Wearable
- ML Kit
- Kotlin coroutines

Avoid unnecessary:

- cloud SDKs
- networking frameworks
- third-party AI clients
- heavy DI frameworks
- analytics SDKs

## 24. Observability

Local developer diagnostics should include:

- processing stage
- elapsed inference time
- model availability state
- structured validation failures
- transport state
- database errors

Do not log raw user text in production by default.

## 25. Official platform notes at documentation baseline

As of September 2026 official documentation indicates:

- Gemini Nano runs on-device through Android AICore.
- ML Kit Prompt API supports structured output on supported configurations.
- feature availability should be checked at runtime.
- Wear OS Data Layer provides phone/watch synchronization and can buffer DataItems until reconnection.
- Room provides a structured SQLite abstraction with migration support.

Implementation must re-verify these assumptions against current official docs before pinning APIs.
