// Android library module: owns persistence (Room) for the phone app.
//
// Every version here is read from gradle/libs.versions.toml -- no SDK level, JDK
// level or library version may be written as a literal in this file.
//
// ADR-023: no com.google.mlkit artifact may ever appear in this module. ML Kit is
// confined to core-ai; adding it here would break that containment.
// NOTE: the catalog's `kotlin-android` alias is deliberately NOT applied here.
// AGP 9 has built-in Kotlin support and hard-fails if org.jetbrains.kotlin.android
// is applied alongside it ("The 'org.jetbrains.kotlin.android' plugin is no longer
// required for Kotlin support since AGP 9.0"). See https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mcfrenchpants.activityledger.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkPhone.get().toInt()
    }

    // Host-side Room tests (Robolectric, run by `./gradlew test`, no device).
    // The tracked exported schema JSON is exposed as test assets so
    // androidx.room.testing.MigrationTestHelper can build a database at any
    // exported version. See docs/proposals/room-schema/TOOLING_NOTES.md.
    sourceSets {
        named("test") {
            assets.directories.add(layout.projectDirectory.dir("schemas").asFile.path)
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

ksp {
    // The exported Room schema JSON is tracked in git, NOT build output: this
    // project forbids destructive migrations, and future migration tests read
    // these files to diff schema versions. Keep this pointed at a source-tree
    // directory and keep exportSchema = true on every @Database.
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}

dependencies {
    implementation(project(":core-domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)

    // Resolves at the applied Kotlin plugin's own version, so it adds no new
    // version to gradle/libs.versions.toml.
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
}
