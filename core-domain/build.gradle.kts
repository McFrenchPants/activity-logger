// Pure Kotlin/JVM library: no Android plugin, no `android { }` block, no Android
// framework on the classpath. Keeping this module off the platform keeps its unit
// tests fast and makes accidental coupling to platform types impossible.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

dependencies {
    // Resolves at the applied Kotlin plugin's own version, so it adds no new
    // version to gradle/libs.versions.toml.
    testImplementation(kotlin("test"))
}
