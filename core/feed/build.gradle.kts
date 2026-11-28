// SPDX-License-Identifier: GPL-3.0-or-later

// Android library (wraps rssparser for feed parsing — see `architecture.adoc` §7,
// not Stalla as CLAUDE.md's dependency table originally names). Gradle resolves
// rssparser's "android" Kotlin Multiplatform target here, which needs Robolectric
// in local unit tests for org.xmlpull.v1.XmlPullParserFactory resolution — see
// `architecture.adoc` §7 for why that's still Tier 1/2 in spirit (CLAUDE.md §4
// explicitly allows Robolectric for "Android-framework bits", headless, no emulator).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "net.drehtuer.podsilo.core.feed"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.rssparser)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
