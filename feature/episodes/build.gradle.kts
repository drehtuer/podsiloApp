// SPDX-License-Identifier: GPL-3.0-or-later

// Android library (Compose UI): S1 (podcast list), S2 (episode list) and S3 (episode detail).
// S1 lives here rather than in :app because it shares the ledger query and the episode projection
// with S2 — a badge that disagrees with the list it opens is the bug co-location prevents.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.drehtuer.podsilo.feature.episodes"
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Compose UI tests run under Robolectric — headless, no emulator (CLAUDE.md section 4 Tier 1).
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // Tier 2: the same screen on a real Android runtime (dev-environment.adoc section 6).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Pinned: Compose UI tests pull Espresso transitively, and the version they pulled predates
    // Android 16+ — it calls InputManager.getInstance(), which no longer exists, so every Compose
    // instrumented test died in onIdle() before running a line (journal.adoc, 2026-08-08).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit4)
}
