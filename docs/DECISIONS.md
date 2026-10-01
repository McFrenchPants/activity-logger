# Architectural and Product Decisions

This file contains lightweight ADRs.

---

## ADR-001 — Product is an activity logger, not a task planner

**Status:** Accepted

Activity Ledger records historical/current activity.

Todoist-style planning features are outside MVP.

**Reason:** Frictionless historical logging is the core value and would be diluted by planning workflows.

---

## ADR-002 — Phone is authoritative database owner

**Status:** Accepted

The Android phone owns authoritative activity history.

**Reason:** It provides sufficient compute, storage, and platform APIs while keeping Wear OS simple.

---

## ADR-003 — Wear OS is a capture peripheral

**Status:** Accepted

The watch captures input and communicates with the phone.

It does not own semantic inference or canonical activity data.

**Reason:** Lower complexity, lower battery cost, clearer consistency model.

---

## ADR-004 — Gemini Nano is primary semantic interpreter

**Status:** Accepted

MVP uses supported on-device Gemini Nano/AICore-backed Android APIs.

**Reason:** Enables semantic interpretation without cloud dependency.

---

## ADR-005 — No hidden cloud AI fallback

**Status:** Accepted

If on-device AI is unavailable, MVP does not silently send content to a remote AI provider.

**Reason:** Offline-first behavior and predictable privacy are product requirements.

---

## ADR-006 — Speech recognition is separate from semantic interpretation

**Status:** Accepted

STT produces text.

Semantic inference consumes text.

**Reason:** Different responsibilities, failure modes, test requirements, and future replaceability.

---

## ADR-007 — Raw capture is immutable evidence

**Status:** Accepted

The original recognized utterance is stored separately and never overwritten by AI normalization or correction.

**Reason:** Future auditing, repair, reclassification, and debugging depend on preserving source evidence.

---

## ADR-008 — Corrections are additive/auditable

**Status:** Accepted

User corrections change effective interpretation while preserving original interpretation and raw input.

**Reason:** Historical trust and future repair tooling.

---

## ADR-009 — Canonical activity is distinct from occurrence

**Status:** Accepted

`Mow lawn` is a reusable concept.

`Mowed lawn on Sep 15` is an occurrence.

**Reason:** Required for frequency/history analysis and synonym normalization.

---

## ADR-010 — AI output is structured and validated

**Status:** Accepted

Model results must map to typed schema and pass deterministic validation.

**Reason:** Generated prose is not a safe database contract.

---

## ADR-011 — Natural-language queries execute deterministic database operations

**Status:** Accepted

AI may interpret a question into a constrained query intent.

Application code performs the Room query.

**Reason:** Prevents hallucinated historical answers and arbitrary SQL execution.

---

## ADR-012 — Room is MVP authoritative persistence

**Status:** Accepted

Structured history is stored locally in Room.

**Reason:** Relational model, migrations, query capability, Android integration.

---

## ADR-013 — Cloud synchronization is post-MVP

**Status:** Accepted

MVP stores authoritative data only on the phone.

**Reason:** Cloud sync introduces identity, security, conflicts, cost, and complexity unrelated to proving core value.

**Consequence:** Phone is required for authoritative processing/storage in MVP.

---

## ADR-014 — Schema must be cloud-ready

**Status:** Accepted

Use stable globally unique IDs and preserve provenance.

**Reason:** Future synchronization should not require redesigning identity/history.

---

## ADR-015 — Semantic embeddings are deferred

**Status:** Accepted

MVP first uses candidate matching and Gemini Nano.

Embeddings are added only if catalog scale/performance requires them.

**Reason:** Avoid unnecessary ML subsystem complexity.

---

## ADR-016 — Semantic bugs become regression tests

**Status:** Accepted

Any discovered semantic error must be represented in the test corpus.

**Reason:** Interpretation quality should improve monotonically rather than regress unpredictably.

---

## ADR-017 — False merges are treated as high-risk

**Status:** Accepted

The system should be conservative when deciding that two phrases mean the same canonical activity.

**Reason:** Merging distinct activities silently corrupts statistics and is harder to notice than creating a duplicate.

---

## ADR-018 — Temporal precision must not be fabricated

**Status:** Accepted

Phrases such as "this morning" or "yesterday" should preserve appropriate uncertainty/precision.

**Reason:** Historical truth is more important than artificial timestamp precision.

---

## ADR-019 — Phone app uses single-activity navigation with Log as start destination

**Status:** Accepted

The phone app is one Android `Activity` hosting one Compose Navigation `NavHost`.

Top-level destinations in an M3 `NavigationBar`: **Log** (start; capture and recent history on one screen), **History**, **Ask**. Settings/diagnostics opens from the Log top bar. Occurrences open as a bottom sheet; Activity detail and Edit interpretation are pushed screens. Needs-review items are a History filter, not a destination or inbox.

Details and navigation graph: `docs/UX_VISUAL_SPEC.md` §3 D1.

**Reason:** Capture must require no navigation on launch, so the mic lives on the start destination. History and Ask stay one tap away. A single activity keeps deep links and process-death handling in one place, and keeping review inside History avoids inbox patterns excluded by UX_SPEC §15.

---

## ADR-020 — First Wear OS entry surfaces are the launcher and a complication; Tile is deferred

**Status:** Accepted

The Step 8 Wear milestone ships the app launcher entry and a watch-face complication that opens capture directly in the listening state. The complication may show a queued-capture count. A Tile is deferred to a later milestone.

Details: `docs/UX_VISUAL_SPEC.md` §3 D2.

