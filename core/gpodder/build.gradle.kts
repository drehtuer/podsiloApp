// SPDX-License-Identifier: GPL-3.0-or-later

// Pure JVM, not com.android.library — resolves architecture.adoc §12 open decision #3.
// Nothing in this module's job (Retrofit/OkHttp client, DTOs, DTO<->domain mapping) touches an
// Android API, so a JVM module compiles the "no Android" property in rather than relying on
// review to catch a stray import. Its MockWebServer tests then run on the plain `test` task with
// no Robolectric and no Android test runner. See `architecture.adoc` §2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)

    // The probe checks real server timestamps against the parser that lives in :core:sync.
    testImplementation(project(":core:sync"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

/**
 * A manual probe against a real Nextcloud — see `ManualNextcloudProbe.kt`. Not part of `check`, not
 * a test, and it talks to the network, which every test in this project is forbidden from doing.
 *
 *   ./gradlew :core:gpodder:nextcloudProbe -Phost=cloud.example.org
 */
tasks.register<JavaExec>("nextcloudProbe") {
    group = "verification"
    description = "Runs Login Flow v2 against a real Nextcloud and lists its subscriptions. Read-only."
    mainClass.set("net.drehtuer.podsilo.core.gpodder.ManualNextcloudProbeKt")
    classpath = sourceSets["test"].runtimeClasspath
    standardOutput = System.out
    args =
        listOf(
            project.findProperty("host")?.toString().orEmpty(),
            project.findProperty("handoff")?.toString().orEmpty(),
            // Writes are opt-in and name the account they may touch: -Pwrite=<loginName>
            project.findProperty("write")?.toString().orEmpty(),
            // Read-only: dump the newest N actions in full — see reportRecent().
            project.findProperty("recent")?.toString().orEmpty(),
            // -Prevoke=yes: delete the app password this run was granted, then prove it is dead.
            project.findProperty("revoke")?.toString().orEmpty(),
        )
}
