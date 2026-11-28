// SPDX-License-Identifier: GPL-3.0-or-later

// Android library: Room needs an Android Context. Implements the four repository ports from
// :core:model (FeedRepository, EpisodeRepository, EpisodeLedgerRepository, SyncStateRepository) —
// see architecture.adoc §2/§4. Hilt @Binds wiring is deferred to :app (Tier 4c); the
// repositories are plain constructor-injectable classes so that wiring is trivial when it lands.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.drehtuer.podsilo.core.database"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // MigrationTestHelper reads the exported schemas through the *asset* manager, so the directory
    // ksp writes them to has to be on the unit-test asset path. Without this the helper fails at
    // construction with "Cannot find the schema file", which reads like a missing export rather
    // than a missing source set.
    sourceSets {
        named("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Export the schema so future migrations have a versioned baseline to diff against (CLAUDE.md §3:
// no hand-rolled migration runner — Room's, with its schema history).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
