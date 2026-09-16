// Root build script: applies no plugin itself. Every plugin is declared here with
// `apply false` so its version is resolved once, from gradle/libs.versions.toml,
// and module build files apply them without repeating a version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