**Reason:** A complication is one tap from the watch face the user is already looking at, which is the fastest practical capture path (WATCH_SPEC §3) and satisfies "one intentional action before speaking". It is a small data source with a tap action; a Tile adds a swipe before the tap and a separate ProtoLayout surface to build.

---

## ADR-021 — SDK baselines: phone `minSdk` 33, watch `minSdk` 34, `targetSdk` 36, `compileSdk` 37

**Status:** Accepted (amended 2026-09-16: `compileSdk` 36 → 37)

| Module | minSdk | targetSdk | compileSdk |
|---|---|---|---|
| `app-phone` | 33 (Android 13) | 36 | 37 |
| `app-wear` | 34 (Android 14 / Wear OS 5) | 36 | 37 |

Phone `minSdk` 33 is set by the speech decision in ADR-024: from API 33, `createOnDeviceSpeechRecognizer()` forces on-device recognition and fails cleanly when no local engine exists, rather than silently falling back to a network recognizer. Silent network fallback would breach ADR-005 and AGENTS.md #11, so the API level that makes the failure explicit is the floor.

Watch `minSdk` 34 matches Wear OS 5, which is what the OnePlus Watch 3 test hardware ships with. Wear OS 6 (API 36) is promised for that device but has not landed; targeting 36 as a minimum would make the only available watch untestable.

`compileSdk`/`targetSdk` 36 rather than 37: API 36 is the highest platform installed in the local SDK, and is Google Play's current target-API requirement. The primary test device runs Android 17 (API 37) and runs API 36 apps under normal forward compatibility, so nothing is lost by not chasing 37 before there is a reason to.

**Amendment (2026-09-16):** that reason arrived for `compileSdk` only. The Compose BOM pinned by ADR-022 (2026.09.00, Compose 1.12.1) declares a minimum `compileSdk` of 37 in its AAR metadata, so `app-phone` fails `checkDebugAarMetadata` at 36. `compileSdk` is therefore 37 for every Android module (they all read it from the version catalog). `targetSdk` stays 36: `compileSdk` only sets which APIs code may compile against, while `targetSdk` sets runtime behaviour and the Play requirement, so the runtime reasoning above is unchanged. Keeping the BOM pin was preferred over pinning an older BOM to stay on 36. AGP auto-installed Android SDK Platform 37.0 (revision 2); a fresh machine or CI needs that platform too.

**Reason:** Each floor is set by a hard constraint — an explicit on-device speech failure, real watch hardware, and the installed/required platform — not by a general preference for newness.

---

## ADR-022 — Kotlin 2.3.21 / KSP 2.3.12, pinned together

**Status:** Accepted

Pinned build toolchain (verified against Google Maven and Maven Central on 2026-09-15):

| Component | Version |
|---|---|
| Kotlin | 2.3.21 |
| KSP plugin | 2.3.12 |
| Android Gradle Plugin | 9.4.0 |
| JDK (toolchain) | 21 |
| Room | 2.8.5 |
| Compose BOM | 2026.09.00 |
| Wear Compose Material3 | 1.6.2 |
| androidx.activity-compose | 1.13.0 |

Kotlin is deliberately **not** on its latest release (2.4.20). KSP has no 2.4.x release — it stops at 2.3.12 — and Room's compiler requires KSP. Kotlin therefore cannot move ahead of KSP without giving up KSP-based Room compilation. Kotlin 2.3.21 is the newest version KSP 2.3.12 supports.

Do not bump Kotlin past 2.3.x until a matching KSP release exists. This constraint is independent of the AI decisions in ADR-023; Room alone creates it.

**Reason:** Pinning Kotlin ahead of KSP is a build break, not a gradual deprecation, and the annotation processors involved (Room, and the schema compiler in ADR-023) are load-bearing.

---

## ADR-023 — Gemini Nano is reached through ML Kit GenAI Prompt API; Structured Output is contained in `core-ai`

**Status:** Accepted

Pinned AI dependencies (verified on Google Maven, 2026-09-15):

| Artifact | Version | Stability |
|---|---|---|
| `com.google.mlkit:genai-prompt` | `1.0.0-beta4` | Beta |
| `com.google.mlkit:genai-schema-compiler` | `1.0.0-alpha1` | **Alpha** |

Structured Output (`@Generable` / `@Guide` data classes compiled by KSP into a response schema) is the mechanism that satisfies ADR-010's typed-schema requirement. Google offers it in alpha, "not subject to any SLA or deprecation policy", and warns that backward-incompatible changes may be made.

It is adopted anyway, under one containment rule: **every `@Generable` type, every `genai-*` import, and all schema-compiler output stay inside `core-ai`.** No other module may reference an ML Kit type. A breaking change in the alpha library is then a single-module repair, not an app-wide one.

**Amended 2026-09-16.** This originally also said "`core-ai` exposes only plain domain types to the rest of the app". That sentence was not implementable and has been replaced by the two checkable conditions below. The schema compiler generates, for each `@Generable` class, a `public <Name>_GeneratedProvider` in that class's own package which implements an ML Kit interface and is discovered reflectively via `META-INF/services`. A `@Generable` class therefore cannot be `internal` — it fails to compile with *"'public' property exposes its 'internal' type argument"* — and generated ML Kit types unavoidably appear in `core-ai`'s compiled public surface. Containment is therefore defined as:

