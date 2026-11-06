#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tier 3 — the device test set. Everything under `src/androidTest/` across the project, run against
# a real phone (or an emulator, if that is what is attached).
#
# WHY THIS IS SEPARATE FROM CI, AND STAYS SEPARATE
#
# `.github/workflows/ci.yml` runs yamllint, shellcheck, `ktlintCheck`, `detekt`, `lint`, `test` and
# both APK builds — and nothing else.
# `connectedDebugAndroidTest` is not in it and must not be added: GitHub's runners have no
# device, so the job could only ever be skipped, fail, or spin up an emulator whose whole purpose is
# to *not* be the thing these tests exist to check. The isolation is therefore structural rather
# than a matter of tagging: a test in `src/test/` runs on CI, a test in `src/androidTest/` runs here.
#
# WHAT THIS SET IS FOR
#
# Three things the JVM suite cannot reach, in rough order of how much they have actually cost:
#
#   1. **Android-vs-JVM behavioural deviations.** Regexes compiled by ICU rather than
#      `java.util.regex`, locale-sensitive case conversion, ICU date formatting, the device's own
#      SQLite. `decisions/0017` exists because one of these shipped a bug past 437 green tests.
#   2. **Platform surfaces with no test double worth trusting** — SAF writes, the Keystore cipher,
#      a foreground service actually starting, the cleartext-traffic policy.
#   3. **UI conformance to `UI.adoc`** on a real Compose runtime.
#
# ⚠ THIS UNINSTALLS AND REINSTALLS THE APP, SO IT WIPES ITS DATA
#
# `connectedAndroidTest` installs the app and its test package, and an install that cannot replace
# the existing one uninstalls it first. On a phone that has been used for real that costs the
# Nextcloud login, the SAF folder grant, and — the part that matters — **the episode ledger**, which
# CLAUDE.md §5 calls the one table that must never be lost.
#
# Downloaded files are unaffected: they live in the user's own folder, not in app storage.
#
# So: export a backup from Settings first if the install holds anything you want to keep, and expect
# to reconnect Nextcloud afterwards. Restoring that backup is itself gated on being connected again
# (`decisions/0018`), which is the intended order rather than an obstacle.
#
# USAGE
#
#     ./scripts/device-test.sh            # everything
#     ./scripts/device-test.sh :app       # one module
set -euo pipefail

cd "$(dirname "$0")/.."

# Reuse the connection check rather than restating it: it also refuses to start a container-local
# adb server, which is the failure that makes a working phone look unplugged (§9.3).
if ! ./scripts/adb-connect-host.sh >/dev/null 2>&1; then
    echo "No usable device. Run ./scripts/adb-connect-host.sh for the diagnosis." >&2
    exit 1
fi

# One device, named explicitly, for everything below — Gradle *and* the raw adb calls at the end.
#
# `adb` and AGP both honour ANDROID_SERIAL, so exporting it is the whole mechanism; nothing here
# needs a `-s` flag. It matters because more than one device in the list is the normal case rather
# than the exotic one: over wireless debugging (dev-environment.adoc §9.4) a stale
# `emulator-5554` or a previous `<ip>:<port>` entry sits alongside the phone, and Gradle then fails
# with "found 2 devices" rather than picking one. An ANDROID_SERIAL the caller already set wins,
# which is how you choose between them.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    ANDROID_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
export ANDROID_SERIAL
device="$ANDROID_SERIAL"

echo "==> Device test set against ${device}"
case "$device" in
    *:[0-9]*) echo "    (network device — wireless debugging, §9.4)" ;;
    *) ;; # USB or an in-container emulator; nothing extra worth saying
esac
echo "    (CI never runs these — see the header of this script)"
echo

