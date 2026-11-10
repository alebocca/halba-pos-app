plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.halba.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.halba.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Sandbox di halba: in produzione https://api.halba.ai
        buildConfigField("String", "HALBA_API", "\"https://api.sandbox.halba.ai\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.stripe:stripeterminal:6.0.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3")
}
