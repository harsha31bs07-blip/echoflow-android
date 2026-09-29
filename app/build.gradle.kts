import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Secrets live in local.properties (never committed): GEMINI_API_KEY=..., optional GEMINI_MODEL=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun localProp(name: String, default: String = ""): String = (localProps.getProperty(name) ?: default).replace("\"", "")

android {
    namespace = "com.echoflow.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echoflow"
        minSdk = 30 // AccessibilityAction.ACTION_IME_ENTER
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProp("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${localProp("GEMINI_MODEL", "gemini-flash-lite-latest")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Hackathon build: sign release with the debug key so judges can sideload it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
