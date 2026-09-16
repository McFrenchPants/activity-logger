# Progress — Gradle project scaffold

Task status vocabulary: `todo`, `in-progress`, `blocked`, `done`.

Plan: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).
Branch: `feature/gradle-scaffold` (off `feature/platform-validation`, which
carries the ADR-021..024 decisions this scaffold consumes and is itself not
yet merged to `main`).

This work item is **sdlc-tracked**: it has an entry in `.sdlc/state.json` and
each task is driven through a task packet under `.sdlc/task-packets/`, not the
legacy proposal-folder delegation path.

## Tasks

| Task ID | Description | Status | Notes |
|---|---|---|---|
| SS1.1 | Gradle foundation: wrapper, settings, version catalog | done | Gradle 9 requires every included project directory to exist, so the eight module dirs were created here with a `.gitkeep` each. Plugin markers still unresolved — nothing applies a plugin yet. |
| SS1.2 | `core-domain`, `core-wear-protocol` (pure Kotlin JVM) | done | Kotlin 2.3.21 plugin marker resolved for the first time. AGP and KSP still unproven. |
| SS1.3 | `core-data` (Room + KSP), `core-testing` | done | Verifier pass, all 8 criteria. AGP 9 rejects the `kotlin-android` plugin; alias removed from the catalog. |
| SS1.4 | `core-ai` (ML Kit containment), `core-speech` | blocked | Code is written, builds and is committed. Verifier **fail** on one criterion: ADR-023's "no ML Kit type in a public signature" is not satisfiable for schema-compiler *generated* code. Needs an owner decision on amending ADR-023 before it can be accepted as done. |
| SS1.5 | `app-phone`, `app-wear` | todo | Depends on SS1.4. |
| SS1.6 | Full-build verification pass + `BUILD_NOTES.md` | todo | Depends on SS1.5. |

## Session log

### 2026-09-16 — SS1.4 implemented, BLOCKED on an ADR-023 decision

`core-ai` and `core-speech` are written, build green, and are committed. The
independent verifier returned **fail** on one of ten criteria, so the task is
**not accepted as done**. The failure is a problem with the specification, not
with the code — but it is not the orchestrator's to waive.

**The finding: a `@Generable` class cannot be `internal`.** The ML Kit schema
compiler emits, into the annotated class's own package, a
`public class <Name>_GeneratedProvider` that implements
`com.google.mlkit.genai.schema.guided.GenerableProvider` and returns
`GenerableDetail<*>` from a public method. An `internal` `@Generable` class
therefore fails to compile: *"'public' property exposes its 'internal' type
argument"*. The provider is also registered for reflective discovery via
`META-INF/services`, so it cannot be hidden.

Both the implementer and the verifier reached this independently, and the
verifier confirmed it by disassembling the built AAR rather than by reading
source. The consequence is that **every future real Structured Output schema
type is necessarily part of `core-ai`'s public ABI**, and generated code
unavoidably carries ML Kit types in public signatures. ADR-023's sentence
*"`core-ai` exposes only plain domain types to the rest of the app"* is not
satisfiable as literally written while using the schema compiler at all.

**Containment nonetheless holds in substance, and this was proved, not
assumed.** `:core-ai:dependencies --configuration debugApiElements` reports
*"No dependencies"* — a consumer's compile classpath is built from
`apiElements`, so no ML Kit type reaches any consuming module at compile time.
`genai-prompt` is an `implementation` dependency and appears only on
`runtimeElements`. A consumer can see the generated class's *name* but cannot
use it: its supertype and return type are unresolvable there. The AAR is 6.6 KB
and contains no ML Kit classes. So ADR-023's actual purpose — a breaking change
in the alpha library is a single-module repair — is intact.

**Owner decision needed** (see the run summary): amend ADR-023 so containment is
stated as a mechanically checkable property (no ML Kit on `apiElements`, plus
no ML Kit in *hand-written* public signatures) rather than a rule that generated
code cannot satisfy. Until then SS1.4 stays `blocked` and SS1.5 does not start,
since `app-phone` is the first module that would consume `core-ai`.

