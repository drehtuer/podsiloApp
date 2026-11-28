// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: WorkManager, the SAF write, and the foreground-service notification all need
// Android APIs. The parts that don't — the HTTP download into the app cache, the tag rewriting, and
// the pipeline that sequences them — are plain-JVM classes sitting behind the DownloadTarget port
// (`architecture.adoc` §11), so they are unit-testable with MockWebServer and a temp directory, with no
// emulator. See architecture.adoc §8/§11 for why tagging must happen before the SAF copy.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "net.drehtuer.podsilo.core.download"
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

    lint {
        // `SpecifyForegroundServiceType` cannot be satisfied from inside this module, and is a false
        // positive here rather than a rule being waived. It asks that the manifest override
        // WorkManager's `SystemForegroundService` entry with `android:foregroundServiceType`, which
        // `:app`'s manifest does — with a long comment on the hard process crash it prevents. A
        // library module is linted against its own manifest, which cannot see `:app`'s, so the check
        // fires on `DownloadWorker`'s `ForegroundInfo` no matter what any manifest says.
        //
        // Suppressed at the module rather than at the call site so this note is the only place the
        // reasoning lives, and because the thing lint is guarding is asserted directly by two tests:
        // `:app`'s `ForegroundServiceManifestTest` (Robolectric, reads the merged manifest) and
        // `PlatformSurfacesTest` (on a real device, reads the installed one). Delete this block if
        // either of them goes, not before.
        disable += "SpecifyForegroundServiceType"
    }
}

dependencies {
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    implementation(project(":core:model"))
    implementation(project(":core:naming"))
    implementation(libs.jaudiotagger)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // Only for `String.toUri()` in SafDownloadTarget, which lint's UseKtx asks for. Not a new
    // dependency for the project: :app already ships core-ktx, so the APK does not grow by a byte —
    // which is what makes the KTX call cheaper here than the warning was.
    implementation(libs.androidx.core.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.documentfile)
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
