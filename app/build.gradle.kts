plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dogukan.cizgi"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dogukan.cizgi"
        minSdk = 24
        targetSdk = 34
        val yapi = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 100 + yapi // Guncelle.TABAN ile aynı olmalı
        versionName = "1.3.$yapi"
        buildConfigField("String", "REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }
    signingConfigs {
        create("sabit") {
            val yol = System.getenv("KS_PATH")
            if (yol != null) {
                storeFile = file(yol)
                storeType = "pkcs12"
                storePassword = System.getenv("KS_PASS")
                keyAlias = "cizgi"
                keyPassword = System.getenv("KS_PASS")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (System.getenv("KS_PATH") != null) signingConfig = signingConfigs.getByName("sabit")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    // ML Kit OCR (modeller uygulamaya gömülü, internet gerekmez)
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