**A second, separate concern the verifier surfaced, which is a real ADR-013 /
AGENTS.md #11 matter.** `genai-prompt` pulls in `transport-backend-cct`, which
declares `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` plus a
`TransportBackendDiscovery` service pointing at Google's Clearcut telemetry
backend; `transport-runtime` adds a scheduler service and an alarm receiver.
None of this affects `core-ai`'s own merged manifest — verified as `<uses-sdk>`
only — so it is not a violation today. But **when `app-phone` first depends on
`core-ai`, its merged manifest will gain `INTERNET` unless it is explicitly
suppressed** with `tools:node="remove"`. For a project whose whole premise is
that nothing leaves the device, that should be a deliberate decision recorded
before the AI vertical slice lands, not a surprise discovered later.

Smaller items worth carrying:

- `kotlin("test")` does not work in an Android library module — it resolves no
  framework variant and `kotlin.test.Test` is unresolved. Android modules need
  `kotlin("test-junit")`. `core-data` did not reveal this because it has no test
  source set yet.
- The R8 keep rules have never been exercised: nothing in the repo runs R8, and
  a wrong keep rule fails **only** in a release build. They are correct by
  construction, not by test. Nothing keeps the `META-INF/services` resource that
  actually discovers the provider.
- `core-speech`'s capability probe has zero coverage, since local unit tests stub
  the framework.

### 2026-09-16 — SS1.3 done (verifier pass)

`core-data` (Android library, Room via KSP) and `core-testing` (shared test
fixtures) exist. This task was routed to the independent `verifier` agent
because it falls in the `data_persistence_migrations` floor tier, and it
returned **pass** on all eight acceptance criteria with no forbidden-path
violations — including an independent re-run with `--rerun-tasks`, so the
verdict does not rest on an UP-TO-DATE build.

**AGP 9 removed the need for the `kotlin-android` plugin, and hard-fails if you
apply it.** The exact error: *"The 'org.jetbrains.kotlin.android' plugin is no
longer required for Kotlin support since AGP 9.0"*. An Android module now
applies only `android-library` / `android-application` and still compiles
Kotlin 2.3.21. The SS1.3 packet was wrong to ask for the alias; the implementer
dropped it and said so. The orchestrator then removed the dead alias from
`gradle/libs.versions.toml` **and** the matching `apply false` line from the
root build script, because leaving it there was a landmine for `app-phone` and
`app-wear` in SS1.5. Verified green afterwards. Do not add it back.

**The exported Room schema is load-bearing and is now wired correctly.**
`exportSchema = true`, and the KSP argument `room.schemaLocation` points at
`core-data/schemas/` in the source tree, not into `build/`. The emitted
`1.json` is confirmed stageable and not caught by `.gitignore`. This matters
because the project forbids destructive migrations, so future migration tests
read these files — a scaffold that quietly left `exportSchema` off would have
removed that foundation without anyone noticing.

Also resolved for the first time: **AGP 9.4.0 and KSP 2.3.12**, both clean, with
no catalog version changed and Kotlin still at 2.3.21. AGP self-serviced the
missing Android SDK build-tools, accepting the license and installing 36.0.0
into `C:\Dev\Android SDK` despite there being no `cmdline-tools` directory — so
the concern raised when this work was planned turned out not to bite.

Open items this task surfaced:

- Nothing fails if someone later flips `exportSchema` to false or repoints
  `room.schemaLocation` into `build/`. The real-schema backlog item should guard
  that invariant itself, not only the migrations.
- Room is proven only at compile/KSP time. No instrumented test has opened the
  database, so runtime Room behaviour against `minSdk` 33 is still unproven.
- A CI machine without network access to the Android SDK repo, or without
  license auto-acceptance, would fail its first Android build.

Next: SS1.4.

### 2026-09-16 — SS1.2 done