1. **No ML Kit type on `core-ai`'s `apiElements`.** The `genai-*` artifacts are `implementation` dependencies, never `api`, so nothing ML Kit reaches a consuming module's compile classpath. This is mechanically checkable: `./gradlew :core-ai:dependencies --configuration debugApiElements` must report "No dependencies". A consumer can see a generated class's name but cannot use it, because its supertype and return types are unresolvable there.
2. **No ML Kit type in any hand-written public signature of `core-ai`.** Generated schema-compiler output is exempt; hand-written code is not.

Together these preserve the decision's actual purpose. The first condition is the one that matters and the one to check in review.

The alpha library is a *parser*, never a trust boundary. Its typed output is still subject in full to the deterministic schema and business validation required by AGENTS.md #5 and ADR-010 — including candidate-ID validation (no model-invented activity IDs) and temporal-precision checks (ADR-018). Adopting a typed decoder does not reduce the validation surface by one check.

Capability detection uses `checkStatus()` (`AVAILABLE` / `DOWNLOADABLE` / `DOWNLOADING` / `UNAVAILABLE`) plus `isStructuredOutputFeatureAvailable()`, feeding the AI-unavailable states in ARCHITECTURE.md §21. Both APIs hard-refuse on an unlocked bootloader.

Constraints carried into implementation: Kotlin-only (no Java in `core-ai`); no circular references between `@Generable` classes; supported field types are `String`, `Double`, `Float`, `Int`, `Long`, `Boolean`, `List<T>` and nested `@Generable` classes, with `description` / `enumValues` / `minimum` / `maximum` / `minItems` / `maxItems` constraints; ProGuard/R8 keep rules are required for annotated classes.

**Reason:** Constrained decoding produces a far more reliable database contract than prompting for JSON and repairing it by hand, which is the failure mode ADR-010 exists to prevent. The alpha risk is real but bounded by module isolation, and it is not a correctness risk, because nothing downstream trusts the library's output without validating it.

---

## ADR-024 — On-device speech uses the platform `SpeechRecognizer`

**Status:** Accepted

`SpeechTranscriber` is implemented with `android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer()` on both phone and watch.

The alternative considered was `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1` in "Advanced mode", which offers better transcription quality and broader language coverage. It was rejected for the MVP: it is alpha, and Advanced mode runs only on Pixel 10 and Pixel 11. The Pixel 7 Pro capability-fallback device and the OnePlus Watch 3 would both need the platform implementation regardless, so adopting it would mean maintaining two transcription paths to benefit one device.

On the primary test device the on-device recognizer role is held by `com.google.android.tts` (verified 2026-09-15), so no additional recognizer install is required.

**Amendment (2026-09-19, VC1.1 — measured on hardware).** The 2026-09-15 evidence above was a read-only property inspection: it established that a recognizer package exists and is the registered default, which is a *different question* from whether `createOnDeviceSpeechRecognizer()` works. Instrumented probes then measured the real answer on both devices:

| Device | `isRecognitionAvailable` | `isOnDeviceRecognitionAvailable` | `createOnDeviceSpeechRecognizer()` |
|---|---|---|---|
| Pixel 10 Pro (API 37) | true | **true** | created successfully |
| OnePlus Watch 3 (Wear OS 5, API 34) | true | **false** | **throws `UnsupportedOperationException`** |

Both devices have the same `com.google.android.tts` package registered as their default `RecognitionService`, and it is exactly that coincidence which made the original evidence look conclusive.

So this ADR **holds for the phone** and is implemented there (`PlatformSpeechTranscriber` in `core-speech`, VC1.2/VC1.4). It **does not hold for the watch** — see ADR-035. The adapter catches the factory's `UnsupportedOperationException` and maps it to a "no on-device engine" failure rather than letting it propagate, so a device without an engine reports honestly instead of crashing.

Revisiting this is a measurement question, not a design one: once Step 6 provides a real capture pipeline, ML Kit Advanced mode can be compared against the platform recognizer on the semantic seed corpus. Recorded as backlog item 6.

**Reason:** ADR-006 keeps transcription separate from interpretation precisely so the transcriber can be swapped later. That makes the stable, universally-available implementation the correct starting point, and defers the quality comparison to a point where it can actually be measured.

---

## ADR-025 — The apps strip the `INTERNET` permission that arrives with ML Kit; the phone keeps `ACCESS_NETWORK_STATE`

**Status:** Accepted, amended 2026-09-17

`com.google.mlkit:genai-prompt` depends transitively on Google's `datatransport` stack (`transport-backend-cct`, `transport-runtime`). Those libraries declare `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` in their own manifests, plus a `TransportBackendDiscovery` service pointing at the Clearcut telemetry backend, a `JobInfoSchedulerService` and an alarm receiver. Android's manifest merger folds all of that into any app that depends on `core-ai`, so `app-phone` would silently gain network permission the first time it uses the AI path.

The apps therefore explicitly remove permissions in their own manifests:

| App | `INTERNET` | `ACCESS_NETWORK_STATE` |
|---|---|---|
| `app-phone` | removed | **kept** (see amendment) |
| `app-wear` | removed | removed |

