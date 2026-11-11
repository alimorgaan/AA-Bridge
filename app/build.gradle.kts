plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Short commit this build was made from -- for the in-app update checker to compare against. */
fun gitSha(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=10", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

android {
    namespace = "dev.caraalink"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.alimorgaan.aabridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    signingConfigs {
        // Committed on purpose (see .gitignore) so every debug build --
        // local, CI, anyone's machine -- signs with the same key. Without
        // this, each environment's auto-generated ~/.android/debug.keystore
        // differs, so swapping a locally-built APK for a CI-built one (or
        // vice versa) fails to install with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
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
    implementation(project(":protocol"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
}
