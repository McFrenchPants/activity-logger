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

`ActivityInterpreter` is implemented by `GeminiNanoActivityInterpreter` in `core-ai` (the Gemini Nano adapter, §13) and by `FakeActivityInterpreter` in `core-testing`. `ActivityRepository` is implemented by `RoomActivityRepository` in `core-data` (the app obtains it through `createActivityRepository(context, clock)`) and by `InMemoryActivityRepository` in `core-testing`. Integrity violations (unknown ids, non-ACTIVE target activity, `recordOutcome` on a capture that already has an occurrence) throw `IllegalArgumentException` with nothing written; `acceptInterpretation` is idempotent per capture.

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

### Implemented (`core-ai` and `app-phone`, work item AI1)

The on-device interpreter and its capability check live in `core-ai` (`...core.ai`). Every ML Kit type stays inside that module (ADR-023); its public surface is plain Kotlin and core-domain types. Abridged:

```kotlin
class OnDeviceModelCapability : AutoCloseable {            // one per process; owns the single model client
    suspend fun readiness(): ModelReadiness                  // never downloads, never throws (except cancellation)
    fun download(): Flow<ModelDownloadProgress>              // cold: nothing happens until collected (ADR-031)
}
// ModelReadiness = READY | NOT_INSTALLED | DOWNLOAD_IN_PROGRESS | UNSUPPORTED_DEVICE
//                | STRUCTURED_OUTPUT_UNSUPPORTED | CHECK_FAILED
// ModelDownloadProgress = Started(bytesToDownload) | Progress(totalBytesDownloaded) | Completed | Failed(errorCode)

class GeminiNanoActivityInterpreter(capability: OnDeviceModelCapability) : ActivityInterpreter {
    // provenance: interpreterVersion "gemini-nano-1", promptVersion "2", schemaVersion 1
    suspend fun warmUp()                                     // optional, explicit pre-load; no-op unless READY
}
```

The phone app wires these by hand (no DI framework, ADR-032):

```kotlin
class CapturePipeline : AutoCloseable {                      // exactly one per app process
    companion object {
        fun create(context, clock = Clock.systemDefaultZone(), extractorDecorator = { it }): CapturePipeline
    }
    // exposes repository, capability, extractor, taggedOrchestrator, clock; close() releases the model client
}
```

`CapturePipeline.create` is the single composition point for the capture pipeline. Creating it checks nothing and downloads nothing. The phone app has a single capture pipeline, the subject + action tag pipeline (extractor and `TaggedCaptureOrchestrator`); the older single-activity wiring was removed from the phone app (ADR-050). `extractorDecorator` is an observation seam for tests (timing only) and the identity in production.

`SpeechTranscriber` is implemented in `core-speech` (§14):

```kotlin
interface SpeechTranscriber {
    fun listen(): Flow<SpeechEvent>
}
```

A cold flow per explicit session: zero or more display-only `PartialTranscript`s, then exactly one terminal event (`FinalTranscript` or `Failed`), then completion. No Android type appears in the interface or any type in its signatures, and `SpeechFailure` is a payload-free enum, so a failure is structurally incapable of carrying the user's words (AGENTS.md #11).

### Conceptual (not built)

These remain design sketches; signatures will be fixed when they are implemented.

```kotlin
interface QueryInterpreter {
    suspend fun interpret(question: QueryInput): QueryIntent
}

interface WearCaptureTransport {
    fun observeIncomingCaptures(): Flow<WearCaptureEnvelope>
    suspend fun acknowledge(...)
}
```

## 6. Capture orchestration

Implemented by `CaptureInterpretationOrchestrator.process(captureId)`, driving the real Gemini Nano interpreter when obtained through `CapturePipeline` (§5). The raw capture is persisted beforehand by `createRawCapture`; speech transcription and acknowledgement to the watch are outside `process` and not built yet.

