plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tinker.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.tinker.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    // o daemon vai como lib*.so para ser extraído em nativeLibraryDir, onde o Android permite executar
    packaging { jniLibs { useLegacyPackaging = true } }
}
