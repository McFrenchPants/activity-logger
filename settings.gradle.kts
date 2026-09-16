pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "activity-ledger"

include(":app-phone")
include(":app-wear")
include(":core-domain")
include(":core-data")
include(":core-ai")
include(":core-speech")
include(":core-wear-protocol")
include(":core-testing")
