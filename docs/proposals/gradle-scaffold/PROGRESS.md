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
| SS1.2 | `core-domain`, `core-wear-protocol` (pure Kotlin JVM) | todo | Depends on SS1.1. |
| SS1.3 | `core-data` (Room + KSP), `core-testing` | todo | Depends on SS1.2. Verifier tier: data persistence. |
| SS1.4 | `core-ai` (ML Kit containment), `core-speech` | todo | Depends on SS1.3. Verifier tier: ADR-023 containment. |
| SS1.5 | `app-phone`, `app-wear` | todo | Depends on SS1.4. |
| SS1.6 | Full-build verification pass + `BUILD_NOTES.md` | todo | Depends on SS1.5. |

## Session log

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
