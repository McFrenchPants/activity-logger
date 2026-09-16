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

Revisiting this is a measurement question, not a design one: once Step 6 provides a real capture pipeline, ML Kit Advanced mode can be compared against the platform recognizer on the semantic seed corpus. Recorded as backlog item 6.

**Reason:** ADR-006 keeps transcription separate from interpretation precisely so the transcriber can be swapped later. That makes the stable, universally-available implementation the correct starting point, and defers the quality comparison to a point where it can actually be measured.

---

## ADR-025 — The apps strip the `INTERNET` permission that arrives with ML Kit

**Status:** Accepted

`com.google.mlkit:genai-prompt` depends transitively on Google's `datatransport` stack (`transport-backend-cct`, `transport-runtime`). Those libraries declare `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` in their own manifests, plus a `TransportBackendDiscovery` service pointing at the Clearcut telemetry backend, a `JobInfoSchedulerService` and an alarm receiver. Android's manifest merger folds all of that into any app that depends on `core-ai`, so `app-phone` would silently gain network permission the first time it uses the AI path.

`app-phone` and `app-wear` therefore explicitly remove them in their own manifests:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

The merged manifest of every release build must be checked for `INTERNET` as part of the privacy/logging review in the hardening step. A dependency bump is the realistic way this regresses.

Gemini Nano inference is local and binds to AICore over IPC, so nothing in the MVP needs network access to work. The permissions are an artifact of a shared telemetry library, not a requirement of the feature.

**Reason:** ADR-013 and the product's core promise are that captured activity text never leaves the device. A privacy guarantee that depends on a transitive dependency not choosing to use a permission it holds is not a guarantee. Removing the permission makes the guarantee enforced by the platform rather than by trust, and turns any future need for it into a deliberate, visible change.
