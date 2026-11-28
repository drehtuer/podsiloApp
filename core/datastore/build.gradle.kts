// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: Jetpack DataStore needs an Android Context, and the app-password cipher uses the
// Android Keystore. Implements SettingsRepository from :core:model. The Keystore-backed cipher is
// behind the AppPasswordCipher interface (`architecture.adoc` §2) so the DataStore serialisation is
// Robolectric-testable with a fake cipher; the real Keystore binding is exercised on-device only.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.drehtuer.podsilo.core.datastore"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    implementation(project(":core:model"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // No Robolectric: DataStore-Preferences runs over a plain temp file with no Android Context, so
    // the settings serialisation is testable on the plain JVM runner. The Keystore cipher (the only
    // Android-runtime dependency here) is faked in tests — see `architecture.adoc` §2.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
