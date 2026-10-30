// Tinker 2: o app novo, um módulo por seção (ver "Guia da versão 2" na skill). Instala ao lado da 2.x (outro applicationId).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tinker.v2"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.tinker.v2"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // o mesmo tinkerd e o mesmo jogo de teste (tests/target.c) que o CI compila para a 2.x
    sourceSets["main"].jniLibs.srcDir("../app/src/main/jniLibs")
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
