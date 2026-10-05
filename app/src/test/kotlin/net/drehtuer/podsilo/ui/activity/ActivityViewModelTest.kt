// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.Connectivity
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.feature.episodes.DownloadProgress
import net.drehtuer.podsilo.feature.episodes.DownloadWork
import net.drehtuer.podsilo.feature.episodes.EpisodeScheduler
import net.drehtuer.podsilo.feature.episodes.FolderState
import net.drehtuer.podsilo.feature.episodes.TriageWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val FEED_URL = "https://example.org/feed.xml"

/**
 * S7's view model — **the screen issue #47 was actually about**, and which had no unit test at all.
 *
 * The bug was not a missing observation: S7 already observed Room `Flow`s. It observed *every ledger
 * row on the device* and then looked each row's episode up one at a time in Kotlin, discarding all
 * but the handful in flight — thousands of sequential queries per emission, re-run on every ledger
 * write anywhere in the app. These tests pin the shape that replaced it: the queries are narrow, and
 * live progress finally reaches the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private val list = FakeActivityListRepository()
    private val episodes = FakeActivityEpisodeRepository()
    private val feeds = FakeActivityFeedRepository()
    private val settings = FakeActivitySettingsRepository()
    private val work = MutableStateFlow(DownloadWork())
    private val folder = MutableStateFlow(FolderState.GRANTED)
    private val scheduler = RecordingActivityScheduler()
    private val clock = Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC)
    private val connectivity = MutableStateFlow(Connectivity(online = true, metered = false))
    private var syncRequests = 0

    /** Held rather than built inline, so a decision withdrawn on this screen is observable (#90). */
    private val triageLedger = FakeActivityLedger()

    private fun TestScope.viewModel(): ActivityViewModel {
        feeds.seed(Feed(FEED_URL, "Der Podcast", null, 0, null, null, null))
        val vm =
            ActivityViewModel(
                episodeRepository = episodes,
                listRepository = list,
                feedRepository = feeds,
                settingsRepository = settings,
                connectivityMonitor =
                    object : ConnectivityMonitor {
                        override fun observe() = connectivity
                    },
                folderStatus = { folder },
                workMonitor = { work },
                syncStatus = { MutableStateFlow(null) },
                scheduler = scheduler,
                triageWriter = TriageWriter(triageLedger, clock, {}),
                syncNow = { syncRequests++ },
                clock = clock,
                // Issue #91: the projection is dispatched off the main thread in production; a test
                // has to name a dispatcher it controls, or its emissions race the test scheduler.
                projectionContext = UnconfinedTestDispatcher(),
            )
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    private fun episode(key: String) =
        Episode(
            episodeKey = key,
            feedUrl = FEED_URL,
            guid = key,
            enclosureUrl = "https://example.org/$key.mp3",
            title = "Die Elbe von unten",
            description = null,
            pubDate = null,
            durationMs = null,
        )

    private fun row(
        key: String,
        state: LedgerState,
        writtenFileName: String? = null,
        actionedAt: Long = 0,
    ) = EpisodeLedgerRow(
        episodeKey = key,
        feedUrl = FEED_URL,
        enclosureUrl = "https://example.org/$key.mp3",
        state = state,
        actionedAt = actionedAt,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = writtenFileName,
        durationSeconds = null,
    )

    @Test
    fun `the three groups come from the narrowed in-flight query`() =
        runTest {
            list.inFlight.value =
                listOf(
                    EpisodeListItem(episode("q"), row("q", LedgerState.QUEUED)),
                    EpisodeListItem(episode("d"), row("d", LedgerState.DOWNLOADING)),
                    EpisodeListItem(episode("f"), row("f", LedgerState.ERROR)),
                )
            work.value = DownloadWork(live = setOf("d"))
            val vm = viewModel()
            runCurrent()

            val state = vm.state.value
            assertEquals(listOf("q"), state.queued.map { it.episode.episodeKey })
            assertEquals(listOf("d"), state.downloading.map { it.episodeKey })
            assertEquals(listOf("f"), state.failed.map { it.episodeKey })
        }

    /**
     * The headline symptom: a download appearing in S7 without a delay. A newly queued row reaches
     * the screen on the query's next emission, with no per-row episode lookup in between.
     */
    @Test
    fun `a newly queued download appears without any further work`() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertTrue(
                vm.state.value.queued
                    .isEmpty(),
            )

            list.inFlight.value = listOf(EpisodeListItem(episode("q"), row("q", LedgerState.QUEUED)))
            runCurrent()

            assertEquals(
                listOf("q"),
                vm.state.value.queued
                    .map { it.episode.episodeKey },
            )
        }

    @Test
    fun `live byte progress reaches the downloading row`() =
        runTest {
            list.inFlight.value = listOf(EpisodeListItem(episode("d"), row("d", LedgerState.DOWNLOADING)))
            work.value =
                DownloadWork(
                    progress = mapOf("d" to DownloadProgress(bytesDownloaded = 24, totalBytes = 39)),
                    live = setOf("d"),
                )
            val vm = viewModel()
            runCurrent()

            assertEquals(
                61,
                vm.state.value.downloading
                    .single()
                    .progress
                    ?.percent,
            )
        }

    /** §7: after process death there is no live work, so the row must not draw a percentage. */
    @Test
    fun `a downloading row with no live work shows no percentage`() =
        runTest {
            list.inFlight.value = listOf(EpisodeListItem(episode("d"), row("d", LedgerState.DOWNLOADING)))
            val vm = viewModel()
            runCurrent()

            assertTrue(
                vm.state.value.downloading
                    .isEmpty(),
            )
            assertEquals(
                listOf("d"),
                vm.state.value.queued
                    .map { it.episode.episodeKey },
            )
            assertNull(
                vm.state.value.queued
                    .single()
                    .episode.progress,
            )
        }

    @Test
    fun `recently delivered and the outbox depth both come from their own queries`() =
        runTest {
            list.delivered.value = listOf(row("a", LedgerState.DOWNLOADED, writtenFileName = "a.mp3", actionedAt = 5))
            list.unsyncedCount.value = 3
            val vm = viewModel()
            runCurrent()

            assertEquals(
                listOf("a.mp3"),
                vm.state.value.recent
                    .map { it.fileName },
            )
            assertEquals(
                "Der Podcast",
                vm.state.value.recent
                    .single()
                    .folderLabel,
            )
            assertEquals(3, vm.state.value.sync.outboxDepth)
        }

    /**
     * Issue #90: the undo window closes after five seconds and the decision is then invisible, so a
     * mis-swipe cannot be found again. The group is the finding, and the button is the taking back.
     */
    @Test
    fun `recent actions render newest first, in the user's words, and are bounded in SQL`() =
        runTest {
            episodes.seed(episode("played"))
            episodes.seed(episode("downloaded"))
            list.history.value =
                listOf(
                    EpisodeListItem(episode("played"), row("played", LedgerState.SKIPPED, actionedAt = 400)),
                    EpisodeListItem(
                        episode("downloaded"),
                        row("downloaded", LedgerState.DOWNLOADED, writtenFileName = "d.mp3", actionedAt = 300),
                    ),
                )
            val vm = viewModel()
            runCurrent()

            val history = vm.state.value.history
            assertEquals(listOf("played", "downloaded"), history.map { it.episodeKey })
            assertEquals(listOf(LedgerState.SKIPPED, LedgerState.DOWNLOADED), history.map { it.state })
            assertEquals("Der Podcast", history.first().feedTitle)
            // The limit is the query's job, not a take() after the fact (issue #47's lesson).
            assertEquals(50, list.historyLimit)
        }

    /** A decision already withdrawn offers no button — an affordance that does nothing is worse. */
    @Test
    fun `an already unplayed row offers no mark-as-unplayed action`() =
        runTest {
            episodes.seed(episode("e1"))
            list.history.value =
                listOf(EpisodeListItem(episode("e1"), row("e1", LedgerState.UNPLAYED, actionedAt = 1)))
            val vm = viewModel()
            runCurrent()

            assertFalse(
                vm.state.value.history
                    .single()
                    .canMarkAsUnplayed,
            )
        }

    /**
     * The recovery itself. A new `UNPLAYED` row, never a deleted one (`decisions/0024`): the
     * ledger row is the dedup authority and has to outlive the decision it records.
     */
    @Test
    fun `marking as unplayed from the history writes a new row and keeps the old one's file name`() =
        runTest {
            episodes.seed(episode("e1"))
            triageLedger.upsert(row("e1", LedgerState.DOWNLOADED, writtenFileName = "e1.mp3", actionedAt = 1))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.MarkAsUnplayedClicked("e1"))
            runCurrent()

            val written = triageLedger.get("e1")
            assertEquals(LedgerState.UNPLAYED, written?.state)
            assertNotNull("the row must survive the withdrawal", written)
            // Kept, or a later re-download would write a second copy of a file already in the folder.
            assertEquals("e1.mp3", written?.writtenFileName)
            assertFalse("the withdrawal has to reach the server", written?.syncedToServer ?: true)
        }

    /**
     * *Clear list* is a display cursor, not a deletion — those rows are what stop an episode being
     * downloaded twice (CLAUDE.md §11). Here that means it re-runs the query with a new `since`.
     */
    @Test
    fun `clearing the delivered list moves the cursor and deletes nothing`() =
        runTest {
            list.delivered.value = listOf(row("a", LedgerState.DOWNLOADED, writtenFileName = "a.mp3"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.ClearDeliveredClicked)
            runCurrent()

            assertEquals(clock.millis(), settings.deliveredClearedAt.value)
            assertEquals(clock.millis(), list.deliveredSince)
        }

    @Test
    fun `Sync now asks for a pass when online and says so`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(ActivityEvent.SyncNowClicked)

                assertEquals(ActivityEffect.ShowMessage("Syncing…"), awaitItem())
            }
            assertEquals(1, syncRequests)
        }

    /** UI.adoc §12.10: an offline tap says so instead of enqueueing a pass that waits for a network. */
    @Test
    fun `Sync now offline says there is no network and asks for nothing`() =
        runTest {
            connectivity.value = Connectivity(online = false, metered = false)
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(ActivityEvent.SyncNowClicked)

                assertEquals(ActivityEffect.ShowMessage("No network connection"), awaitItem())
            }
            assertEquals(0, syncRequests)
            assertEquals(BlockedReason.OFFLINE, vm.state.value.sync.blockedReason)
            assertFalse(vm.state.value.sync.canSyncNow)
        }

    @Test
    fun `without an account the sync row is blocked as not configured, even online`() =
        runTest {
            settings.account.value = null
            val vm = viewModel()
            runCurrent()

            assertEquals(BlockedReason.NOT_CONFIGURED, vm.state.value.sync.blockedReason)
            assertFalse(vm.state.value.sync.canSyncNow)
        }

    @Test
    fun `a queued row offline waits for a network, not for Wi-Fi`() =
        runTest {
            connectivity.value = Connectivity(online = false, metered = false)
            list.inFlight.value = listOf(EpisodeListItem(episode("q"), row("q", LedgerState.QUEUED)))
            val vm = viewModel()
            runCurrent()

            assertEquals(
                WaitReason.NETWORK,
                vm.state.value.queued
                    .single()
                    .reason,
            )
        }

    /**
     * A retry is a re-decision (`decisions/0012` §3): a fresh `QUEUED` row with the attempt count
     * and error cleared, then the download is enqueued — not merely the work restarted.
     */
    @Test
    fun `retry re-queues through the triage writer and then enqueues the download`() =
        runTest {
            episodes.seed(episode("f"))
            triageLedger.upsert(row("f", LedgerState.ERROR).copy(attempts = 3, lastError = "connection reset"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.RetryClicked("f"))
            runCurrent()

            val written = triageLedger.get("f")
            assertEquals(LedgerState.QUEUED, written?.state)
            assertEquals(0, written?.attempts)
            assertNull(written?.lastError)
            assertEquals(listOf("f" to false), scheduler.enqueued)
        }

    @Test
    fun `mark as played from a failed row writes SKIPPED and enqueues nothing`() =
        runTest {
            episodes.seed(episode("f"))
            triageLedger.upsert(row("f", LedgerState.ERROR))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.MarkAsPlayedClicked("f"))
            runCurrent()

            assertEquals(LedgerState.SKIPPED, triageLedger.get("f")?.state)
            assertTrue(scheduler.enqueued.isEmpty())
        }

    /** An episode pruned from the cache since the row was drawn: nothing to write, nothing to crash. */
    @Test
    fun `an action on an episode that no longer exists does nothing`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.RetryClicked("gone"))
            vm.onEvent(ActivityEvent.MarkAsPlayedClicked("gone"))
            vm.onEvent(ActivityEvent.MarkAsUnplayedClicked("gone"))
            runCurrent()

            assertNull(triageLedger.get("gone"))
            assertTrue(scheduler.enqueued.isEmpty())
        }

    @Test
    fun `cancel goes to the scheduler and nowhere else`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.onEvent(ActivityEvent.CancelClicked("q"))

            assertEquals(listOf("q"), scheduler.cancellations)
        }

    @Test
    fun `navigation events become the matching effects`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(ActivityEvent.RowClicked(FEED_URL, "e1"))
                assertEquals(ActivityEffect.OpenEpisodeDetail("e1"), awaitItem())

                vm.onEvent(ActivityEvent.DetailsClicked("e1"))
                assertEquals(ActivityEffect.OpenErrorLog, awaitItem())

                vm.onEvent(ActivityEvent.ErrorLogClicked)
                assertEquals(ActivityEffect.OpenErrorLog, awaitItem())

                vm.onEvent(ActivityEvent.PausedBannerActionClicked)
                assertEquals(ActivityEffect.ChooseFolder, awaitItem())
            }
        }
}