`process` must be called while the phone app is the top foreground app: the platform refuses on-device AI calls from the background (ADR-029). A capture that arrives while the app is backgrounded is persisted raw and processed when the app is next in the foreground. The full flow below (capture, real interpretation, auto-accept, persistence) has passed once on a real device, for one sentence (`docs/proposals/ai-vertical-slice/RESULTS.md`).

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
ActivityInterpreter.interpret           (Gemini Nano: one call, no retry or repair, ADR-030;
   |                                     not READY -> UNAVAILABLE without calling the model)
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

Each run appends at most one interpretation. A capture without an occurrence can be processed again; a capture in `NEEDS_REVIEW` is resolved by the user through `ReviewResolutionService` (§5). The stored interpretation's `matched_activity_id` is the model's id only if that id is in the catalog loaded for the run, otherwise null (it is a foreign key, and an invented id must still reach review); `structured_result_json` holds whatever raw answer text the interpreter supplies (`structuredResultJson`), and the validator judges the unmodified decoded answer. The Gemini Nano adapter always supplies null there, by design: ML Kit's typed API returns an already-decoded object, never the model's raw text, so there is nothing genuine to store (§13).

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

**Built 2026-09-19 (VC1).** The Log screen's microphone control starts one `SpeechTranscriber` session; partials are shown live and never stored; the final transcript becomes exactly one `RawCapture` with `source = PHONE_VOICE`, the recognizer's confidence in `speech_confidence` and its alternatives in `speech_alternatives_json`, and then takes the same `CaptureInterpretationOrchestrator.process` path typed text takes. There is one capture path, not two: a spoken and a typed capture of the same sentence reach the model identically and produce the same result card. A session that hears nothing persists nothing at all. Listening cannot start while the screen is stopped (ADR-029), and a session is cancelled when the screen stops or the user leaves Log.

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

Implemented in `core-ai` (§5) as `OnDeviceModelCapability` plus `GeminiNanoActivityInterpreter`:

- **Capability detection:** `readiness()` maps ML Kit's `checkStatus()` and `isStructuredOutputFeatureAvailable()` onto `ModelReadiness` (§21). Anything but `READY` makes `interpret` return `Failure(UNAVAILABLE)` without touching the model. No download is ever implicit (ADR-031).
- **Model/session setup and lifecycle:** one lazily created ML Kit client per process, owned by `OnDeviceModelCapability` and released by `close()`; the interpreter borrows it and never creates its own. `warmUp()` is an explicit, optional pre-load.
- **Prompt construction:** a fixed system instruction plus a pure, deterministic per-capture prompt (`InterpretationPrompt.kt`, prompt version `"2"`); see AI_INTERPRETATION_SPEC §6.
- **Structured output invocation:** a typed request against `InterpretationResponse` (a `@Generable` class, schema version 1), decoded by an internal pure decoder; generation settings are chosen for determinism (temperature 0, topK 1, one candidate, fixed seed, at most 256 output tokens, thinking off). Exactly one `generateContent` call per `interpret`, with no retry or repair prompt (ADR-030).
- **Error translation:** not ready -> `UNAVAILABLE`; ML Kit error with a positive retry delay -> `RETRYABLE`; no candidate or undecodable response -> `MALFORMED`; anything else -> `OTHER`. Failures carry a kind only, never a message or content.
- **Cancellation:** propagates; it is never reported as a failure.
- **Foreground only:** calls must be made while the phone app is the top foreground app (ADR-029). A backgrounded call is refused by the platform and currently surfaces as `OTHER`.
- **Raw output:** `structuredResultJson` is always null. The typed API returns an already-decoded object and never the model's raw text (confirmed on the device), and re-serialising the decoded object would fabricate an audit field.

## 14. Speech adapter

Speech implementation should likewise be replaceable.

The system may choose the best supported on-device recognition API for the target device.

The semantic pipeline only receives text and recognition metadata.

Implemented as `core-speech` (VC1.2):

