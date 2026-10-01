// Android application module: the phone app.
//
// The phone owns the database and all semantic interpretation (AGENTS.md), so this
// is the one app module that depends on core-data and core-ai. It holds the app shell
// (Application wiring, theme, navigation) and the phone's Compose screens.
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
    // For type-safe Compose Navigation routes (@Serializable route objects).
    alias(libs.plugins.kotlin.serialization)
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

    // Host-side Compose/Robolectric tests need the app's resources (strings, fonts, drawables).
    // Robolectric's SDK is pinned in src/test/resources/robolectric.properties -- see
    // docs/proposals/room-schema/TOOLING_NOTES.md for why it is not the target SDK.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation(libs.play.services.wearable)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Host-side (JVM, no device) guard for the interpreter-unavailable path. Deliberately
    // minimal: JUnit 4 plus the shared pure-JVM fixtures, nothing else.
    testImplementation(project(":core-testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.core)
    // Test dispatcher / virtual time for the Log screen's ViewModel tests (Dispatchers.setMain).
    testImplementation(libs.kotlinx.coroutines.test)

    // Host-side (JVM, no device) UI tests: Robolectric runs the real activity and Compose
    // test rules drive it. Mirrors core-data's Robolectric setup.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

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
