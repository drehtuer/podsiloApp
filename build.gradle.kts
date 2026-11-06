// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// One entry point for coverage across a build with two kinds of module. Each module writes its own
// JaCoCo XML report and the SonarQube scan merges the twelve when it ingests them — nothing here
// parses an `.exec` file, merges execution data, or computes a percentage by hand (CLAUDE.md §3).
//
// Registered here, *before* `subprojects { }`, so that each module can attach its own report task
// below as the plugin that owns that task is applied.
val coverage =
    tasks.register("coverage") {
        group = "verification"
        description = "Runs the Tier 1 unit tests in every module and writes a JaCoCo XML report for each."
    }

// ktlint + detekt apply to every module, including the still-empty stubs, so
// `./gradlew ktlintCheck detekt` (CLAUDE.md §7/§8) has something to check as
// soon as source lands in any of them.
subprojects {
    // Pins transitive versions too; the catalog only pins what is named directly. After changing a
    // dependency: ./gradlew resolveAndLockAll --write-locks (dev-environment.adoc).
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        group = "help"
        description = "Resolves every configuration so that --write-locks can record it."
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks." }
        }
        doLast {
            // The graph, not the artifacts: AGP's variant attributes make artifact selection ambiguous
            // on some configurations no build ever resolves, and a lock only records versions.
            configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                val unresolved =
                    configuration.incoming.resolutionResult.allDependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                check(unresolved.isEmpty()) { "${configuration.name}: could not resolve $unresolved" }
            }
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))

        // detekt's default roots are `src/main` + `src/test` only, so until this line the device
        // tests under `src/androidTest/` — the ones CI cannot run either (`.github/workflows/ci.yml`)
        // — were the only Kotlin in the repository with no complexity or style checking at all. An
        // over-length line in one of them passed `./gradlew ktlintCheck detekt` and had to be found
        // by hand, which is what put this in `backlog.adoc`.
        //
        // ktlint needs no equivalent: its Android plugin registers the `androidTest` source set
        // itself (`runKtlintCheckOverAndroidTestSourceSet`), which is why only detekt is named here.
        source.from(files("src/androidTest/kotlin", "src/androidTest/java"))
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            xml.required.set(false)
            txt.required.set(false)
        }
    }

    // ktlint-gradle 14.2.0 does not treat a *removed* Kotlin file as an input change, so
    // `runKtlintCheckOver<X>SourceSet` stays UP-TO-DATE, its stale
    // `build/intermediates/ktLint/*_errors.bin` survives, and `ktlint<X>SourceSetCheck` keeps
    // failing on a file that is no longer on disk — through a daemon restart, a `--rerun`, and
    // deleting the report by hand. Reproduced from a clean state in `src/main`, `src/test` and
    // `src/androidTest` alike; 14.2.0 is the current release, so there is no upgrade to wait for.
    //
    // This adds the one thing the plugin's snapshot is missing: the *list of files that exist*. It
    // is deliberately coarse — every Kotlin path under the module's `src`, shared by every ktlint
    // task in that module — because the value only changes when a file is added or renamed or
    // deleted, and the first two already invalidate the task correctly. So the practical cost is a
    // directory walk per task, and what it buys is that the third does too.
    //
    // The alternative was `outputs.upToDateWhen { false }`, measured at 0.9s -> ~13s for every
    // `ktlintCheck`. Paying that on every invocation to fix a rare case is the wrong trade; this
    // pays nothing on the common one.
    tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
        inputs
            .property("ktlintSourceFileList") {
                projectDir
                    .resolve("src")
                    .walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "kts") }
                    .map { it.relativeTo(projectDir).invariantSeparatorsPath }
                    .sorted()
                    .joinToString("\n")
            }.optional(true)
    }

    // The two module types reach coverage by different routes, because they have to.
    //
    // The four pure-JVM modules (:core:model, :core:naming, :core:sync, :core:gpodder) use Gradle's
    // own `jacoco` plugin, whose `jacocoTestReport` task already knows where `test` put its
    // execution data and class files.
    //
    // The eight Android modules cannot use it: their unit tests run against a mocked android.jar
    // through AGP's own test task, and the classes under test are transformed on the way in, so a
    // hand-written JacocoReport pointed at `build/classes` reports coverage of the wrong bytecode.
    // `enableUnitTestCoverage` is the supported way in — AGP wires the agent into the test task and
    // registers `createDebugUnitTestCoverageReport` itself.
    //
    // Only the debug build type is instrumented. Release is R8-minified, and line coverage of
    // renamed and inlined classes says nothing useful.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")

        extensions.configure<JacocoPluginExtension> {
            toolVersion = rootProject.libs.versions.jacoco.get()
        }

        val report =
            tasks.named<JacocoReport>("jacocoTestReport") {
                // The jacoco plugin does NOT wire this up itself: without it the report task runs
                // against whatever `.exec` happens to be lying around, which on a clean checkout is
                // nothing at all — an empty report rather than a failure.
                dependsOn(tasks.named("test"))
                reports {
                    // XML is what SonarQube reads (sonar-project.properties names the glob) and is
                    // not optional. HTML is kept on so that `./gradlew :core:sync:jacocoTestReport`
                    // is also useful on its own, which is how the Android modules behave — AGP's
                    // report task writes both — and having the two halves of the build disagree
                    // about that would be a needless surprise.
                    xml.required.set(true)
                    html.required.set(true)
                }
            }

        coverage.configure { dependsOn(report) }
    }

    // WITHOUT THIS, EVERY ROBOLECTRIC TEST IN THIS REPOSITORY COUNTS FOR NOTHING.
    //
    // Robolectric runs the class under test inside its own sandbox class loader, which defines the
    // class with no code-source location. JaCoCo's agent skips such classes by default, so the
    // execution data comes back with no entry for them at all — not a mismatch, an absence. Measured
    // before this was added: :core:database 0 % of 2629 lines and :core:ui 8 %, both of which test
    // exclusively through Robolectric, while :core:datastore (no Robolectric) reported 60 %.
    //
    // `jdk.internal.*` has to be excluded once no-location classes are included, or the agent tries
    // to instrument the JDK's own reflection classes and the test JVM dies on startup.
    //
    // Hung off `withPlugin("jacoco")` rather than written as a bare `tasks.withType<Test>` here, and
    // that is not stylistic. This block runs while the *root* project is evaluated, before any module
    // is; the extension itself is added by the `jacoco` plugin's own `withType(Test)` action, and
    // actions run at task-realisation time in registration order. Registered here directly, ours runs
    // first, finds no extension yet and silently does nothing — which is exactly what happened on the
    // first attempt. Waiting for the plugin puts our action after the one that creates what it
    // configures. The plugin arrives either way: applied below for the JVM modules, and by AGP for
    // the Android ones (verified — `pluginManager.hasPlugin("jacoco")` is true in :core:database).
    pluginManager.withPlugin("jacoco") {
        tasks.withType<Test>().configureEach {
            extensions.configure<JacocoTaskExtension>("jacoco") {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }

    // Split by plugin id rather than configured through `CommonExtension<...>`: the generic arity of
    // that interface has changed between AGP majors, and these two named interfaces have not.
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            buildTypes.getByName("debug") { enableUnitTestCoverage = true }
            testCoverage.jacocoVersion = rootProject.libs.versions.jacoco.get()
        }
        // Resolved out here on purpose: inside `coverage.configure` the receiver is the root task,
        // so `$path` there would read `:coverage` rather than this module's path.
        val reportTaskPath = "$path:createDebugUnitTestCoverageReport"
        coverage.configure { dependsOn(reportTaskPath) }
    }

    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            buildTypes.getByName("debug") { enableUnitTestCoverage = true }
            testCoverage.jacocoVersion = rootProject.libs.versions.jacoco.get()
        }
        val reportTaskPath = "$path:createDebugUnitTestCoverageReport"
        coverage.configure { dependsOn(reportTaskPath) }
    }
}
