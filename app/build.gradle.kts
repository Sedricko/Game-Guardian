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
        versionCode = 200
        versionName = "2.69.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // chave fixa: sem ela cada build do CI assina diferente e o Android não deixa atualizar por cima
    // shortcut: chave de teste no repositório, trocar por uma em GitHub Secrets antes de publicar
    signingConfigs {
        getByName("debug") {
            storeFile = file("tinker-dev.keystore")
            storePassword = "android"
            keyAlias = "tinker"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            // APK menor: R8 tira código e recursos não usados
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // o daemon vai como lib*.so para ser extraído em nativeLibraryDir, onde o Android permite executar
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 🎯 Achar sozinho: lê os números da tela no próprio aparelho (modelo embutido, sem internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
