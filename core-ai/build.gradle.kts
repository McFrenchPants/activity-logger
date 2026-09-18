// Android library module: the ADR-023 containment boundary for ML Kit GenAI.
//
// This is the ONLY module in this repository permitted to name a com.google.mlkit
// artifact, import an ML Kit type, or declare a @Generable class. Structured Output
// is alpha ("not subject to any SLA or deprecation policy"), so a breaking change in
// it must stay a single-module repair. Nothing ML Kit may appear in a public
// signature of this module: outward, core-ai speaks only plain Kotlin/domain types.
//
// Every version here is read from gradle/libs.versions.toml -- no SDK level, JDK
// level or library version may be written as a literal in this file.
//
// ADR-023 also makes this module Kotlin-only: no Java sources here.
//
// NOTE: no `kotlin-android` plugin is applied (and no such alias exists in the
// catalog). AGP 9 has built-in Kotlin support and hard-fails if
// org.jetbrains.kotlin.android is applied alongside it. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mcfrenchpants.activityledger.core.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkPhone.get().toInt()

        // ADR-023: the schema compiler requires ProGuard/R8 keep rules for
        // @Generable-annotated classes. Shipping them as consumer rules means any
        // app module that consumes core-ai inherits them automatically.
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    implementation(project(":core-domain"))

    // ADR-023 pinned AI dependencies. genai-schema-compiler is a KSP annotation
    // processor (it compiles @Generable classes into a response schema), hence
    // ksp(...) rather than implementation(...).
    implementation(libs.mlkit.genai.prompt)
    ksp(libs.mlkit.genai.schema.compiler)

    // Coroutines are used directly here (Flow, suspend seams), so they are declared rather than
    // relied on arriving transitively through the ML Kit artifact. Pinned to the version ML Kit's
    // own coroutines BOM already imposes on this module's classpath, so declaring it changes
    // nothing about what resolves -- it only makes the existing dependency visible.
    implementation(libs.kotlinx.coroutines.core.genai)

    // Resolves at the applied Kotlin plugin's own version, so it adds no new
    // version to gradle/libs.versions.toml. The `-junit` variant is explicit
    // because, unlike the Kotlin JVM plugin, the Android plugin does not pick a
    // kotlin-test framework variant automatically -- plain kotlin("test") leaves
    // kotlin.test.Test unresolved here. JUnit 4 arrives transitively with it.
    testImplementation(kotlin("test-junit"))
}