/** Records what the screen asked to be scheduled — never runs anything. */
private class RecordingActivityScheduler : EpisodeScheduler {
    val cancellations = mutableListOf<String>()

    /** Episode key and the `userRequested` flag, per enqueue. */
    val enqueued = mutableListOf<Pair<String, Boolean>>()

    override fun enqueueDownload(
        episodeKey: String,
        userRequested: Boolean,
    ) {
        enqueued += episodeKey to userRequested
    }

    override fun cancelDownload(episodeKey: String) {
        cancellations += episodeKey
    }

    override suspend fun requestFeedRefresh(feedUrl: String?) = Unit

    override suspend fun syncAndAwait() = Unit
}

/**
 * Each query is its own settable flow, which is the point: a test can move one without the others,
 * so "the delivered list changed" cannot accidentally be satisfied by the in-flight query.
 */
private class FakeActivityListRepository : EpisodeListRepository {
    val inFlight = MutableStateFlow<List<EpisodeListItem>>(emptyList())
    val delivered = MutableStateFlow<List<EpisodeLedgerRow>>(emptyList())
    val unsyncedCount = MutableStateFlow(0)

    /** S7's *recent actions* group (issue #90), settable on its own like the rest. */
    val history = MutableStateFlow<List<EpisodeListItem>>(emptyList())

