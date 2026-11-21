plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (GitHub Actions secrets).
val ksFile = System.getenv("NETPULSE_KEYSTORE")
val hasSigning = ksFile != null && file(ksFile).exists()

android {
    namespace = "com.abusaeed.netpulse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abusaeed.netpulse"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.1"
    }

    signingConfigs {
        // Fixed debug key kept in the repo, so every GitHub build can update the
        // previously installed app (a new random key each build would block updates).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasSigning) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("NETPULSE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NETPULSE_KEY_ALIAS")
                keyPassword = System.getenv("NETPULSE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    androidResources {
        generateLocaleConfig = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
