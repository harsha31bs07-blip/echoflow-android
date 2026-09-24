// Plugins go on the root buildscript classpath so the Kotlin and Android Gradle plugins share
// one classloader. The Android plugin (and Google Maven) is only required when :app is built.
buildscript {
    val jvmOnly = (project.findProperty("echoflow.jvmOnly") as String?)?.toBoolean() ?: false
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (!jvmOnly) google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.21")
        if (!jvmOnly) classpath("com.android.tools.build:gradle:8.7.3")
    }
}
