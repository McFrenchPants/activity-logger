// Pure Kotlin/JVM library: no Android plugin, no `android { }` block, no Android
// framework on the classpath. This module holds only the phone/watch message
// contract -- data shapes and path constants -- so it must never depend on Play
// Services, on any Android type, or on any other project module.
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