`core-domain` and `core-wear-protocol` exist as plain Kotlin/JVM modules — no
Android plugin, no `android { }` block, no dependency on any other project
module. Each has a scaffold placeholder plus one unit test.

Re-verified by the orchestrator rather than accepted on report: tests were
re-run with `--rerun-tasks` to BUILD SUCCESSFUL, and the generated JUnit XML
shows `tests="1" skipped="0" failures="0"` for each module. That check matters
here specifically because a module containing zero tests also reports BUILD
SUCCESSFUL — the passing exit code alone would not have distinguished the two.

**The Kotlin 2.3.21 plugin marker resolved from a repository for the first
time.** Until this task every plugin was `apply false`, so the catalog's
coordinates were unexercised. Kotlin is now proven; **AGP 9.4.0 and KSP 2.3.12
still are not** — SS1.3 is their first real test, and a failure there is a
finding about the catalog, not a flaky build.

The two `.gitkeep` files in these modules were deleted, now that each has real
tracked content. The other six remain and must stay until their module does.

Next: SS1.3.

### 2026-09-16 — SS1.1 done

Gradle foundation in place and independently re-verified by the orchestrator,
not accepted on the implementer's word: the wrapper jar's SHA-256 was recomputed
and matches Gradle's published `7a9ce74c…62c5d`, all eight module directories
contain only a `.gitkeep`, and `./gradlew projects` was re-run to `BUILD
SUCCESSFUL` listing all eight subprojects under root project `activity-ledger`.

**One scope change was approved during the task.** The packet originally
forbade the eight module directories, on the assumption that a Gradle
subproject with no directory is legal. It is not, as of Gradle 9: *"Configuring
project ':core-ai' without an existing directory is not allowed"* is a hard
error with no opt-out, where 8.x only warned. The implementer correctly stopped
and asked rather than writing outside its packet. The packet was widened to
exactly the eight `.gitkeep` paths — not the directories wholesale — so module
build files, sources and manifests stay owned by SS1.2–SS1.5.

Carried forward:

- **The catalog's plugin coordinates are still unproven.** Every plugin is
  `apply false` and no module applies one, so AGP 9.4.0 / Kotlin 2.3.21 /
  KSP 2.3.12 have never been resolved from a repository. A typo there would
  not have surfaced yet; SS1.2 is the first real test.
- `org.gradle.configuration-cache=true` is on before any Android module exists.
  Accepted as AGP 9 hygiene, but it is the first thing to disable if an
  SS1.2+ module turns out to be incompatible.
- The `.gitkeep` files become dead weight once each module has real tracked
  content, but removing one before then re-breaks the build.
- Nothing mechanically enforces the Kotlin 2.3.x ceiling — it is a comment at
  the top of `libs.versions.toml`. A dependency-bump tool could still break the
  build. Worth a later task.

Next: SS1.2.

### 2026-09-16 — scaffold planned

Nothing was in flight at the start of the run: PV1 is `done`, the tree was
clean, and backlog item 2 was the only unblocked entry.

Owner decisions taken before planning: package root
`com.mcfrenchpants.activityledger`; all eight `ARCHITECTURE.md` §3 modules
created now rather than deferred; verification is local build + unit tests
only, no device install in this work item.

Resolved during planning rather than recalled: Gradle 9.7.1 is the current
release, and AGP 9.4.0 is confirmed as the current stable line on Google Maven
(9.5.0 is alpha-only). Local toolchain confirmed present: Temurin JDK
21.0.12.1, Android SDK at `C:\Dev\Android SDK` with `platforms/android-36`.

Two things a later session should not be surprised by:

- **`build-tools` tops out at 35.0.1 locally** while `compileSdk` is 36, so
  the first build may need AGP to fetch a 36.x build-tools package. There is
  no `cmdline-tools` directory in that SDK, so if AGP cannot self-service the
  download it will need installing by hand.
- **The SDK path contains a space** (`C:\Dev\Android SDK`), which is a classic
  source of odd NDK/CMake failures later even though AGP itself handles it.

Next: SS1.1.