    /** The limit the view model asked for, so "bounded in SQL" is observable rather than assumed. */
    var historyLimit: Int = -1
        private set

    /** The `since` the view model asked for, so the clear cursor is observable. */
    var deliveredSince: Long = -1
        private set

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> = MutableStateFlow(emptyList())

    override fun observeInFlight(): Flow<List<EpisodeListItem>> = inFlight

    override fun observeRecentActions(limit: Int): Flow<List<EpisodeListItem>> {
        historyLimit = limit
        return history
    }

    override fun observeRecentlyDelivered(
        since: Long,
        limit: Int,
    ): Flow<List<EpisodeLedgerRow>> {
        deliveredSince = since
        return delivered.map { rows -> rows.filter { it.actionedAt > since }.take(limit) }
    }

    override fun observeUnsyncedCount(): Flow<Int> = unsyncedCount

    override fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>> = MutableStateFlow(emptyList())

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> = emptyList()

    override suspend fun undecided(scope: BulkScope): List<Episode> = emptyList()
}

private class FakeActivityEpisodeRepository : EpisodeRepository {
    private val episodes = mutableMapOf<String, Episode>()

    /** The history group joins to the episode, so a test that renders one has to have one (#90). */
    fun seed(episode: Episode) {
        episodes[episode.episodeKey] = episode
    }

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) = Unit

    override suspend fun get(episodeKey: String): Episode? = episodes[episodeKey]

    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> = MutableStateFlow(emptyList())

    override suspend fun deleteForFeed(feedUrl: String) = Unit

    override suspend fun latestPublicationByFeed(): Map<String, Long> = emptyMap()
}

