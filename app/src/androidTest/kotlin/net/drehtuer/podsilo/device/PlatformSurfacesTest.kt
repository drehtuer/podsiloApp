// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.device

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.UnknownServiceException

/**
 * Platform behaviour with no test double worth trusting — the rules the *system* enforces, which a
 * fake by definition does not.
 *
 * Both cases below were found on a real Pixel 5 within minutes of each other, and neither was
 * visible to any JVM test.
 */
@RunWith(AndroidJUnit4::class)
class PlatformSurfacesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The regression for the crash that killed every download attempt on API 34.
     *
     * `DownloadWorker` calls `setForeground(… FOREGROUND_SERVICE_TYPE_DATA_SYNC)`; WorkManager serves
     * that through its own `SystemForegroundService`, which declares no `foregroundServiceType`; and
     * from API 34 the runtime type must be a subset of the manifest's. It was not, so the framework
     * threw inside its own service dispatch — a hard process kill the worker could not catch, which
     * WorkManager's retry then turned into a crash loop.
     *
     * There is a Robolectric version of this in `:app`'s unit tests reading the merged manifest, and
     * it is the one that will fail fastest. This one is worth keeping anyway: it asks the **device's
     * own `PackageManager`**, after installation, which is the authority the framework actually
     * consults.
     */
    @Test
    fun theForegroundServiceIsInstalledWithTheDataSyncType() {
        val service =
            context.packageManager.getServiceInfo(
                ComponentName(context, "androidx.work.impl.foreground.SystemForegroundService"),
                PackageManager.GET_META_DATA,
            )

        assertEquals(
            "installed without dataSync — setForeground() will kill the process on API 34+",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * The app declares the permission that goes with that type. Necessary and **not sufficient** —
     * the manifest test above is the other half, and having only this one is exactly the state the
     * crash shipped in.
     */
    @Test
    fun theDataSyncForegroundPermissionIsGranted() {
        val granted =
            context.packageManager.checkPermission(
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                context.packageName,
            )

        assertEquals(PackageManager.PERMISSION_GRANTED, granted)
    }

    /**
     * The platform backup stays off: the ledger and the subscription list leave the phone only
     * through S4's *Export database*, which the user asks for. Read from the installed package, as
     * above. `res/xml/data_extraction_rules.xml` covers the device-to-device transfer this flag does
     * not.
     */
    @Test
    fun platformBackupIsDisabled() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    /**
     * **Cleartext `http://` is blocked, and this pins that it is.**
     *
     * Not a bug — the platform default at `targetSdk` 28+, and the right default. It is asserted
     * because it is invisible from the JVM (OkHttp there will happily use `http://`) and because it
     * has a real consequence recorded in `backlog.adoc`: the author's `heute journal` feed
     * advertises its cover art over `http://`, so that row falls back to a monogram. If an
     * *enclosure* ever arrives over `http://` it will fail to download for this reason and the error
     * will not say so.
     *
     * If this test ever fails, someone has added a network-security config permitting cleartext —
     * which is a decision, and should be an ADR rather than a side effect.
     */
    @Test
    fun plainHttpIsRefusedByThePlatform() {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://example.org/cover.jpg").build()

        val failure =
            runCatching { client.newCall(request).execute().close() }
                .exceptionOrNull()

        assertTrue(
            "expected cleartext to be refused, got: $failure",
            failure is UnknownServiceException ||
                (failure is IOException && failure.message?.contains("CLEARTEXT") == true),
        )
    }
}
