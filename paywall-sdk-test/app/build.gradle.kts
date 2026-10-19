plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "paywall.testtool"
    compileSdk = 36

    defaultConfig {
        applicationId = "paywall.testtool"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Merchant uygulamaları gibi R8 açık: SDK'nın consumer kuralları da test edilmiş olur.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Test aracı: debug imzasıyla kurulabilsin.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Bir merchant'ın yazdığı tek Paywall satırı. Masterpass SDK'nın içinde gömülü gelir.
    implementation(libs.paywall.sdk)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // Ağ sekmesinde JSON'u girintili göstermek için.
    implementation(libs.gson)
}