```xml
<!-- app-phone -->
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />

<!-- app-wear -->
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

The merged manifest of every release build must be checked for `INTERNET` as part of the privacy/logging review in the hardening step. A dependency bump is the realistic way this regresses. `app-wear`'s merged manifest must also stay free of `ACCESS_NETWORK_STATE`.

Gemini Nano inference is local and binds to AICore over IPC, so inference itself needs no network access. `ACCESS_NETWORK_STATE` only lets an app read whether the device is connected; it cannot open a connection or send anything. The privacy guarantee rests entirely on `INTERNET` being absent, and that is unchanged.

**Amendment (2026-09-17):** originally both apps also stripped `ACCESS_NETWORK_STATE`, on the reasoning that inference is local. That reasoning covered inference but not the one-time download of the on-device model, which the phone must be able to obtain when the user asks for it (ADR-031). On the Pixel 10 Pro, with `ACCESS_NETWORK_STATE` stripped, a bounded 20-minute explicit download attempt emitted no progress events at all while the app process logged being refused `ACCESS_NETWORK_STATE` by the connectivity service. With `ACCESS_NETWORK_STATE` left in place (and `INTERNET` still removed), the device then reported the model and its structured-output feature downloaded and ready. This evidence is **suggestive, not conclusive**: an earlier, inconclusive download attempt may have completed in the background in the meantime, and the two causes cannot be separated after the fact. `app-phone` keeps the permission anyway, because it cannot transmit anything, and a user whose model silently never downloads has a broken app. `app-wear` still strips both, because the watch never runs inference or downloads a model (ADR-003).

**Reason:** ADR-013 and the product's core promise are that captured activity text never leaves the device. A privacy guarantee that depends on a transitive dependency not choosing to use a permission it holds is not a guarantee. Removing the permission makes the guarantee enforced by the platform rather than by trust, and turns any future need for it into a deliberate, visible change.

---

## ADR-026 — Row identifiers are app-generated UUIDv7 text

**Status:** Accepted

Every primary key in the phone database is generated by the app, not by SQLite, and stored as canonical lowercase 36-character UUID text (`TEXT PRIMARY KEY`). Production IDs are UUIDv7, produced by the Kotlin standard library's `kotlin.uuid.Uuid.generateV7()`.

`Uuid` V7 generation is still `@ExperimentalUuidApi` at Kotlin 2.3.21 (ADR-022). The opt-in is confined to one file, `core-data`'s `UuidV7IdFactory`; the rest of the code sees plain `String` IDs. ID generation sits behind an internal `IdFactory` interface (`newId(): String`) that is injected rather than called statically, so tests supply a deterministic factory with predictable IDs.

**Evidence:** work item DB1.1 confirmed that `Uuid.generateV7()` compiles and runs at the pinned Kotlin 2.3.21: generated IDs are 36 characters, lowercase, parse with `java.util.UUID.fromString` as version 7 / variant 2, are unique, and sort non-decreasing as strings across 10,000 consecutive calls. Recorded in `docs/proposals/room-schema/TOOLING_NOTES.md` ("UUIDv7 finding").

**Fallback rule:** if a future pinned Kotlin version removes or breaks the V7 generator, switch `UuidV7IdFactory`'s implementation to random UUIDv4 via `java.util.UUID.randomUUID()`. Do not add a third-party UUID library. Stored IDs stay the same text format, so no migration is needed.

**Reason:** ADR-014 and requirement DB-004 (export-ready identifiers) require IDs that stay unique across devices without coordination, which rules out auto-increment integers. UUIDv7's leading timestamp makes inserts roughly append-only in the primary-key B-tree instead of randomly scattered, and gives rows a rough creation-order sort for free. Taking it from the standard library avoids a new dependency; the interface keeps the experimental API a one-file concern and keeps tests deterministic.

---

## ADR-027 — Interpretation confidence policy has two outcomes: auto-accept or needs review

**Status:** Accepted (owner decision 2026-09-17)

`InterpretationValidator` (`core-domain`) decides every successful interpreter answer deterministically. Each check that fails adds a `ValidationReason`; each reason either rejects or only requires review:

- **REJECT** (any rejecting reason): operation `UNSUPPORTED` or `QUERY_HISTORY`; `EXISTING_ACTIVITY` without an activity ID, with an ID that was not in the supplied candidates, or with a proposed name; `NEW_ACTIVITY` without a name or with an activity ID; `AMBIGUOUS` / `UNRESOLVED` carrying an ID or name; activity state missing. The capture orchestrator also records `INTERPRETER_OUTPUT_MALFORMED` and `INTERPRETER_FAILED` for interpreter failures `MALFORMED` and `OTHER`.
- **NEEDS_REVIEW** (review reasons only): `AMBIGUOUS` or `UNRESOLVED` activity; confidence band not `HIGH` or missing; time in the future or unresolvable (ADR-028); new name fails the new-name rules or matches an ACTIVE activity's name or alias; speech confidence below 0.5.
- **AUTO_ACCEPT**: no reasons at all. This requires confidence band `HIGH`.

Only AUTO_ACCEPT logs anything: the interpretation is stored `VALID` and accepted in one transaction. NEEDS_REVIEW stores the interpretation as `NEEDS_REVIEW`; REJECT stores it as `INVALID`; in both cases the capture becomes `NEEDS_REVIEW` and waits for the user. Interpreter failures `UNAVAILABLE` / `RETRYABLE` store no interpretation and mark the capture `FAILED_RETRYABLE`. There is no "save it and mark it uncertain" outcome.

Reason codes are persisted in `interpretations.validation_reason` as the `ValidationReason` constant names, sorted alphabetically and comma-joined (null when there are none). There are 20 constants, 11 rejecting and 9 review. Because the names are persisted text, renaming or removing one is a data change.

The minimum speech confidence is a single constant, `InterpretationValidator.DEFAULT_MINIMUM_SPEECH_CONFIDENCE` = 0.5; a NaN or out-of-range value counts as low. It and the "HIGH only" band rule are provisional and are to be calibrated against the semantic seed corpus (Step 5) and real recognizer output (Step 6).

This replaces the four suggested tiers previously sketched in AI_INTERPRETATION_SPEC.md §11 (auto-accept, auto-accept with a marker, needs review, reject/retry).

**Reason:** The owner chose "ask me" over "save it and mark it". A silently wrong entry corrupts history and is harder to notice than a question (ADR-017), while a capture waiting for review loses nothing because the raw capture and every interpretation are kept.

---

## ADR-028 — Relative time phrases resolve by a fixed deterministic rule table

**Status:** Accepted (weekday and "N days/weeks ago" rules: owner decision 2026-09-17; weekday + part-of-day rule added 2026-09-19, FX1.2)

`TemporalResolver` (`core-domain`) resolves the model's verbatim `temporalExpression` against the capture instant, doing all calendar arithmetic in the capture's IANA zone. The model never produces a time. The expression is normalized (whitespace, case, edge punctuation) and one leading filler (`on`, `at`, `about`, `around`, `approximately`) is dropped. Rules are checked in order and the first whole-phrase match wins:

| Phrase | Result | Precision |
|---|---|---|
| none / blank | capture instant | `INFERRED_NOW` |
| "just now", "just", "now", "right now", "(just) a moment/minute/second ago", "moments ago", "just finished", "just did it" | capture instant | `INFERRED_NOW` |
| "yesterday morning/afternoon/evening" | band anchor on yesterday | `APPROXIMATE` |
| "(earlier) this morning/afternoon/evening", "tonight" | see band rule below | `APPROXIMATE` |
| "earlier today" | midpoint between local midnight and the capture instant | `APPROXIMATE` |
| "last night" | yesterday 21:00 | `APPROXIMATE` |
| "today" / "yesterday" | start of that local day | `DATE_ONLY` |
| "(about) half an hour ago", "(about) N minutes/hours ago" | capture instant minus the duration | `APPROXIMATE` |
| "(about) N days/weeks ago" | start of the local day N days (or 7·N days) before today | `DATE_ONLY` |
| weekday + morning/afternoon/evening/night, optionally "last …" ("Saturday morning") | band anchor on the day the weekday rule below picks (night = 21:00, as "last night") | `APPROXIMATE` |
| weekday name, optionally "last …" | start of the most recent previous such day (never today; same weekday as today means 7 days ago) | `DATE_ONLY` |
| month name + day ("Sep 14", "14th of September") | start of the most recent such date not after today | `DATE_ONLY` |
| clock time today ("3pm", "3:30 pm", "7 this evening") | that local time today | `EXACT` |
| "tomorrow…", "next …", "later", "later today", "in N …" | `Future` | — |
| anything else | the capture's local day, no time (owner decision 2026-10-01, below) | `DATE_ONLY` |

Part-of-day bands and anchors: morning 05:00–12:00, anchor 09:00; afternoon 12:00–17:00, anchor 15:00; evening 17:00–21:00, anchor 19:00; tonight 17:00–midnight, anchor 21:00.

Refinements:

- "this <band>" / "tonight" before the band starts is `Future`; inside the band but before its anchor it resolves to the capture instant (the anchor would be in the future); otherwise to today's anchor.
- A hedged clock time ("about 3pm") is `APPROXIMATE`, not `EXACT` (ADR-018).
- A clock time later today is `Future`; a clock time that falls in a DST gap is `Unresolvable` rather than shifted.
- N accepts digits 1–999, the words one–twelve, "a"/"an" (1) and "a couple (of)" (2).
- A final guard turns any resolved instant after the capture instant into `Future`.
- `Future` and `Unresolvable` both make the interpretation need review (ADR-027); no time is guessed.

The owner chose on 2026-09-17 that a bare weekday means the most recent previous such day, and that "N days ago" / "N weeks ago" is a calendar date (`DATE_ONLY`) rather than an approximate instant.

Deliberately unresolvable: "a few days ago" (no defined count, so any date would be invented), "a month ago" (no month-length rule; a date one month back would claim precision the user did not give), and numeric dates such as "9/14" (day/month order depends on locale and would silently produce a wrong date). These go to review.

English only. Another language, or a new phrase, is an additional rule.

**Reason:** ADR-018 — temporal precision must not be fabricated. A fixed table is testable, reproducible for re-interpretation, and keeps time arithmetic out of the model and the prompt; anything outside it asks the user instead of guessing.

---

## ADR-029 — On-device AI calls are made only while the phone app is in the foreground

**Status:** Accepted (2026-09-17)

The platform permits ML Kit GenAI (Gemini Nano) calls only from the app that is currently the top foreground app; calls made from the background are refused. This is a platform rule, not a project choice, and the project designs around it:

- Every `generateContent` call (and so every `ActivityInterpreter.interpret` on the Gemini Nano adapter) is made while the phone app is in the foreground.
- Captures cannot be interpreted from a background service, `WorkManager` worker or other backgrounded code path.
- A capture that arrives while the phone app is in the background (for example from the watch, Step 8) is to be stored raw immediately (ADR-007) and interpreted when the app is next in the foreground.
- Callers must therefore check they are in the foreground before calling `CaptureInterpretationOrchestrator.process()`. As built, a refused background call is not distinguishable from other runtime errors: the adapter reports it as `OTHER`, which the orchestrator records as an `INVALID` interpretation with `INTERPRETER_FAILED` and sends the capture to review rather than marking it retryable. Recognising the refusal specifically is left for when a background capture path is actually built (Steps 6–8).

This constrains ARCHITECTURE.md §6 (capture orchestration) and §18 (background processing), and Steps 6 and 7 (phone voice capture and phone UX), which must trigger interpretation from a foreground context.

**Evidence:** on the Pixel 10 Pro (2026-09-17), with the model installed and ready, the vertical-slice instrumented test failed in 245 ms with `INTERPRETER_FAILED` while no Activity of the app was in the foreground. Launching `MainActivity` and holding it resumed around `process()` made the same test pass. Only this one pair of runs supports the rule on this project's hardware; Google's documentation states it generally.

**Reason:** Designing a background interpretation path would build on a call the platform refuses. Storing the raw capture first and interpreting on the next foreground visit loses nothing (the raw capture is the evidence) and keeps the failure mode visible instead of silent.

---

## ADR-030 — Interpretation is one-shot: no retry, no repair prompt

**Status:** Accepted (2026-09-17)

The Gemini Nano adapter makes exactly one `generateContent` call per `interpret`. There is no automatic retry, no second call, no "fix your JSON" repair prompt and no fallback. A response that is missing or does not decode into the schema's permitted values becomes interpreter failure `MALFORMED`, which the orchestrator records as an `INVALID` interpretation (`INTERPRETER_OUTPUT_MALFORMED`) and sends to review (ADR-027). A runtime error that ML Kit marks as worth retrying later becomes `RETRYABLE` (capture `FAILED_RETRYABLE`), but the adapter itself does not retry.

**Reason:** An undecodable answer already has a correct, lossless destination: review. A repair loop asks the model to rewrite its own output until it looks acceptable, which is the trust-the-model failure mode ADR-010 exists to prevent, and it spends the user's battery and latency on every bad answer. Revisit only with measured evidence from the semantic regression corpus (Step 5) that malformed answers are frequent enough to matter.

**Amendment (2026-09-19) — busy refusals are retried, briefly, outside the adapter:** On the Pixel 10 Pro, AICore refuses a back-to-back request with `GenAiException` error code `BUSY` and a *zero* retry delay, which the adapter used to report as `OTHER` — so a capture made while the model was still busy with the previous one was stored `INVALID` (`INTERPRETER_FAILED`) and sent to review as if the model had answered badly. Now `BUSY` maps to `RETRYABLE` whatever its retry delay (a positive delay still maps to `RETRYABLE`; any other code with zero delay stays `OTHER`; only the code and delay are read). The adapter still makes exactly one `generateContent` call per `interpret`. The phone pipeline (`CapturePipeline.create`) wraps it in `BusyRetryInterpreter`, which repeats only `RETRYABLE` refusals — where no inference ran — at most twice, waiting 2 s then 4 s; if all three attempts are refused the capture becomes `FAILED_RETRYABLE` as before. It never re-asks after an answer (`Success` or `MALFORMED`), nor after `OTHER` or `UNAVAILABLE`, so the no-repair rule above is unchanged. `INTERPRETER_VERSION` is not bumped: a busy refusal produces no candidate. The semantic corpus recorder keeps its own backoff and is unaffected.

---

## ADR-031 — The on-device model is downloaded only when explicitly requested

**Status:** Accepted (owner decision 2026-09-17)

Nothing large is ever downloaded as a side effect of logging an activity. `OnDeviceModelCapability.readiness()` never starts a download; a model that is not installed yields `NOT_INSTALLED`, and the interpreter then returns `Failure(UNAVAILABLE)` without touching the model (the capture is kept and marked `FAILED_RETRYABLE`). Downloading is a separate call, `OnDeviceModelCapability.download()`, whose flow is cold: nothing happens until it is explicitly collected. Nothing on the interpretation path calls it, so the Step 7 UI can tell the user the size and ask first.

**Reason:** The model is a large download over the user's connection and storage. Starting it silently because someone logged "I mowed the lawn" would spend the user's data and battery without their knowledge. Keeping download a separate, explicit call makes the choice the user's and keeps interpretation's behaviour predictable (ready or unavailable, never "maybe fetching").

---

## ADR-032 — No dependency-injection framework for now

**Status:** Accepted (2026-09-17)

The phone app wires its collaborators by hand. `CapturePipeline.create(context, clock, interpreterDecorator)` in `app-phone` is the single composition point: it builds the repository, the one `OnDeviceModelCapability`, the `GeminiNanoActivityInterpreter` and the `CaptureInterpretationOrchestrator`. `CapturePipeline` is `AutoCloseable` and is created once per app process. No Hilt, Koin or Dagger.

**Reason:** There are three collaborators to wire. A DI framework (and its annotation processing, which ADR-022 already makes delicate) would be a large, hard-to-reverse commitment to solve a problem the project does not have, and would hide the ownership rule that exactly one model client exists per process. Revisit when the object graph becomes genuinely painful to wire by hand (likely no earlier than Step 7's ViewModels or Step 8's watch transport).

---

## ADR-033 — Semantic accuracy is measured by recording model answers and replaying them

**Status:** Accepted (shape approved by owner 2026-09-17; recorded 2026-09-18)

Semantic accuracy is measured against the semantic regression corpus (TEST_STRATEGY.md §3) by a record-and-replay split at the model call. A recorder sends every corpus case to a model once and saves each structured answer or failure to a recording file. On every build, the JVM replays each recorded answer through the real `CaptureInterpretationOrchestrator`, `CandidateSelector`, `TemporalResolver` and `InterpretationValidator` and scores the result as correct, safe miss (sent to review, nothing wrong saved) or unsafe miss (something wrong saved).

- **The device recording is the only official measurement.** It is made on the phone against Gemini Nano (`SemanticCorpusRecorderTest`, run by `scripts/semantic/run-device-corpus.sh`).
- **A local stand-in model is allowed for iteration only.** An open-source model (Gemma 3n) served locally by Ollama on the developer's machine, loopback only, may be recorded to compare prompt changes quickly. Its recording is labelled `STAND_IN`, its report carries a "not the official result" banner, and it never gates the build.
- **The regression gate is a human-written baseline.** `baseline.json` lists the case ids that were correct in a reviewed device recording; replaying the latest device recording fails the build if any of them is no longer correct. Nothing writes the baseline automatically.
- **Stale recordings are refused.** Each recording stores the SHA-256 of the corpus it was made against. Replay refuses a recording whose cases are no longer in the corpus or whose candidate shortlist differs from today's, reports any corpus-hash mismatch, and the gate fails if a device recording made against a different corpus lacks a baseline case. Changing the corpus means re-recording.

Details: `docs/SEMANTIC_CORPUS.md`.

**Reason:** Gemini Nano runs only on a real phone, and only while the app is in the foreground (ADR-029); there is no emulator or JVM path to it, and the project's only AI-capable test phone is rarely available. Recording once and replaying everywhere keeps every deterministic part of the pipeline measured on every build without the phone, while keeping the official number tied to the real model. Letting a stand-in gate or stand in for the official result would measure a different model and hide real regressions.

**Revisit** if Gemini Nano (or its replacement) becomes callable from an emulator, a CI device or the JVM, so the corpus can run live on every build; if the stand-in's results turn out to track the device's poorly enough that it misleads prompt work; or if the phone becomes routinely available so recordings can be refreshed on every prompt change.

## ADR-034 — Hiding an occurrence is a visibility change, not a correction

**Status:** Accepted (decided in the UI1 implementation plan; recorded 2026-09-19 after the device pass)

Undo on the Log screen's Saved card (UX_VISUAL_SPEC D5), and later *Remove from history*, call `ActivityRepository.hideOccurrence(occurrenceId)`. It flips the occurrence's `visibility_status` from ACTIVE to HIDDEN and sets `updated_at`; hiding an already hidden occurrence is a no-op, an unknown id throws. It writes **no correction row** and never touches the raw capture or its interpretations. History (`loadHistory()`) omits a capture whose only occurrence is hidden.

*Change activity* on the same card is different: it is a real correction (`CorrectionService.correct`, source USER), recorded alongside the original interpretation.

**Reason:** `corrections` records changes to *what* an occurrence says (activity, time, state) and has no visibility columns (DATA_MODEL.md); forcing a hide through it would mean a schema change for a flag the occurrence row already carries. The audit need is met without one: the raw capture and interpretation stay, the hidden row stays, and `updated_at` shows when it was hidden. This settles the PROJECT_STATUS open decision "hide/restore ... how it is audited".

**Revisit** if a restore / "show hidden items" feature needs to say *who* hid something and why, or if sync (deferred, ADR-013) needs hides as ordered events rather than a current-state flag.

## ADR-035 — The OnePlus Watch 3 has no on-device speech recognizer; the Wear capture design needs revisiting

**Status:** Accepted (the measurement); the consequence is an open product decision

Measured on hardware 2026-09-19 (VC1.1, `app-wear`'s `PlatformSpeechRecognizerProbeTest` run against the paired OnePlus Watch 3, Wear OS 5 / API 34):

- `SpeechRecognizer.isRecognitionAvailable(context)` → true
- `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` → **false**
- `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` → **throws `UnsupportedOperationException`**

A Wear build of `com.google.android.tts` is installed and is the default `RecognitionService`, so ordinary (potentially network-backed) recognition is available. On-device recognition is not.

**What this invalidates.** The designed in-app Wear Listening screen (ADR-020, `docs/UX_VISUAL_SPEC.md` §3 D2 and §4) assumed in-app on-device recognition on the watch. It cannot be built on ADR-024's mechanism. Using the ordinary `SpeechRecognizer` instead is **not** an available fallback: it may transcribe over the network, which breaches ADR-005 and AGENTS.md #11 (no captured text leaves the device), and the privacy guarantee is the reason phone `minSdk` is 33 in the first place (ADR-021).

**Owner clarification, 2026-10-01.** The owner says staying off the Internet is a *preference to avoid where possible*, not a hard rule. So a network-backed path is no longer automatically disqualified: option 1 below is reopened, and if measurement shows it needs the network it becomes a trade-off to put to the owner (visible, disclosed, never silent -- ADR-005's actual wording is "no *hidden* cloud fallback"), not a veto. Not yet amended: AGENTS.md #11 and REQUIREMENTS ("normal capture MUST work without Internet"), which still read as hard rules; reconcile them if a network path is ever chosen. Order of preference stays: on-device first.

**Measured 2026-10-01 (WD1.3, OnePlus Watch 3, Wear OS 5): the watch CAN transcribe on-device, just not through `createOnDeviceSpeechRecognizer()`.** A debug-only probe (`DictationProbeActivity`, `app-wear/src/debug`) ran two paths with airplane mode **on** (Wi-Fi, Bluetooth and mobile off): (a) the system dictation screen (`ACTION_RECOGNIZE_SPEECH`, answered by Google's Wear keyboard, `WearRemoteInputActivity`) and (b) the ordinary `SpeechRecognizer.createSpeechRecognizer()` with `EXTRA_PREFER_OFFLINE`. Both returned accurate text, quickly, with no network. The logcat of path (b) shows Google's on-device SODA engine (`ondevice_asr_recognizer_callback`, `SodaSpeechRecognizer`, partial and final results) doing the work. Owner-reported accuracy: good on both, online and offline. Timing figures in the probe include the speaker's own time and are not latency.

**Consequence.** The designed in-app Wear Listening screen (ADR-020) is buildable. Preferred mechanism: the ordinary `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, inside the app (path b, full control of UI, shows partials), with system dictation (path a) as a fallback. **Open risk the Wear work must close:** `EXTRA_PREFER_OFFLINE` is a hint, not a guarantee, and this recognizer is not the API-33 "fails cleanly with no local engine" one (ADR-024). If the offline model were missing or removed, it could silently use the network. Per the owner clarification above that is a preference not a ban, but ADR-005 still forbids *silent* fallback: the Wear adapter must either detect the offline case (e.g. an airplane-mode check at design time plus a runtime signal if one exists) or surface it visibly. Also unverified: other watches, other Wear OS versions, other languages, and whether the offline model is always present or must be downloaded.

