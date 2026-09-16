// Shared test fixtures, consumed by other modules as `testImplementation(project(":core-testing"))`.
//
// Pure Kotlin/JVM, same shape as core-domain: no Android plugin, no `android { }`
// block. kotlin("test") is exposed as `api` so consuming modules inherit the test
// assertion API along with the fixtures.
//
// Deliberately no coroutines-test, Truth, Robolectric, androidx.test or mockk:
// none is pinned by an ADR and nothing needs them yet. The task that first
// genuinely needs one adds it then, after verifying it resolves.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    // Resolves at the applied Kotlin plugin's own version, so it adds no new
    // version to gradle/libs.versions.toml.
    api(kotlin("test"))
}
