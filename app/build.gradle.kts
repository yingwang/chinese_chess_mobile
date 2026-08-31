plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yingwang.chinesechess"
    compileSdk = 36
    // Needed only so AGP can find objcopy and strip the engine symbols into the bundle.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.yingwang.chinesechess"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "2.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../chess-release.keystore")
            storePassword = "chinesechess2024"
            keyAlias = "chess"
            keyPassword = "chinesechess2024"
        }
    }

    buildTypes {
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

}