**Options, none yet chosen** (superseded by the measurement above: option 1 works offline; the rest are fallbacks) — this is a product decision for the Wear work item, not something to settle here:

1. **Wear's system dictation surface** (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` as an activity). One extra screen, and it is the path Wear users already know. Whether it transcribes strictly on-device is unverified and would have to be measured before it could be accepted; if it can go to the network, it is disqualified.
2. **Record on the watch, transcribe on the phone.** Keeps the privacy guarantee intact and reuses the phone's proven engine, but means moving audio over the Data Layer, holding audio on the watch until the phone acknowledges, and a noticeably slower capture.
3. **A non-voice watch capture** (a short list of recent activities, one tap). Privacy-safe and instant, but it stops being an open-ended activity logger on the wrist and only works for repeats.
4. **Different watch hardware**, if a Wear device with on-device recognition exists.

**Reason:** recorded as its own decision rather than folded into ADR-024 because the measurement is durable and the consequence is not: a future Wear OS or recognizer update could change the answer, and whoever revisits it needs to know exactly what was measured, on what, and when.

---

## ADR-036 — Speech recognition may use the internet; offline is a preference, not a rule

**Status:** Accepted (owner, 2026-10-01)

The owner's original wish was "do as much as possible offline". That grew, across ADR-005, ADR-024, ADR-025 and ADR-035, into a near-ban on network use plus warnings about it. The owner has clarified they do not care whether speech recognition uses the internet, and does not want to be told when it does.

