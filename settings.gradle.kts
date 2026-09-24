pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Set -Pechoflow.jvmOnly=true (or put it in ~/.gradle/gradle.properties) to build only the pure-Kotlin
// :core module — for machines/CI without the Android SDK or Google Maven access.
val jvmOnly = providers.gradleProperty("echoflow.jvmOnly").map { it.toBoolean() }.getOrElse(false)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (!jvmOnly) google()
    }
}

rootProject.name = "echoflow-android"

include(":core")
if (!jvmOnly) {
    include(":app")
}
