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
| SS1.1 | Gradle foundation: wrapper, settings, version catalog | todo | No dependencies. |
| SS1.2 | `core-domain`, `core-wear-protocol` (pure Kotlin JVM) | todo | Depends on SS1.1. |
| SS1.3 | `core-data` (Room + KSP), `core-testing` | todo | Depends on SS1.2. Verifier tier: data persistence. |
| SS1.4 | `core-ai` (ML Kit containment), `core-speech` | todo | Depends on SS1.3. Verifier tier: ADR-023 containment. |
| SS1.5 | `app-phone`, `app-wear` | todo | Depends on SS1.4. |
| SS1.6 | Full-build verification pass + `BUILD_NOTES.md` | todo | Depends on SS1.5. |

## Session log

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
