# Room host-side test tooling (DB1.1)

Audience: agents working on core-data. Everything below was established by
running it on this toolchain (Gradle 9.7.1, AGP 9.4.0 built-in Kotlin, Kotlin
2.3.21, KSP 2.3.12, Room 2.8.5, JDK 21, compileSdk 37, minSdk 33) on a Windows
host, 2026-09-16.

## Test command

```
./gradlew :core-data:test      # core-data only (runs testDebugUnitTest)
./gradlew test                 # all modules
```

No device or emulator. With AGP 9 only the debug unit-test variant runs for
core-data (`core-data/build/test-results/testDebugUnitTest/`).

## Approach: Robolectric + androidx.test + room-testing

- Runner: `@RunWith(AndroidJUnit4::class)` (androidx.test.ext:junit), backed by
  Robolectric. Room runs against Android's framework SQLite as emulated by
  Robolectric, i.e. the same `android.database.sqlite` stack the phone uses.
- Robolectric SDK pinned in `core-data/src/test/resources/robolectric.properties`
  (`sdk=35`).
- Exported schema JSON (`core-data/schemas`) is added as a **test assets**
  directory in `core-data/build.gradle.kts`
  (`android.sourceSets.named("test") { assets.directories.add(...) }`) with
  `testOptions.unitTests.isIncludeAndroidResources = true`, so
  MigrationTestHelper finds `<db fqcn>/<version>.json`.
- In-memory databases: always open via `inMemoryTestDatabase<T>()`
  (`core-data/src/test/.../TestDatabases.kt`). It adds a callback that runs
  `PRAGMA foreign_keys = ON` on every open, because Room only enables foreign
  keys itself when the schema declares one. Proven by
  `PlaceholderRoomToolingTest.inMemoryDatabaseEnforcesForeignKeys` (pragma
  returns 1 and a dangling-reference insert is rejected).
- Migration tests: use the **driver-based** constructor
  `MigrationTestHelper(instrumentation, file = targetContext.getDatabasePath(name), driver = AndroidSQLiteDriver(), databaseClass = X::class)`
  and `createDatabase(version)` / `runMigrationsAndValidate(version, migrations)`,
  which return `androidx.sqlite.SQLiteConnection`. `AndroidSQLiteDriver` comes
  from androidx.sqlite:sqlite-framework 2.6.2, already transitive from
  room-runtime (no catalog entry added). Note: a connection from
  MigrationTestHelper does not have foreign keys switched on automatically.
- Proof tests live in `PlaceholderRoomToolingTest` and target the scaffold
  placeholder database; delete them with the placeholder.

### What was tried and failed

1. `sdk=36` (android-all-instrumented 16-robolectric-13921718-i7) under JDK 21:
   every Robolectric test failed in setup with
   `RuntimeException: Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?`
   caused by `IllegalAccessException: ... cannot access class jdk.internal.access.SharedSecrets (in module java.base) because module java.base does not export jdk.internal.access to unnamed module`
   (from `com.android.internal.os.ApplicationSharedMemory.create`, new in API 36).
   Fixed by `sdk=35` rather than adding `--add-exports` JVM flags. minSdk is 33,
   so 35 is within the supported range.
2. `MigrationTestHelper(Instrumentation, Class)` (SupportSQLite path) failed on
   Windows with
   `IllegalArgumentException: This driver is configured to open a database named 'placeholder-migration-test' but 'C:\...\databases\placeholder-migration-test' was requested.`
   Cause (confirmed with javap on sqlite-framework-android 2.6.2):
   `SupportSQLiteDriver.open` compares names via `substringAfterLast('/')`,
   which does not split a backslash path. Would likely pass on Linux/macOS, but
   the driver-based constructor works on every host, so use that.
3. `import kotlin.test.Test` does not resolve in core-data unit tests
   (`kotlin("test")` resolves without a JUnit binding here). Use
   `org.junit.Test`; `kotlin.test.assert*` functions are fine.
4. `android.sourceSets.getByName("test").assets.srcDir(...)` fails at
   configuration under AGP 9.4.0 with a `ClassCastException`
   (`DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`).
   Use `sourceSets { named("test") { assets.directories.add(path) } }`.

The bundled-SQLite JVM driver alternative (androidx.sqlite:sqlite-bundled) was
not needed and not tried: Robolectric met both requirements, and it tests the
framework SQLite the app actually ships on.

## New catalog entries (gradle/libs.versions.toml)

| Alias | Coordinates | Version | Provenance |
|---|---|---|---|
| `junit4` | junit:junit | 4.13.2 | Latest in repo1.maven.org maven-metadata.xml |
| `robolectric` | org.robolectric:robolectric | 4.17 | Latest stable in repo1.maven.org maven-metadata.xml (after 4.17-beta-4) |
| `androidx-test-core` | androidx.test:core | 1.7.0 | Latest stable in dl.google.com/android/maven2 maven-metadata.xml |
| `androidx-test-ext-junit` | androidx.test.ext:junit | 1.3.0 | Latest stable in dl.google.com/android/maven2 maven-metadata.xml |

`androidx-room-testing` (Room 2.8.5) already existed and is now used as
`testImplementation`. Metadata queried 2026-09-16; all versions confirmed by a
passing `./gradlew :core-data:test`. No existing version line changed.

Robolectric downloads `org.robolectric:android-all-instrumented:15-robolectric-13954326-i7`
from Maven Central on first test run (then cached in the Gradle cache).

## UUIDv7 finding

`kotlin.uuid.Uuid.generateV7()` exists and works at Kotlin 2.3.21 (still
`@ExperimentalUuidApi`). Evidence:

- `core-data/src/main/.../id/UuidV7IdFactory.kt` calls
  `Uuid.generateV7().toString()` and `:core-data:compileDebugKotlin` succeeds.
- `IdFactoryTest.productionIdsAreCanonicalLowercaseUuidV7`: 1,000 IDs, each 36
  chars, lowercase, round-trips through `java.util.UUID.fromString`,
  `version() == 7`, `variant() == 2`. Passed.
- `IdFactoryTest.productionIdsSortNonDecreasingAsStrings`: 10,000 consecutive
  IDs sort non-decreasing as strings and are unique. Passed.

So production uses V7; the `java.util.UUID.randomUUID()` fallback was not
needed. `@OptIn(ExperimentalUuidApi::class)` is confined to
`UuidV7IdFactory.kt`. `IdFactory` (internal `fun interface`, `newId(): String`)
is in `com.mcfrenchpants.activityledger.core.data.id`; the deterministic
`DeterministicIdFactory` (`00000000-0000-7000-8000-000000000001`, ...) is in the
test source set.