if [[ $# -gt 0 ]]; then
    ./gradlew "$1:connectedDebugAndroidTest"
    exit
fi

# The library modules run through Gradle normally. They install only their own small test APK, and
# UTP handles that fine over this link.
#
# `:core:download` and `:core:ui` were missing from this list until 2026-08-10, so
# NotificationIconConformanceTest and MarkLegibilityConformanceTest had never run on a device
# despite existing. `:core:database` stays even though it currently has no src/androidTest/ — the
# task is a no-op there, and leaving it means the day someone adds one it is already wired in.
./gradlew :core:database:connectedDebugAndroidTest \
    :core:datastore:connectedDebugAndroidTest \
    :core:download:connectedDebugAndroidTest \
    :core:ui:connectedDebugAndroidTest \
    :feature:episodes:connectedDebugAndroidTest \
    :feature:settings:connectedDebugAndroidTest

# :app IS DELIBERATELY RUN BY HAND, AND THIS IS NOT PREMATURE CLEVERNESS.
#
# `:app:connectedDebugAndroidTest` cannot install on a usbip-attached phone: UTP's own installer
# fails on the ~58 MB app APK with `AndroidTestApkInstallerPlugin ErrorCode: 2002 — Failed to install
# APK`, producing a report that reads `tests="0" failures="0"` while Gradle announces "There were
# failing tests". Everything else about the run is fine — the same two APKs install in seconds with
# `adb install` using AGP's own flags (`-r -g`), and the tests then pass.
#
# Ruled out, each by experiment rather than by reading:
#   - a stale package         — clearing every podsilo package changed nothing
#   - AGP's install timeout   — `installation { timeOutInMs }` at 30 min made no difference
#   - the permission flags    — `adb install -r -g` of both APKs succeeds every time
#   - disabling UTP           — `useUnifiedTestPlatform=false` is deprecated and ignored in AGP 9
#
# So the install goes through adb and the run through `am instrument`, which is the same runner
# Gradle would have invoked. This is a workaround for one broken tool path on one transport, not a
# replacement for Gradle — hence it stays in this script rather than becoming a Gradle task.
echo
echo "==> :app via adb + am instrument (see the comment in this script for why)"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q

# The app APK is NOT `app-debug.apk`. `androidComponents.onVariants` in app/build.gradle.kts renames
# it to `podsilo-<versionName>-debug.apk`, and that rename applies only to the *main* variant — the
# androidTest APK keeps AGP's default name. Globbing rather than hardcoding the version keeps this
# working when versionName changes.
#
# This was a real fault: the hardcoded `app-debug.apk` kept resolving to a months-old file left in
# that directory from before the rename, so the run silently tested a stale build. Failing loudly
# when the glob matches nothing is the point.
#
# A `nullglob` array rather than `ls | head -1`: the glob expands to nothing when there is no match
# (which the check below is waiting for) and it never parses a filename out of ls's output
# (ShellCheck SC2012). Expansion is sorted, so "first match" means what it meant before.
shopt -s nullglob
app_apk_matches=(app/build/outputs/apk/debug/podsilo-*-debug.apk)
shopt -u nullglob
app_apk="${app_apk_matches[0]:-}"
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

if [[ -z "$app_apk" ]] || [[ ! -f "$test_apk" ]]; then
    echo "==> Expected APKs are missing after assembleDebug:" >&2
    echo "    app:  ${app_apk:-<no match for podsilo-*-debug.apk>}" >&2
    echo "    test: $test_apk" >&2
    exit 1
fi

echo "    installing $(basename "$app_apk")"
adb install -r -g "$app_apk" >/dev/null
adb install -r -g -t "$test_apk" >/dev/null

# `-r` (raw) rather than the pretty summary, because THE SUMMARY LIES ABOUT SKIPS.
#
# A test that opts out with `assumeTrue` is neither run nor reported: `am instrument` prints
# "OK (6 tests)" for six tests that all threw `AssumptionViolatedException` and took 0.135 s between
# them. `SafDownloadTargetInstrumentedTest` skips exactly that way when no SAF folder has been
# granted — and this script's own uninstall removes the grant, so the six tests it most needs to run
# are the six most likely to silently not.
#
# That is not hypothetical: it is how "41 instrumented tests green" got claimed on 2026-08-03 when
# 35 had run. Skips are now counted and reported, and the run is not called green while any exist.
result="$(adb shell am instrument -w -r net.drehtuer.podsilo.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"

failures="$(echo "$result" | grep -c 'INSTRUMENTATION_STATUS_CODE: -2' || true)"
skipped="$(echo "$result" | grep -c 'AssumptionViolatedException' || true)"
total="$(echo "$result" | grep -oE 'numtests=[0-9]+' | head -1 | cut -d= -f2)"

echo "    :app — ${total:-?} tests, ${failures} failed, ${skipped} skipped"
if [[ "$failures" -gt 0 ]]; then
    echo "$result" | grep -A6 'INSTRUMENTATION_STATUS: stack=' | head -40
    echo "==> :app FAILED" >&2
    exit 1
fi
if [[ "$skipped" -gt 0 ]]; then
    echo "$result" | grep -oE 'AssumptionViolatedException: .*' | sort -u | sed 's|^|    skipped: |'
    echo "==> :app INCOMPLETE — ${skipped} test(s) opted out; a skip is not a pass" >&2
    exit 1
fi

echo
echo "==> Reports:"
find . -path '*reports/androidTests/connected*' -name index.html -not -path '*/tmp/*' 2>/dev/null | sed 's|^|    |'

# TROUBLESHOOTING
#
# `Failed to install APK … ErrorCode: 2002` with "Starting 0 tests" is the UTP install failure
# described above — not a test result. It is why :app is run by hand here. If a future AGP fixes it,
# collapse this script back to a single `./gradlew connectedDebugAndroidTest`.