private class FakeActivityFeedRepository : FeedRepository {
    private val feeds = MutableStateFlow(emptyList<Feed>())

    fun seed(vararg items: Feed) {
        feeds.value = items.toList()
    }

    override fun observeAll(): Flow<List<Feed>> = feeds

    override suspend fun getAll(): List<Feed> = feeds.value

    override suspend fun get(url: String): Feed? = feeds.value.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) = Unit

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = Unit
}

/** Only the two settings S7 reads carry behaviour; the rest would fail loudly if it started using them. */
private class FakeActivitySettingsRepository : SettingsRepository {
    val deliveredClearedAt = MutableStateFlow(0L)
    val account = MutableStateFlow<NextcloudAccount?>(NextcloudAccount("https://cloud.example.org", "podsilo"))

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = error("not used by S7")

    override fun observeDownloadFolderUri(): Flow<String?> = MutableStateFlow(null)

    override suspend fun setDownloadFolderUri(uri: String?) = error("not used by S7")

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("not used by S7")

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = error("not used by S7")

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = error("not used by S7")

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = error("not used by S7")

    override fun observeDeliveredClearedAt(): Flow<Long> = deliveredClearedAt

    override suspend fun setDeliveredClearedAt(millis: Long) {
        deliveredClearedAt.value = millis
    }

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = error("not used by S7")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = account

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = error("not used by S7")
}

/** S7's retry/mark-as-played path writes through this; these tests assert the projection, not the writes. */
private class FakeActivityLedger : net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository {
    private val rows = MutableStateFlow<Map<String, EpisodeLedgerRow>>(emptyMap())

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = rows.map { it.values.toList() }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.value[episodeKey]

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = rows.map { it[episodeKey] }

    override suspend fun upsert(row: EpisodeLedgerRow) {
        rows.value = rows.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) = Unit

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) {
        this.rows.value = this.rows.value + rows.associateBy { it.episodeKey }
    }
}
