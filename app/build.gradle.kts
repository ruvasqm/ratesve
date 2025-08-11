plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.example.ratesve"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ratesve"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("APP_VERSION_CODE")?.toInt() ?: 420
        versionName = System.getenv("APP_VERSION_NAME") ?: "4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFileEnv = System.getenv("RELEASE_KEYSTORE")

            val storePasswordEnv = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordEnv = System.getenv("RELEASE_KEY_PASSWORD")

            if (storeFileEnv != null && storeFileEnv.isNotEmpty() &&
                storePasswordEnv != null && storePasswordEnv.isNotEmpty() &&
                keyAliasEnv != null && keyAliasEnv.isNotEmpty() &&
                keyPasswordEnv != null && keyPasswordEnv.isNotEmpty()) {

                storeFile = project.file(storeFileEnv) // Use project.file() to resolve relative paths correctly
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            } else {
                // Optionally handle the case where env vars are not set for local non-CI builds
                // Or throw an error if they are strictly required for release builds
                println("Warning: Release signing environment variables not fully set. Build might fail or use defaults.")
                // You might have your local.properties fallback logic here if desired for non-CI local builds
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }


        debug {
            isMinifyEnabled = false
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        // kotlinCompilerExtensionVersion = "1.5.3"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (for network requests)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson (for JSON parsing)
    implementation("com.google.code.gson:gson:2.10.1")

    // Jsoup (for HTML parsing)
    implementation("org.jsoup:jsoup:1.17.2")

    // Kotlin Coroutines (for async operations, used by WorkManager KTX)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Security (for EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.0.0")

    // Biometrics (for BiometricPrompt)
    implementation("androidx.biometric:biometric:1.1.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.8.0")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
