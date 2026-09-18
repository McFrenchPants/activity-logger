// Android application module: the phone app.
//
// The phone owns the database and all semantic interpretation (AGENTS.md), so this
// is the one app module that depends on core-data and core-ai. At this stage it is
// a scaffold only: a single empty activity, no product UI.
//
// Every version here is read from gradle/libs.versions.toml -- no SDK level, JDK
// level or library version may be written as a literal in this file.
//
// ADR-023: no ML Kit artifact coordinate may appear in this file. ML Kit reaches the
// app only transitively, through core-ai.
//
// NOTE: no `kotlin-android` plugin is applied (and no such alias exists in the
// catalog). AGP 9 has built-in Kotlin support and hard-fails if
// org.jetbrains.kotlin.android is applied alongside it. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mcfrenchpants.activityledger"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Must match app-wear's applicationId: the Wear companion pairs by it.
        applicationId = "com.mcfrenchpants.activityledger"
        minSdk = libs.versions.minSdkPhone.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Runs the on-device vertical-slice test (src/androidTest). It is the only
        // instrumented test here and it SKIPS itself (org.junit.Assume) on any device whose
        // on-device model is not ready, so it is safe to run anywhere.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-ai"))
    implementation(project(":core-speech"))
    implementation(project(":core-wear-protocol"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Host-side (JVM, no device) guard for the interpreter-unavailable path. Deliberately
    // minimal: JUnit 4 plus the shared pure-JVM fixtures, nothing else.
    testImplementation(project(":core-testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.core)

    // On-device test only. Only what the vertical-slice test actually uses: the runner that
    // executes it, the AndroidJUnit4 runner class, ApplicationProvider, JUnit 4 assertions and
    // runBlocking. Catalog entries androidx-test-core and androidx-test-ext-junit are reused.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    // The semantic corpus recorder (semantic/SemanticCorpusRecorderTest): the corpus, its
    // classpath resource, CorpusInterpretationInput and the recording format.
    androidTestImplementation(project(":core-testing"))
}
