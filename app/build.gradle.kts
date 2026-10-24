plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tinker.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.tinker.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // o daemon vai como lib*.so para ser extraído em nativeLibraryDir, onde o Android permite executar
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
