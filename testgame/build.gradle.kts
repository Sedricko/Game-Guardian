// Jogo de teste com tela: o agente do PC usa o Tinker nele como num jogo de verdade (os valores certos são conhecidos)
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tinker.testgame"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.tinker.testgame"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/tinker-dev.keystore")
            storePassword = "android"
            keyAlias = "tinker"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
