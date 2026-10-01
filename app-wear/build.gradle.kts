// Android application module: the Wear OS companion app.
//
// AGENTS.md: the watch never runs inference. This module therefore depends only on
// core-domain, core-wear-protocol and core-speech, and must NEVER depend on core-ai
// -- directly or through any other module. At this stage it is a scaffold only: a
// single empty activity, no product UI.
//
// Every version here is read from gradle/libs.versions.toml -- no SDK level, JDK
// level or library version may be written as a literal in this file.
//
// ADR-023: no ML Kit artifact coordinate may appear in this file.
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
    // namespace may differ from applicationId; applicationId is what pairing uses.
    namespace = "com.mcfrenchpants.activityledger.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Must match app-phone's applicationId: a Wear companion shares the phone app's id.
        applicationId = "com.mcfrenchpants.activityledger"
        minSdk = libs.versions.minSdkWatch.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Runs the one-off on-device speech-recognizer probe (src/androidTest). It is a
        // measurement, not a regression gate: instrumented tests never run during
        // `./gradlew test`, and the probe reports "unavailable" as a pass, not a failure.
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
    implementation(project(":core-wear-protocol"))
    implementation(project(":core-speech"))
    implementation(libs.play.services.wearable)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)

    // On-device test only, and only what the recognizer probe actually uses: the runner that
    // executes it, the AndroidJUnit4 runner class, ApplicationProvider/InstrumentationRegistry
    // and JUnit 4 assertions. Catalog aliases are shared with app-phone.
    //
    // Deliberately NOT androidx.test:rules / GrantPermissionRule: RECORD_AUDIO is granted out
    // of band with `adb shell pm grant` before the run.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
