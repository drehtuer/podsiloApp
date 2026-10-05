// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.drehtuer.podsilo.core.download.DownloadFolderAccess
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.work.WorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import net.drehtuer.podsilo.feature.episodes.FolderState as EpisodesFolderState
import net.drehtuer.podsilo.feature.settings.FolderState as SettingsFolderState

private const val FEED_A = "https://example.org/a.xml"
private const val FEED_B = "https://example.org/b.xml"
private const val TREE = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"

/**
 * The adapters `:app` puts between the feature modules and WorkManager / SAF / Room. They are thin,
 * but each carries one decision a screen relies on — that is what is pinned here, not the plumbing.
 */
@RunWith(RobolectricTestRunner::class)
class AdaptersTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val settings = FolderSettings()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    /**
     * Revoked is not unchosen (CLAUDE.md §11): a lost grant needs a re-grant prompt, and showing
     * "not chosen" sends the user to pick a folder they already picked. Both feature modules' copies
     * of the state have to keep that distinction.
     */
    @Test
    fun `a stored folder without a held grant reads as revoked, and no label is resolved through it`() =
        runBlocking {
            val access = DownloadFolderAccess(context, settings)
            val episodesStatus = AccessDownloadFolderStatus(access)
            val settingsStatus = SettingsFolderStatusAdapter(access, DocumentFolderLabel(context, access))

            assertEquals(EpisodesFolderState.NOT_CHOSEN, episodesStatus.observe().first())
            assertEquals(SettingsFolderState.NOT_CHOSEN, settingsStatus.observe().first().state)

            settings.folder.value = TREE

            assertEquals(EpisodesFolderState.REVOKED, episodesStatus.observe().first())
            val revoked = settingsStatus.observe().first()
            assertEquals(SettingsFolderState.REVOKED, revoked.state)
            // A revoked tree URI cannot be queried, and a stale name would be worse than none.
            assertNull(revoked.label)
        }

    @Test
    fun `a held read-write grant reads as granted`() =
        runBlocking {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(TREE),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.folder.value = TREE
            val access = DownloadFolderAccess(context, settings)

            assertEquals(EpisodesFolderState.GRANTED, AccessDownloadFolderStatus(access).observe().first())
            assertEquals(
                SettingsFolderState.GRANTED,
                SettingsFolderStatusAdapter(access, DocumentFolderLabel(context, access)).observe().first().state,
            )
        }

    /** "Is anything stuck" — the whole unsynced set across feeds, not one feed's and not every row. */
    @Test
    fun `the outbox depth counts only unsynced rows, and the error count every entry`() =
        runBlocking {
            val ledger =
                ListLedger(
                    listOf(
                        row("a", synced = false),
                        row("b", synced = true),
                        row("c", synced = false, feedUrl = FEED_B),
                    ),
                )
            val counts = SettingsCountsAdapter(ThreeEntryLog(), ledger)

            assertEquals(2, counts.observeOutboxDepth().first())
            assertEquals(3, counts.observeErrorLogCount().first())
        }

    /** S6's first preview line uses the author's own newest episode, across all feeds. */
    @Test
    fun `the naming sample is the head of the feed with the newest episode`() =
        runBlocking {
            val episodes =
                MapEpisodes(
                    byFeed =
                        mapOf(
                            FEED_A to listOf(episode("a1", FEED_A)),
                            FEED_B to listOf(episode("b2", FEED_B), episode("b1", FEED_B)),
                        ),
                    latest = mapOf(FEED_A to 100L, FEED_B to 200L),
                )
            val feeds = ListFeeds(listOf(feed(FEED_A), feed(FEED_B)))

            assertEquals("b2", NamingSampleSourceAdapter(feeds, episodes).mostRecent()?.episodeKey)
        }

    @Test
    fun `before the first refresh there is no naming sample, rather than a guess`() =
        runBlocking {
            val adapter = NamingSampleSourceAdapter(ListFeeds(emptyList()), MapEpisodes(emptyMap(), emptyMap()))

            assertNull(adapter.mostRecent())
        }

    /**
     * Issue #47: a queued download is *live* for S7 but has no progress yet, which renders as
     * *resuming* rather than 0 %. Work that is not a download carries no episode tag and is ignored.
     */
    @Test
    fun `queued download work is live without progress, and other work is not counted`() =
        runBlocking {
            val scheduler = WorkScheduler(workManager)
            scheduler.enqueueDownload("e1")
            scheduler.requestFeedRefresh()

            val work =
                withTimeout(TIMEOUT_MS) {
                    WorkManagerDownloadMonitor(scheduler).observe().first { it.live.isNotEmpty() }
                }

            assertEquals(setOf("e1"), work.live)
            assertEquals(emptyMap<String, Any>(), work.progress)
        }

    private fun row(
        key: String,
        synced: Boolean,
        feedUrl: String = FEED_A,
    ) = EpisodeLedgerRow(
        episodeKey = key,
        feedUrl = feedUrl,
        enclosureUrl = "https://example.org/$key.mp3",
        state = LedgerState.SKIPPED,
        actionedAt = 0,
        syncedToServer = synced,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        durationSeconds = null,
    )

    private fun episode(
        key: String,
        feedUrl: String,
    ) = Episode(
        episodeKey = key,
        feedUrl = feedUrl,
        guid = key,
        enclosureUrl = "https://example.org/$key.mp3",
        title = key,
        description = null,
        pubDate = null,
        durationMs = null,
    )

    private fun feed(url: String) = Feed(url, url, null, 0, null, null, null)

    private companion object {
        /** A guard against a hang, not a synchronisation delay: every wait here is on a predicate. */
        const val TIMEOUT_MS = 10_000L
    }
}

