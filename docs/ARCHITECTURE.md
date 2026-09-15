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

Suggested conceptual interfaces:

```kotlin
interface SpeechTranscriber {
    suspend fun transcribe(request: SpeechRequest): SpeechResult
}

interface ActivityInterpreter {
    suspend fun interpret(input: InterpretationInput): InterpretationCandidate
}

interface QueryInterpreter {
    suspend fun interpret(question: QueryInput): QueryIntent
}

interface ActivityRepository {
    suspend fun persistCapture(...)
    suspend fun persistInterpretation(...)
    suspend fun createOccurrence(...)
    suspend fun correctOccurrence(...)
}

interface WearCaptureTransport {
    fun observeIncomingCaptures(): Flow<WearCaptureEnvelope>
    suspend fun acknowledge(...)
}
```

Concrete signatures should evolve during implementation.

## 6. Capture orchestration

Recommended pipeline:

```text
Raw input
   |
   v
Persist RawCapture first
   |
   v
Speech transcript available
   |
   v
Candidate activity selection
   |
   v
Gemini Nano structured inference
   |
   v
Schema validation
   |
   v
Business validation
   |
   v
Temporal resolution
   |
   v
Persist Interpretation
   |
   v
Resolve/Create CanonicalActivity
   |
   v
Persist ActivityOccurrence transactionally
   |
   v
Acknowledge result
```

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

## 16. Candidate activity selector

MVP strategy for small catalogs:

- include all active canonical activities if bounded and performant

As catalog grows:

- exact alias lookup
- normalized lexical matching
- recency/frequency candidate boost
- bounded candidate set

Embedding retrieval is deferred unless measurements show it is needed.

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