- **`PlatformSpeechTranscriber`** over `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (ADR-024), exposed as a `callbackFlow`. Every recognizer call happens on the main thread, which the platform requires, via an injected `Handler`-backed runner. The recognizer is cancelled and destroyed in `awaitClose` on every exit path, including flow cancellation.
- **Error translation:** the platform's numeric `ERROR_*` codes are mapped to a small closed `SpeechFailure` enum and then discarded. A caller learns what it can act on — ask for the permission, this device has no engine, nothing was heard, the engine is busy, an engine error, cancelled — and never sees a code or a message.
- **No on-device engine is a real, handled case, not an exception.** `createOnDeviceSpeechRecognizer()` throws `UnsupportedOperationException` on the OnePlus Watch 3 (ADR-035); the adapter catches it and reports "no engine" rather than crashing.
- **Audio never crosses the seam.** The platform's audio-buffer callback is deliberately not carried across the adapter boundary, so no audio bytes exist on the app's side of it. The module contains no logging call of any kind.
- **Capability** is `SpeechCapability`: whether on-device recognition is available here and the language tag the transcriber will ask for. It starts no recognizer and downloads nothing.

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

- receiving and durably storing queued watch captures (raw, uninterpreted)
- database maintenance if later required

Do not create broad always-on services.

Semantic interpretation is **not** a background job. The platform refuses on-device AI calls unless the phone app is the top foreground app (ADR-029), so a background service or worker cannot interpret captures. Captures received in the background are stored raw and interpreted when the app next comes to the foreground; `FAILED_RETRYABLE` captures are likewise retried from the foreground. The model download is not background preparation either: it runs only when the user explicitly asks for it (ADR-031).

## 19. Cloud boundary

MVP has no cloud backend.

The platform enforces this for the phone app: its merged manifest does not contain `INTERNET`, so it cannot open a network connection. It does keep `ACCESS_NETWORK_STATE`, which only reads connectivity status and appears to be needed for the on-device model download; the watch app strips both (ADR-025, amended 2026-09-17).

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

- no raw utterance network transmission in MVP, enforced by `INTERNET` being absent from both apps' merged manifests (ADR-025); the phone app's `ACCESS_NETWORK_STATE` cannot transmit anything
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

AI capability is implemented as `OnDeviceModelCapability.readiness()` in `core-ai` (§5, §13), returning:

| `ModelReadiness` | Meaning | What the user needs |
|---|---|---|
| `READY` | model installed and structured output supported | nothing; the only state that interprets |
| `NOT_INSTALLED` | model could be fetched but is not present | an offer to download (explicit, ADR-031) |
| `DOWNLOAD_IN_PROGRESS` | a download is under way | wait |
| `UNSUPPORTED_DEVICE` | this device cannot run the model | none possible |
| `STRUCTURED_OUTPUT_UNSUPPORTED` | model installed but cannot produce structured output | none possible for now |
| `CHECK_FAILED` | the check itself failed (or the capability is closed) | try again later |

Any state other than `READY` makes interpretation fail as `UNAVAILABLE` without calling the model; the capture is kept as `FAILED_RETRYABLE`. Readiness alone is not sufficient: the app must also be the top foreground app when interpreting (ADR-029).

Speech capability is implemented as `SpeechCapability` in `core-speech` (§14, VC1.2): whether this device has an on-device recognition engine, and the language tag the transcriber asks for. It starts no recognizer and downloads nothing. On a device with no engine, voice capture must be presented as unavailable rather than broken, and typing must stay fully available — the Log screen enforces that today (VC1.3). Measured answers: the Pixel 10 Pro has an engine; the OnePlus Watch 3 refuses `createOnDeviceSpeechRecognizer()` but its ordinary `SpeechRecognizer` ran fully offline on Google's on-device engine in airplane mode (ADR-035, WD1.3).

Wear Data Layer capability detection is not built yet. Measured 2026-10-01 (WD1): the Pixel 10 Pro and OnePlus Watch 3 exchange both a message and a DataItem with the shared `applicationId` and debug signing key; see `docs/proposals/wear-data-layer/RESULTS.md`.

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