private class ListLedger(
    private val rows: List<EpisodeLedgerRow>,
) : EpisodeLedgerRepository {
    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = MutableStateFlow(rows)

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = rows.firstOrNull { it.episodeKey == episodeKey }

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> = MutableStateFlow(null)

    override suspend fun upsert(row: EpisodeLedgerRow) = error("read-only")

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = rows.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) = error("read-only")

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) = error("read-only")
}

private class ThreeEntryLog : LogRepository {
    override fun observe(category: LogCategory?): Flow<List<LogEntry>> =
        MutableStateFlow(
            (1L..3L).map {
                LogEntry(it, 0, LogCategory.SYNC, null, null, "entry $it", null, 1, 0)
            },
        )

    override suspend fun record(entry: NewLogEntry) = error("read-only")

    override suspend fun clear() = error("read-only")

    override suspend fun exportPlainText(): String = ""
}

private class MapEpisodes(
    private val byFeed: Map<String, List<Episode>>,
    private val latest: Map<String, Long>,
) : EpisodeRepository {
    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> = MutableStateFlow(byFeed[feedUrl].orEmpty())

    override suspend fun get(episodeKey: String): Episode? =
        byFeed.values.flatten().firstOrNull { it.episodeKey == episodeKey }

    override suspend fun latestPublicationByFeed(): Map<String, Long> = latest

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) = error("read-only")

    override suspend fun deleteForFeed(feedUrl: String) = error("read-only")
}

private class ListFeeds(
    private val feeds: List<Feed>,
) : FeedRepository {
    override fun observeAll(): Flow<List<Feed>> = MutableStateFlow(feeds)

    override suspend fun getAll(): List<Feed> = feeds

    override suspend fun get(url: String): Feed? = feeds.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) = error("read-only")

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) = error("read-only")
}

/** Only the folder URI carries behaviour; [DownloadFolderAccess] reads nothing else. */
private class FolderSettings : SettingsRepository {
    val folder = MutableStateFlow<String?>(null)

    override fun observeDownloadFolderUri(): Flow<String?> = folder

    override suspend fun setDownloadFolderUri(uri: String?) {
        folder.value = uri
    }

    override fun observeNaming(): Flow<NamingSettings> = MutableStateFlow(NamingSettings())

    override suspend fun setNaming(settings: NamingSettings) = error("unused")

    override fun observeSyncIntervalMinutes(): Flow<Long> = MutableStateFlow(0)

    override suspend fun setSyncIntervalMinutes(minutes: Long) = error("unused")

    override fun observeTheme(): Flow<ThemePreference> = MutableStateFlow(ThemePreference.SYSTEM)

    override suspend fun setTheme(theme: ThemePreference) = error("unused")

    override fun observeSwipeMapping(): Flow<SwipeMapping> = MutableStateFlow(SwipeMapping())

    override suspend fun setSwipeMapping(mapping: SwipeMapping) = error("unused")

    override fun observeAllowMobileData(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setAllowMobileData(allowed: Boolean) = error("unused")

    override fun observeDeliveredClearedAt(): Flow<Long> = MutableStateFlow(0L)

    override suspend fun setDeliveredClearedAt(millis: Long) = error("unused")

    override fun observeMarkOldOlderThan(): Flow<OlderThan> = MutableStateFlow(OlderThan.OFF)

    override suspend fun setMarkOldOlderThan(value: OlderThan) = error("unused")

    override fun observeNextcloudAccount(): Flow<NextcloudAccount?> = MutableStateFlow(null)

    override suspend fun nextcloudCredentials(): NextcloudCredentials? = null

    override suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?) = error("unused")
}
