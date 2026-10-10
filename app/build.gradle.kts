import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Reads app/google-services.json (gitignored, like the keystore) for the online game's
    // Firebase project; it holds a client entry for the release id and for the .preview one.
    id("com.google.gms.google-services")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.yingwang.chinesechess"
    compileSdk = 36
    // Needed only so AGP can find objcopy and strip the engine symbols into the bundle.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.yingwang.chinesechess"
        minSdk = 24
        targetSdk = 36
        versionCode = 24
        versionName = "2.5.0"
    }

    // Release signing comes from keystore.properties in the repo root (gitignored) or from
    // the CHESS_KEYSTORE_* environment variables; nothing secret lives in this file.
    signingConfigs {
        create("release") {
            storeFile = file(signingValue("storeFile", "CHESS_KEYSTORE") ?: "../chess-release.keystore")
            storePassword = signingValue("storePassword", "CHESS_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "CHESS_KEY_ALIAS") ?: "chess"
            keyPassword = signingValue("keyPassword", "CHESS_KEY_PASSWORD")
        }
    }

    buildTypes {
        // Debug builds install beside the store version under their own id and name, so a
        // preview can go on a phone without touching the real app or its saved games.
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                // The engine binaries ship unstripped; hand their symbols to Play so native
                // crashes come back with function names instead of raw addresses.
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            // The engine is a real executable, not a library we dlopen, so it has to exist as
            // a file on disk under nativeLibraryDir. Without legacy packaging it stays inside
            // the APK and there is no path to hand to ProcessBuilder.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }


    androidResources {
        noCompress += "nnue"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Playing a friend online: anonymous sign-in and the Realtime Database the web version uses.
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; the tests read the endgame data with the real one.
    testImplementation("org.json:json:20231013")

}