**Decision.**
- Speech-to-text (phone and watch) may use whatever engine the platform provides, including a network one. `EXTRA_PREFER_OFFLINE` stays set as a harmless default, nothing more.
- No offline-vs-online indicator, notice, or detection is built. The watch's "Offline speech not confirmed" notice and `OfflineSpeechCheck` were removed.
- Nothing needs to detect or guard against network use; ADR-035's "open risk" is closed by this decision.

**Unchanged.** Interpretation stays on the phone's on-device model (Gemini Nano) — that is an architecture choice, not a privacy rule. Core capture must still work when there is no connection where the platform engine allows it, but a failure for lack of a network is an ordinary failure, not a policy matter. The `INTERNET` permission stripping (ADR-025) is left as is: the app itself makes no network calls; the system speech service does its own. If a feature ever needs the app to use the network directly, that is a new decision. No telemetry or logging of captured text is added (a separate engineering habit, not a privacy ban).

**Supersedes** the privacy wording in ADR-005/ADR-024/ADR-025/ADR-035 and AGENTS.md "Do not transmit captured activity text off-device" as far as speech recognition is concerned.

## ADR-037 — Watch retries use a plain system alarm, not WorkManager

**Status:** Accepted (developer decision, 2026-10-01)

The watch must resend a capture later when the phone was unreachable or an acknowledgement was lost (design spec R8: no polling, no long-running service). WorkManager would be a new library for one narrow need.

