# Gradle scaffold -- build notes (SS1.6)

Audience: future agents. Recorded 2026-09-16 on branch `feature/gradle-scaffold`
at plan revision 1616d21. All resolved versions below come from real
`./gradlew ... dependencies` output from this run, not from memory.

## Verification result

- `./gradlew clean build`: BUILD SUCCESSFUL (461 actionable tasks). Nothing failed
  and no fixes were needed. Includes `lint`/`lintDebug`, `lintVitalAnalyzeRelease`
  and `assembleRelease` for both apps.
- `./gradlew test`: BUILD SUCCESSFUL. JUnit XML counts (tests/failures/errors/skipped):
  - core-ai (`testDebugUnitTest`): 3/0/0/0
  - core-speech (`testDebugUnitTest`): 1/0/0/0
  - core-domain (`test`): 1/0/0/0
  - core-testing (`test`): 1/0/0/0
  - core-wear-protocol (`test`): 1/0/0/0
  - app-phone, app-wear, core-data: no test sources (`testDebugUnitTest NO-SOURCE`).
- Only build output noise: two manifest-merger warnings in app-wear
  (`tools:node="remove"` on INTERNET and ACCESS_NETWORK_STATE with nothing to
  remove). They are harmless; the removals are the intended guard against
  those permissions ever being merged in.

## Final catalog (`gradle/libs.versions.toml`)

| Entry | Catalog | Resolved (debugRuntimeClasspath) | Plan | Differs? |
|---|---|---|---|---|
| gradle | 9.7.1 | -- | 9.7.1 | no |
| agp | 9.4.0 | -- | 9.4.0 | no |
| kotlin | 2.3.21 | kotlin-stdlib 2.3.21 (both apps) | 2.3.21 | no |
| ksp | 2.3.12 | -- | 2.3.12 | no |
| jdkToolchain | 21 | -- | 21 | no |
| compileSdk | 37 | -- | 36 | **yes** (see below) |
| targetSdk | 36 | -- | 36 | no |
| minSdkPhone | 33 | -- | 33 | no |
| minSdkWatch | 34 | -- | 34 | no |
| room (runtime, ktx, compiler, testing) | 2.8.5 | room-runtime / room-ktx 2.8.5 (app-phone) | 2.8.5 | no |
| composeBom | 2026.09.00 | compose-bom 2026.09.00 (app-phone) | 2026.09.00 | no |
| compose-ui (BOM-managed) | none | 1.12.1 (app-phone) | -- | n/a |
| compose-material3 (BOM-managed) | none | 1.4.0 (app-phone) | -- | n/a |
| wearComposeMaterial3 | 1.6.2 | 1.6.2 (app-wear) | 1.6.2 | no |
| activityCompose | 1.13.0 | 1.13.0 (both apps) | 1.13.0 | no |
| mlkitGenaiPrompt | 1.0.0-beta4 | genai-prompt 1.0.0-beta4 (+ genai-common 1.0.0-beta4) in app-phone | 1.0.0-beta4 | no |
| mlkitGenaiSchemaCompiler | 1.0.0-alpha1 | genai-schema-compiler 1.0.0-alpha1 on core-ai `kspDebugKotlinProcessorClasspath`; genai-schema 1.0.0-alpha1 at runtime in app-phone | 1.0.0-alpha1 | no |

## Differences from the plan and other notes

1. **compileSdk 37 instead of 36.** Compose BOM 2026.09.00 (Compose 1.12.1)
   requires compileSdk >= 37. ADR-021 was amended 2026-09-16. targetSdk stays 36.
2. **Two Compose versions across apps.** app-phone resolves Compose UI 1.12.1
   via the BOM. app-wear does not use the BOM; Wear Compose Material3 1.6.2
   pulls Compose UI/runtime 1.9.2. Not a plan change, but the apps are not on the
   same Compose line.

## SDK components downloaded into the local Android SDK (`C:\Dev\Android SDK`)

- build-tools 36.0.0 -- during SS1.3.
- Android SDK Platform 37.0 revision 2 (`platforms/android-37.0`) -- during SS1.5.
- SS1.6 (this run): no downloads seen in the build, test or dependency output.
