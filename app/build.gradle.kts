import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Manager password hash baked into the APK so it locks as soon as device admin is allowed.
// Kept in lock.properties (git-ignored; the repo is public). Missing file = no built-in lock.
val lockProps = Properties().apply {
    val f = rootProject.file("lock.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.reelblock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reelblock.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "LOCK_SALT", "\"${lockProps.getProperty("lock.salt", "")}\"")
        buildConfigField("String", "LOCK_HASH", "\"${lockProps.getProperty("lock.hash", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Still signed with the debug key (so existing installs update in place), but not
            // debuggable: `adb shell run-as` could otherwise rewrite the lock state in prefs.
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
