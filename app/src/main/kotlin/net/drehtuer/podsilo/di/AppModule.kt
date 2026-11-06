// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.drehtuer.podsilo.BuildConfig
import net.drehtuer.podsilo.core.gpodder.RetrofitGpodderClientFactory
import net.drehtuer.podsilo.core.gpodder.RetrofitNextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.GpodderClientFactory
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.SyncTrigger
import net.drehtuer.podsilo.feature.settings.DirectionalSync
import net.drehtuer.podsilo.system.AndroidConnectivityMonitor
import net.drehtuer.podsilo.work.WorkScheduler
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Process-wide singletons every other module builds on: HTTP, the clock, WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * One shared client for feed fetches, enclosure downloads and the GPodder API: one connection
     * pool, one dispatcher (CLAUDE.md §3 — use OkHttp directly, don't wrap or duplicate it).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            // Set deliberately rather than left at OkHttp's 10 s defaults, which are tuned for an
            // API you control. Neither of the servers here is that: a self-hosted Nextcloud on a
            // home connection and a podcast host's CDN both routinely take longer to answer, and
            // Nextcloud's bruteforce protection *deliberately* delays repeated login attempts. At
            // 10 s those arrive as timeouts and used to be reported as "can't reach that address".
            //
            // No `callTimeout`: it bounds the whole call including the body, which would cap how
            // long an enclosure download may take, and this client is shared with the downloader.
            .connectTimeout(20.seconds.toJavaDuration())
            .readTimeout(30.seconds.toJavaDuration())
            .writeTimeout(30.seconds.toJavaDuration())
            .build()

    /** Injected rather than called statically so time-dependent logic stays testable (CLAUDE.md §7). */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideGpodderClientFactory(okHttpClient: OkHttpClient): GpodderClientFactory =
        RetrofitGpodderClientFactory(okHttpClient)

    /** Login Flow v2 lives on Nextcloud core, before any credentials exist — see the port's KDoc. */
    @Provides
    @Singleton
    fun provideLoginFlowClient(okHttpClient: OkHttpClient): NextcloudLoginFlowClient =
        RetrofitNextcloudLoginFlowClient(okHttpClient)

    /** Shown in S4's About row. Named because a bare String binding would be ambiguous. */
    @Provides
    @Named("appVersion")
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Build number, build time and commit, for S4's About group. One preformatted string because
     * it has exactly one consumer and splitting it would put formatting in three places.
     */
    @Provides
    @Named("appBuild")
    fun provideAppBuild(): String = "${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TIME} · ${BuildConfig.GIT_SHA}"
}

/** `@Binds` needs an abstract function, so it can't live in the `object` module above. */
@Module
@InstallIn(SingletonComponent::class)
interface BindingsModule {
    /**
     * The two directional passes S4 offers (`decisions/0025`). A separate port from
     * [SyncTrigger] on purpose: "sync now" and "overwrite the server with my state" are different
     * requests, and one interface with a mode parameter is one typo away from confusing them.
     */
    @Binds
    fun bindDirectionalSync(workScheduler: WorkScheduler): DirectionalSync

    /**
     * Every "I have written something the server needs" in the app arrives here — a finished
     * download, a mark-as-played, S4's bulk mark, the mark-old rule, and S5 once credentials land.
     * `:app` is where the worker it schedules lives, and one port means one place to look.
     */
    @Binds
    fun bindSyncTrigger(workScheduler: WorkScheduler): SyncTrigger

    /**
     * The screens ask "is there a network" before starting a refresh, so an offline pull can answer
     * instantly rather than timing out against every feed (`UI.adoc` §12.10).
     */
    @Binds
    fun bindConnectivityMonitor(monitor: AndroidConnectivityMonitor): ConnectivityMonitor
}
