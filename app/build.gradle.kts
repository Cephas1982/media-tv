plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A release build uses the upload key from the environment when CI provides one, so every
// APK shares a signature and `adb install -r` updates in place instead of demanding an
// uninstall (which would throw away the Jellyfin sign-in the TV is using).
val uploadStore: String? = System.getenv("ANDROID_KEYSTORE")
val uploadStorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val uploadAlias: String? = System.getenv("ANDROID_KEY_ALIAS")

android {
    namespace = "app.mediatv"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mediatv"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.9"
    }

    signingConfigs {
        if (uploadStore != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = uploadStorePassword
                keyAlias = uploadAlias
                keyPassword = uploadStorePassword
            }
        }
    }

    buildTypes {
        debug {
            // Same key for debug builds: the TV keeps one identity, so an update never
            // forces an uninstall.
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
        }
        release {
            isMinifyEnabled = false
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party dependencies on purpose: framework views only, so the APK stays
// small and nothing here can drift out of date on its own.
