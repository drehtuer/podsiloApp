// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.GpodderException
import net.drehtuer.podsilo.core.model.port.GpodderFailure
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [SyncWorker] is deliberately thin — the sync logic itself is `:core:sync`'s, tested there without
 * Android. What is worth asserting here is the translation layer: credentials in, `SyncOutcome`
 * mapped to the right `WorkManager` result.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var context: Context
    private val settings = FakeSettingsRepository()
    private val log = RecordingLogRepository()
    private val client = FakeGpodderClient()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private val ledger = FakeEpisodeLedgerRepository()
    private val syncState = FakeSyncStateRepository()

    private fun buildWorker(
        mode: String? = null,
        runAttemptCount: Int = 0,
    ): SyncWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    SyncWorker(
                        appContext = appContext,
                        workerParameters = workerParameters,
                        settingsRepository = settings,
                        syncOrchestratorFactory =
                            SyncOrchestratorFactory(
                                feedRepository = FakeFeedRepository(),
                                episodeLedgerRepository = ledger,
                                syncStateRepository = syncState,
                                gpodderClientFactory = { client },
                                logRepository = log,
                                clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
                            ),
                    )
            }
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(Data.Builder().apply { mode?.let { putString(SyncWorker.KEY_SYNC_MODE, it) } }.build())
            .build()
    }

    @Test
    fun `with no account configured the pass is a no-op, not a failure`() =
        runBlocking {
            settings.credentials = null

            assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
            assertEquals("no credentials means no request at all", 0, client.subscriptionCalls)
        }

    @Test
    fun `a completed pass succeeds`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
            assertEquals(1, client.subscriptionCalls)
        }

    @Test
    fun `a network failure asks WorkManager to retry rather than dropping the outbox`() =
        runBlocking {
            client.failWith = IOException("no route to host")

            assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
        }

    /** Giving up costs nothing: unsynced rows stay unsynced, and the next pass drains them (CLAUDE.md §5). */
    @Test
    fun `a transient failure that keeps failing stops retrying after the last attempt`() =
        runBlocking {
            client.failWith = IOException("no route to host")

            assertEquals(ListenableWorker.Result.retry(), buildWorker(runAttemptCount = 4).doWork())
            assertEquals(ListenableWorker.Result.success(), buildWorker(runAttemptCount = 5).doWork())
        }

    /** A revoked app password is fixed by signing in again (S5), not by waiting — so no retry. */
    @Test
    fun `a refused password fails the work rather than retrying it`() =
        runBlocking {
            client.typedFailure = GpodderException(GpodderFailure.UNAUTHORIZED, "HTTP 401", statusCode = 401)

            assertEquals(ListenableWorker.Result.failure(), buildWorker().doWork())
        }

    /** No mode is the ordinary pass, which reads the action log from the stored cursor, not from 0. */
    @Test
    fun `the ordinary pass pulls actions since the stored cursor`() =
        runBlocking {
            syncState.save(SyncState(lastEpisodeActionSyncTs = 1_785_000_000, deviceId = "test-device"))

            assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

            // From the cursor (rewound by :core:sync's deliberate overlap), never from 0: the full
            // history is the force-pull's job alone (CLAUDE.md §5).
            val since = client.actionFetches.single()
            assertTrue("since=$since", since in (1_785_000_000L - 24 * 60 * 60)..1_785_000_000L)
        }

    /** *Apply Nextcloud's state here* (`decisions/0025`): the whole log, whatever the cursor says. */
    @Test
    fun `the force-pull mode reads the whole action log`() =
        runBlocking {
            syncState.save(SyncState(lastEpisodeActionSyncTs = 1_785_000_000, deviceId = "test-device"))

            assertEquals(ListenableWorker.Result.success(), buildWorker(mode = SyncWorker.MODE_FORCE_PULL).doWork())

            assertEquals(listOf(0L), client.actionFetches)
            assertTrue("a pull sends nothing", client.posted.isEmpty())
        }

    /** *Send this device's state* re-posts rows already marked synced — the only way to repair them. */
    @Test
    fun `the force-push mode re-sends synced rows and pulls nothing`() =
        runBlocking {
            ledger.upsert(
                EpisodeLedgerRow(
                    episodeKey = "e1",
                    feedUrl = "https://example.org/feed.xml",
                    enclosureUrl = "https://example.org/e1.mp3",
                    state = LedgerState.SKIPPED,
                    actionedAt = 0,
                    syncedToServer = true,
                    attempts = 0,
                    lastError = null,
                    writtenFileName = null,
                    durationSeconds = 1_800,
                ),
            )

            assertEquals(ListenableWorker.Result.success(), buildWorker(mode = SyncWorker.MODE_FORCE_PUSH).doWork())

            assertTrue("the synced row was sent again", client.posted.flatten().isNotEmpty())
            assertTrue("a push does not read the log", client.actionFetches.isEmpty())
            assertEquals("nor the subscription list", 0, client.subscriptionCalls)
        }
}

private class FakeGpodderClient : GpodderClient {
    var failWith: IOException? = null
    var typedFailure: GpodderException? = null
    var subscriptionCalls: Int = 0
        private set
    val actionFetches = mutableListOf<Long>()
    val posted = mutableListOf<List<EpisodeAction>>()

    override suspend fun fetchSubscriptions(since: Long?): Result<SubscriptionDelta> {
        subscriptionCalls++
        val failure: Exception? = failWith ?: typedFailure
        return failure?.let { Result.failure(it) }
            ?: Result.success(SubscriptionDelta(add = emptyList(), remove = emptyList(), timestamp = 0))
    }

    override suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit> {
        posted += actions
        return Result.success(Unit)
    }

    override suspend fun fetchEpisodeActions(since: Long): Result<EpisodeActionPage> {
        actionFetches += since
        return Result.success(EpisodeActionPage(actions = emptyList(), timestamp = 0))
    }
}

private class FakeSettingsRepository : SettingsRepository {
    var credentials: NextcloudCredentials? =
        NextcloudCredentials("https://cloud.example.org", "podsilo", "app-password")

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = error("not needed by these tests")

    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(null)

    override suspend fun setDownloadFolderUri(uri: String?) = error("not needed by these tests")

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("not needed by these tests")

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = error("not needed by these tests")

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = error("not needed by these tests")

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = error("not needed by these tests")

    override fun observeDeliveredClearedAt(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = Unit

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = error("not needed by these tests")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(credentials?.account)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = credentials

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) =
        error("not needed by these tests")
}

private class FakeFeedRepository : FeedRepository {
    private val feeds = MutableStateFlow(emptyList<Feed>())

    override fun observeAll(): Flow<List<Feed>> = feeds

    override suspend fun getAll(): List<Feed> = feeds.value

    override suspend fun get(url: String): Feed? = feeds.value.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) {
        this.feeds.value = feeds
    }

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = error("the sync pass never refreshes feeds")
}

private class FakeEpisodeLedgerRepository : EpisodeLedgerRepository {
    private val rows = MutableStateFlow(emptyMap<String, EpisodeLedgerRow>())

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = MutableStateFlow(null)

    override suspend fun upsert(row: EpisodeLedgerRow) {
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) {
        val keys = episodeKeys.toSet()
        rows.value = rows.value.mapValues { (key, row) -> if (key in keys) row.copy(syncedToServer = true) else row }
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) = rows.forEach { row -> upsert(row) }
}

private class FakeSyncStateRepository : SyncStateRepository {
    private var state = SyncState(lastEpisodeActionSyncTs = 0, deviceId = "test-device")

    override suspend fun get(): SyncState = state

    override suspend fun save(state: SyncState) {
        this.state = state
    }
}
