// SPDX-License-Identifier: GPL-3.0-or-later

// The shared Compose vocabulary: the icon allow-list and the spacing invariants that both feature
// modules and :app render against. It exists so those cannot drift between screens — the whole point
// of UI.adoc §17 and §18 — and holds no state, no ports and no screens.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.drehtuer.podsilo.core.ui"
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
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // `api`, not `implementation`: PodsiloIcons returns painters built from this artifact's
    // drawables, so every consumer needs it on the compile classpath.
    api(libs.lucide.icons)
    // Artwork comes from the network in every screen that has a slot, so the loader belongs here
    // beside the icons rather than in each feature module (UI.adoc §18).
    api(libs.coil.compose)

    // The mark is a VectorDrawable, and whether it still reads once rasterised is a question only a
    // real canvas answers — Robolectric's is a no-op. Deliberately runner-only: no Compose, no
    // Espresso, so this set still runs on devices where the Compose instrumented tests currently
    // cannot (see backlog.adoc).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