**Decision.** After every send pass the watch arms one inexact `AlarmManager.setAndAllowWhileIdle` alarm at the time the outbox says it must next act (cancelled when nothing is pending). It fires a non-exported broadcast receiver that runs one more pass. No exact-alarm permission and no new dependency. Phone acknowledgements wake the app through the Data Layer listener service, and opening the app also runs recovery and a pass.

**Consequence.** Doze may delay a retry by minutes; acceptable for a note-taking flow. If device testing (WC1.6) shows retries are too slow or unreliable, revisit with WorkManager.

**Also decided (WC1.5b).** When the watch outbox is full, the oldest FAILED capture is discarded to make room for a new one; non-failed captures are never discarded. There is no watch screen for reviewing failed captures (design spec non-goal).

### Amendment 2026-10-01 — "just" means now; an unreadable time means today, no time

Owner decision after the first real watch captures ("I just walked the dogs for about 30 minutes" went to review because the model reported the duration as the time wording).

- Immediate phrases ("just", "just now", "a minute ago") are the capture instant, `INFERRED_NOW`.
- A time phrase the table cannot read no longer forces review: it logs for the capture's local day with no time (`DATE_ONLY`).
- Safety net kept: these still need review (`Unresolvable`) — punctuation-only text, invalid explicit dates/clock times, a model non-answer ("UNRESOLVED", "n/a", "none"…), a bare numeric date/clock that was not parsed ("9/1", "at 3"), and any phrase containing a word pointing at another past day (ago, last, previous, yesterday, earlier, recently, before, day/week/month/year, weekday or month names). The first draft of this rule dropped that net and a recorded confused model answer ("UNRESOLVED") was then auto-saved with a wrong activity; the semantic regression gate caught it.
- Known leak: phrases outside the word list that mean another day ("the 5th", "over the weekend", "christmas") fall to today. Widen the list if this shows up in practice.
- Prompt version 3: the model is told durations are not time wording, with a worked example for "just".

