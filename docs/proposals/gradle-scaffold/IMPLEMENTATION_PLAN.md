# Implementation plan — Gradle project scaffold (backlog item 2)

Source: `docs/IMPLEMENTATION_HANDOFF.md` "Step 1 — Scaffold",
`docs/ARCHITECTURE.md` §3, and the versions pinned by ADR-021/022/023/024.

No separate design spec was written for this work: `ARCHITECTURE.md` §3 already
fixes the module set, and ADR-021..024 already fix every version and SDK
baseline. There is no open design question left for a spec to answer.

## Owner decisions taken before planning (2026-09-16)

- **Package / namespace root:** `com.mcfrenchpants.activityledger`.
  Both apps share the `applicationId` `com.mcfrenchpants.activityledger`
  (a Wear app must share the phone app's id to pair as its companion).
  Library module namespaces are
  `com.mcfrenchpants.activityledger.core.<name>`.
- **Module count:** all eight from `ARCHITECTURE.md` §3 are created now.
  Each exists to hold a boundary the architecture already mandates — in
  particular ADR-023's rule that every ML Kit type stays inside `core-ai`.
  Creating an empty module is cheap; retrofitting a boundary after code has
  crossed it is not.
- **Verification depth:** local build + unit tests only. No device install in
  this work item.

## Toolchain pinned for this scaffold

From ADR-021/022/023, plus the Gradle version resolved during planning on
2026-09-16:

| Component | Version | Source |
|---|---|---|
| Gradle (wrapper) | 9.7.1 | current release, `services.gradle.org/versions/current` |
| Android Gradle Plugin | 9.4.0 | ADR-022; confirmed current stable on Google Maven |
| Kotlin | 2.3.21 | ADR-022 — **do not bump past 2.3.x**, KSP has no 2.4.x |
| KSP | 2.3.12 | ADR-022 |
| JDK toolchain | 21 | ADR-022; Temurin 21.0.12.1 installed locally |
| Room | 2.8.5 | ADR-022 |
| Compose BOM | 2026.09.00 | ADR-022 |
| Wear Compose Material3 | 1.6.2 | ADR-022 |
| androidx.activity-compose | 1.13.0 | ADR-022 |
| `com.google.mlkit:genai-prompt` | 1.0.0-beta4 | ADR-023 — `core-ai` only |
| `com.google.mlkit:genai-schema-compiler` | 1.0.0-alpha1 | ADR-023 — `core-ai` only |
| phone `minSdk` / watch `minSdk` | 33 / 34 | ADR-021 |
| `compileSdk` / `targetSdk` | 36 / 36 | ADR-021 |

Local Android SDK lives at `C:\Dev\Android SDK` (note the space in the path).
`platforms/android-36` is installed; `build-tools` currently tops out at
35.0.1, so AGP may need to fetch a 36.x build-tools package on first build.

Any version **not** in the table above (coroutines, lifecycle, core-ktx,
Wear Data Layer, test libraries) is not yet pinned by an ADR. Whoever
introduces one must verify it actually resolves before writing it into the
catalog, and report the exact version chosen — no recalled version numbers.

## Standing constraints for every task below

- **ADR-023 containment:** no `com.google.mlkit` dependency, import, or
  `@Generable` type outside `core-ai`. This is the single most important
  invariant this scaffold sets up.
- **No semantic inference on the watch** (AGENTS.md): `app-wear` must not
  depend on `core-ai`.
- Kotlin stays on 2.3.x.
- All versions live in a single `gradle/libs.versions.toml` version catalog;
  no hardcoded version strings in module build files.
- No telemetry, analytics, crash reporting, or logging of user content.
- Modules are scaffolds, not features: no product behavior is implemented
  here beyond what is needed to compile and run an empty app.

## Tasks

### SS1.1 — Gradle foundation

**Scope:** `settings.gradle.kts`, root `build.gradle.kts`,
`gradle/libs.versions.toml`, `gradle.properties`, the Gradle 9.7.1 wrapper
(`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), `.gitignore`, and a
`local.properties` that is generated but git-ignored.

Declares all eight modules in `settings.gradle.kts`. The version catalog
carries every version in the table above. The root build file applies no
plugin directly — plugins are declared `apply false` and applied per module.

**Acceptance criteria:**

1. `./gradlew --version` reports Gradle 9.7.1 and JVM 21.
2. `./gradlew projects` lists all eight modules.
3. `gradle/libs.versions.toml` contains every version from the table above,
   with no version string duplicated in any other build file.
4. `local.properties` is git-ignored and not committed; `.gitignore` also
   covers `.gradle/`, `build/`, and `*.iml`.
5. The wrapper jar is verified against Gradle's published wrapper checksum
   (`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`)
   rather than trusted blindly.

### SS1.2 — Pure-Kotlin core modules: `core-domain`, `core-wear-protocol`

**Scope:** `core-domain/`, `core-wear-protocol/`.

Both are plain Kotlin JVM library modules (not Android libraries) — neither
needs the Android framework, and keeping them off it makes them fast to test
and impossible to accidentally couple to platform types. Each gets a package
root, a placeholder source file, and a placeholder unit test proving the test
source set is wired.

`core-wear-protocol` holds only the phone/watch message contract — data
shapes and path constants. It must not depend on Play Services or any Android
type; the transport implementation lives in the app modules.

**Acceptance criteria:**

1. `./gradlew :core-domain:test :core-wear-protocol:test` passes.
2. Neither module declares an Android plugin or an `android { }` block.
3. Neither module depends on any other project module.

**Depends on:** SS1.1.

### SS1.3 — `core-data` (Room + KSP) and `core-testing`

**Scope:** `core-data/`, `core-testing/`.

`core-data` is an Android library applying Room + KSP, depending on
`core-domain`. It contains no product schema yet — that is backlog item 3. It
must prove only that the Room compiler runs: one trivial `@Entity` + `@Dao` +
`@Database` is enough, with `exportSchema = true` pointed at a
`core-data/schemas/` directory, since destructive migrations are forbidden and
exported schemas are what migration tests will read.

`core-testing` is a shared test-fixtures module (JUnit, coroutines-test,
Truth or equivalent) that other modules consume as a `testImplementation`
dependency.

**Acceptance criteria:**

1. `./gradlew :core-data:assembleDebug` succeeds with KSP running the Room
   compiler.
2. A schema JSON is emitted under `core-data/schemas/`.
3. `:core-data` depends on `core-domain` and on no ML Kit artifact.
4. `./gradlew :core-testing:assemble` succeeds.

**Depends on:** SS1.2.

### SS1.4 — `core-ai` and `core-speech`

**Scope:** `core-ai/`, `core-speech/`.

`core-ai` is the ADR-023 containment boundary: the only module allowed the
`genai-prompt` and `genai-schema-compiler` dependencies. It exposes plain
domain types outward. It gets the ProGuard/R8 consumer keep rules ADR-023
requires for `@Generable`-annotated classes, and Kotlin-only sources.

`core-speech` wraps the platform `SpeechRecognizer` per ADR-024 behind an
interface owned by `core-domain`. No implementation beyond capability
detection and the interface wiring.

**Acceptance criteria:**

1. `./gradlew :core-ai:assembleDebug :core-speech:assembleDebug` succeeds.
2. `core-ai/consumer-rules.pro` (or equivalent, referenced from the module's
   build file) contains keep rules for the schema-compiler-annotated classes.
3. `core-speech` declares no ML Kit dependency.
4. No module other than `core-ai` names a `com.google.mlkit` artifact —
   verifiable by grepping every `build.gradle.kts` in the repo.

**Depends on:** SS1.3.

### SS1.5 — `app-phone` and `app-wear`

**Scope:** `app-phone/`, `app-wear/`.

`app-phone`: Compose application, `minSdk` 33, `applicationId`
`com.mcfrenchpants.activityledger`, depending on `core-domain`, `core-data`,
`core-ai`, `core-speech`, `core-wear-protocol`. A single empty Compose
activity showing the app name. No product UI — `docs/UX_VISUAL_SPEC.md` is a
later step's input, not this one's.

`app-wear`: Wear Compose application, `minSdk` 34, same `applicationId`,
depending on `core-domain`, `core-wear-protocol` and `core-speech` — and
explicitly **not** `core-ai`, per the no-inference-on-the-watch rule. Declares
the Wear-specific manifest bits (`android.hardware.type.watch` feature,
standalone-app metadata).

**Acceptance criteria:**

1. `./gradlew assembleDebug` builds both APKs.
2. `app-wear`'s dependency list contains no path to `core-ai` —
   verifiable via `./gradlew :app-wear:dependencies`.
3. Both manifests declare the correct `minSdk` per ADR-021 and share one
   `applicationId`.
4. Neither app declares an internet permission — nothing in the MVP goes off
   device (AGENTS.md, ADR-013).

**Depends on:** SS1.4.

### SS1.6 — Full-build verification pass

**Scope:** whatever small fixes the full-repo build surfaces, plus a short
`docs/proposals/gradle-scaffold/BUILD_NOTES.md` recording the exact versions
that ended up in the catalog (including the ones not pinned by an ADR) and any
SDK component the first build had to download.

**Acceptance criteria:**

1. `./gradlew clean build` succeeds from a clean state.
2. `./gradlew test` passes across all modules.
3. `BUILD_NOTES.md` lists every version in the final catalog and flags any
   that differ from the plan's table, with the reason.

**Depends on:** SS1.5.
