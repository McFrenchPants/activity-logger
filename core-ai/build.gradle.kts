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

// ---- Semantic corpus STAND-IN recorder (opt-in, host-only) ---------------------------------
//
// core-ai's own unit tests may use core-testing (the semantic corpus and its recording format)
// and the kotlinx JSON *library* -- test scope only. The serialization compiler plugin is
// deliberately NOT applied here: the stand-in client builds and reads JSON with
// Json.parseToJsonElement / buildJsonObject, which need no plugin.
dependencies {
    testImplementation(project(":core-testing"))
    testImplementation(libs.kotlinx.serialization.json)
}

// StandInCorpusRecorderTest sends every corpus case to an open-source model served LOCALLY by
// Ollama (loopback only) and writes a STAND_IN recording. It is opt-in: without
// -PsemanticStandIn=true it Assume-skips and nothing contacts any server. Optional overrides:
// -PsemanticStandIn.model=<ollama model>, -PsemanticStandIn.baseUrl=<loopback URL>.
// Run it via scripts/semantic/run-standin-corpus.sh. Properties are read through
// providers.gradleProperty so the configuration cache tracks them as inputs.
val semanticStandInEnabled: Boolean =
    providers.gradleProperty("semanticStandIn").orNull?.trim()?.equals("true", ignoreCase = true) == true
val semanticStandInModel: String? = providers.gradleProperty("semanticStandIn.model").orNull
val semanticStandInBaseUrl: String? = providers.gradleProperty("semanticStandIn.baseUrl").orNull
val semanticStandInOutput: String = rootProject.layout.projectDirectory
    .file("core-testing/src/test/resources/semantic-corpus/recordings/standin-latest.json")
    .asFile.absolutePath

tasks.withType<Test>().configureEach {
    if (semanticStandInEnabled) {
        systemProperty("semanticStandIn", "true")
        systemProperty("semanticStandIn.output", semanticStandInOutput)
        semanticStandInModel?.let { systemProperty("semanticStandIn.model", it) }
        semanticStandInBaseUrl?.let { systemProperty("semanticStandIn.baseUrl", it) }
        // A recording run talks to a live local model: never "up to date", never from cache.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        // The recorder prints exactly one summary line (counts, timings, path); show it.
        testLogging.showStandardStreams = true
    }
}
