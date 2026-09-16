// Android library module: wraps the platform on-device speech recognizer.
//
// ADR-024: transcription uses android.speech.SpeechRecognizer's
// createOnDeviceSpeechRecognizer(), NOT com.google.mlkit:genai-speech-recognition.
// That choice was deliberate (the ML Kit path is alpha and its Advanced mode runs
// only on Pixel 10/11, while the fallback phone and the watch need the platform
// implementation anyway). This module is exactly where someone would wrongly reach
// for ML Kit: it must declare NO com.google.mlkit dependency, and under ADR-023 no
// module but core-ai may name one at all.
//
// Every version here is read from gradle/libs.versions.toml -- no SDK level, JDK
// level or library version may be written as a literal in this file.
//
// minSdk is minSdkPhone (the lower of the two floors) because this module is
// consumed by the watch app as well as the phone app, and a library's minSdk must
// not exceed its lowest consumer's.
//
// NOTE: no `kotlin-android` plugin is applied (and no such alias exists in the
// catalog). AGP 9 has built-in Kotlin support and hard-fails if
// org.jetbrains.kotlin.android is applied alongside it. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mcfrenchpants.activityledger.core.speech"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkPhone.get().toInt()
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    implementation(project(":core-domain"))

    // Resolves at the applied Kotlin plugin's own version, so it adds no new
    // version to gradle/libs.versions.toml. The `-junit` variant is explicit
    // because, unlike the Kotlin JVM plugin, the Android plugin does not pick a
    // kotlin-test framework variant automatically -- plain kotlin("test") leaves
    // kotlin.test.Test unresolved here. JUnit 4 arrives transitively with it.
    testImplementation(kotlin("test-junit"))
}
