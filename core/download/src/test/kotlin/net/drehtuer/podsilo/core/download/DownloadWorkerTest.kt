// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.LogCategory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private const val EPISODE_KEY = "guid-1"
private const val FEED_URL = "https://example.org/feed.xml"
private const val NOW_MILLIS = 1_784_019_600_000

/**
 * [DownloadWorker]'s ledger contract: the states it writes, in what order, and what it refuses to
 * do. Robolectric supplies the Android `Context` WorkManager needs; there is no emulator involved
 * (CLAUDE.md §4). The SAF write is [FakeDownloadTarget] (`architecture.adoc` §11).
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var target: FakeDownloadTarget
    private val ledger = FakeEpisodeLedgerRepository()
    private val syncTrigger = RecordingSyncTrigger()
    private val log = RecordingLogRepository()
    private val progressUpdater = RecordingProgressUpdater()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneId.of("Europe/Berlin"))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
        target = FakeDownloadTarget(temporaryFolder.newFolder("tree"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feed() =
        Feed(
            url = FEED_URL,
            title = "Der Podcast",
            imageUrl = null,
            firstSeenAt = 0,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    private fun episode() =
        Episode(
            episodeKey = EPISODE_KEY,
            feedUrl = FEED_URL,
            guid = EPISODE_KEY,
            enclosureUrl = server.url("/ep1.mp3").toString(),
            title = "Warum Hamburg immer regnet",
            description = "Show notes",
            pubDate = NOW_MILLIS,
            durationMs = 1_800_000,
        )

    private fun queuedRow(
        state: LedgerState = LedgerState.QUEUED,
        writtenFileName: String? = null,
    ) = EpisodeLedgerRow(
        episodeKey = EPISODE_KEY,
        feedUrl = FEED_URL,
        enclosureUrl = server.url("/ep1.mp3").toString(),
        state = state,
        actionedAt = 0,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = writtenFileName,
    )

    private fun mp3Body(): Buffer {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/audio/silence.mp3")).readBytes()
        return Buffer().write(bytes)
    }

    private fun buildWorker(
        episodes: List<Episode> = listOf(episode()),
        feeds: List<Feed> = listOf(feed()),
        userRequested: Boolean = false,
    ): DownloadWorker {
        // Stable across calls, like the real app's cache dir — a test that builds the worker twice
        // (to compare two runs of the same episode) is exercising exactly the case that matters.
        val cacheDir = File(temporaryFolder.root, "cache").apply { mkdirs() }
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    DownloadWorker(
                        appContext = appContext,
                        workerParameters = workerParameters,
                        episodeRepository = FakeEpisodeRepository(episodes.toMutableList()),
                        feedRepository = FakeFeedRepository(feeds.toMutableList()),
                        ledgerRepository = ledger,
                        settingsRepository = FakeSettingsRepository(),
                        episodeDownloader =
                            EpisodeDownloader(
                                enclosureDownloader = EnclosureDownloader(),
                                audioTagWriter = AudioTagWriter(),
                                downloadTarget = target,
                                cacheDir = cacheDir,
                                zoneId = ZoneId.of("Europe/Berlin"),
                            ),
                        notifications = DownloadNotifications(appContext),
                        syncTrigger = syncTrigger,
                        logRepository = log,
                        clock = clock,
                    )
            }
        return TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(
                Data
                    .Builder()
                    .putString(DownloadWorker.KEY_EPISODE_KEY, EPISODE_KEY)
                    .putBoolean(DownloadWorker.KEY_USER_REQUESTED, userRequested)
                    .build(),
            ).setWorkerFactory(factory)
            .setProgressUpdater(progressUpdater)
            .build()
    }

    @Test
    fun `a successful download writes DOWNLOADED unsynced, then asks for a sync pass`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg").setBody(mp3Body()),
            )
            ledger.upsert(queuedRow())
            ledger.writes.clear()

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(
                listOf(LedgerState.DOWNLOADING, LedgerState.DOWNLOADED),
                ledger.writes.map { it.state },
            )
            val stored = checkNotNull(ledger.get(EPISODE_KEY))
            assertEquals("20260714_Warum Hamburg immer regnet.mp3", stored.writtenFileName)
            // The row must be durable *before* anything is posted: only a confirmed 2xx flips this.
            assertFalse(stored.syncedToServer)
            assertEquals(NOW_MILLIS, stored.actionedAt)
            assertEquals(1800, stored.durationSeconds)
            assertEquals(1, syncTrigger.requests)
        }

    @Test
    fun `an episode already handled remotely is never downloaded`() =
        runBlocking {
            // The exact race CLAUDE.md §7 item 8 calls out: a remote action arrives for an episode
            // that is queued locally. No request must reach the server.
            ledger.upsert(queuedRow(state = LedgerState.HANDLED_REMOTELY))
            ledger.writes.clear()

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(emptyList<LedgerState>(), ledger.writes.map { it.state })
            assertEquals(0, server.requestCount)
            assertEquals(emptyList<String>(), target.deliveries)
        }

    @Test
    fun `the user-requested flag is the only way past the terminal-row refusal`() =
        runBlocking {
            // The half of decisions/0012 that keeps the no-auto-download invariant provable.
            // Same episode, same terminal row, same everything — only the flag differs.
            ledger.upsert(queuedRow(state = LedgerState.DOWNLOADED, writtenFileName = "already there.mp3"))
            ledger.writes.clear()

            buildWorker(userRequested = false).doWork()
            assertEquals("without the flag, nothing is fetched", 0, server.requestCount)
            assertEquals("and the terminal row is left alone", emptyList<EpisodeLedgerRow>(), ledger.writes)
            assertEquals("already there.mp3", ledger.get(EPISODE_KEY)?.writtenFileName)

            server.enqueue(MockResponse().setResponseCode(200).setBody(mp3Body()))
            buildWorker(userRequested = true).doWork()
            assertEquals("with the flag, the download proceeds", 1, server.requestCount)
        }

    @Test
    fun `a re-download resets attempts and clears the error, but keeps the written file name`() =
        runBlocking {
            // decisions/0012 sections 2 and 3. Keeping writtenFileName is the non-obvious half:
            // it is what the duplicate guard checks, so losing it here would let a second copy be
            // written on the *next* re-download.
            server.enqueue(MockResponse().setResponseCode(200).setBody(mp3Body()))
            ledger.upsert(
                queuedRow(state = LedgerState.DOWNLOADED, writtenFileName = "20260714_earlier name.mp3")
                    .copy(attempts = 4, lastError = "No space left on device", syncedToServer = true),
            )
            ledger.writes.clear()

            buildWorker(userRequested = true).doWork()

            val stored = checkNotNull(ledger.get(EPISODE_KEY))
            assertEquals(LedgerState.DOWNLOADED, stored.state)
            assertEquals("a re-decision is a new attempt chain, not attempt 5 of 5", 1, stored.attempts)
            assertNull(stored.lastError)
            assertEquals("20260714_earlier name.mp3", stored.writtenFileName)
            assertFalse("a re-download re-posts DOWNLOAD, so it re-enters the outbox", stored.syncedToServer)
        }

    @Test
    fun `a re-download whose own file is still there aborts instead of writing a second copy`() =
        runBlocking {
            // The one licensed existence check in the app (decisions/0012 §4). Not an ERROR:
            // the row stays DOWNLOADED and the user is told the file is already there.
            target.seed("Der Podcast", "20260714_earlier name.mp3")
            ledger.upsert(
                queuedRow(state = LedgerState.DOWNLOADED, writtenFileName = "20260714_earlier name.mp3"),
            )
            ledger.writes.clear()

            val result = buildWorker(userRequested = true).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("nothing is fetched when the file is already present", 0, server.requestCount)
            assertEquals(emptyList<String>(), target.deliveries)
            assertEquals(LedgerState.DOWNLOADED, ledger.get(EPISODE_KEY)?.state)
        }

    @Test
    fun `a first-time download performs no existence check at all`() =
        runBlocking {
            // A first download has no writtenFileName, so file presence is never consulted — the
            // ledger stays the only authority on whether an episode was handled (CLAUDE.md §11).
            server.enqueue(MockResponse().setResponseCode(200).setBody(mp3Body()))
            target.seed("Der Podcast", "20260714_Warum-Hamburg-immer-regnet.mp3")
            ledger.upsert(queuedRow())

            buildWorker(userRequested = true).doWork()

            assertEquals(1, server.requestCount)
            assertEquals(LedgerState.DOWNLOADED, ledger.get(EPISODE_KEY)?.state)
        }

    @Test
    fun `a retryable failure records the error and asks WorkManager to retry`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            ledger.upsert(queuedRow())
            ledger.writes.clear()

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            val stored = checkNotNull(ledger.get(EPISODE_KEY))
            assertEquals(LedgerState.ERROR, stored.state)
            assertEquals(1, stored.attempts)
            assertTrue(checkNotNull(stored.lastError).contains("503"))
            assertEquals("a failed download must not trigger a sync pass", 0, syncTrigger.requests)
        }

    @Test
    fun `a 404 fails for good rather than retrying forever`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            ledger.upsert(queuedRow())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals(LedgerState.ERROR, ledger.get(EPISODE_KEY)?.state)
        }

    /**
     * The row's `lastError` is the *current* state; the log is the history. Before this, a download
     * that failed every night left nothing S8 could show, which is half of issue #60's diagnosis
     * problem.
     */
    @Test
    fun `a failed download is written to the error log, naming the episode it belongs to`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            ledger.upsert(queuedRow())

            buildWorker().doWork()

            val entry = log.recorded.single()
            assertEquals(LogCategory.DOWNLOAD, entry.category)
            assertEquals(EPISODE_KEY, entry.episodeKey)
            assertEquals(FEED_URL, entry.feedUrl)
            assertTrue("the sentence is plain language", entry.message.startsWith("This episode could not"))
            assertTrue("the attempt count belongs in the detail", checkNotNull(entry.detail).contains("attempt 1"))
        }

    /**
     * Every attempt records, not only the last: the DAO collapses repeats into one entry with a
     * count, and that count is the only way a user can tell "it failed once" from "it has been
     * failing all night" (`UI.adoc` §11).
     */
    @Test
    fun `each attempt records, so the store can collapse them into a count`() =
        runBlocking {
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(503))
                buildWorker().doWork()
            }

            assertEquals(2, log.recorded.size)
            assertEquals(
                "the identity has to match for the store to collapse them",
                1,
                log.recorded
                    .map { it.category to it.message }
                    .toSet()
                    .size,
            )
        }

    /**
     * A lost folder is a storage problem the user fixes elsewhere, so it is filed under `STORAGE`
     * and its sentence names the fix rather than offering a retry that cannot work
     * (`architecture.adoc` §11).
     */
    @Test
    fun `a lost download folder is a storage entry that names the fix`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(mp3Body()))
            target.unavailable = DownloadFolderUnavailableException("tree uri revoked")
            ledger.upsert(queuedRow())

            buildWorker().doWork()

            val entry = log.recorded.single()
            assertEquals(LogCategory.STORAGE, entry.category)
            assertTrue(entry.message.contains("Choose it again"))
            assertFalse("retrying cannot work until the folder is back", entry.message.contains("Retry"))
        }

    @Test
    fun `a retry reuses the file name the ledger recorded`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(mp3Body()))
            ledger.upsert(queuedRow(state = LedgerState.ERROR, writtenFileName = "20260714_earlier name.mp3"))

            buildWorker().doWork()

            assertEquals("20260714_earlier name.mp3", ledger.get(EPISODE_KEY)?.writtenFileName)
            assertEquals(listOf("Der Podcast/20260714_earlier name.mp3"), target.deliveries)
        }

    @Test
    fun `an episode whose feed was unsubscribed mid-flight errors instead of crashing`() =
        runBlocking {
            ledger.upsert(queuedRow())

            val result = buildWorker(episodes = emptyList()).doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            val stored = checkNotNull(ledger.get(EPISODE_KEY))
            assertEquals(LedgerState.ERROR, stored.state)
            assertEquals("episode is no longer in any subscribed feed", stored.lastError)
        }

    @Test
    fun `no episode key in the input data is a permanent failure, not a crash`() =
        runBlocking {
            val worker =
                TestListenableWorkerBuilder<DownloadWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker =
                                DownloadWorker(
                                    appContext,
                                    workerParameters,
                                    FakeEpisodeRepository(),
                                    FakeFeedRepository(),
                                    ledger,
                                    FakeSettingsRepository(),
                                    EpisodeDownloader(
                                        EnclosureDownloader(),
                                        AudioTagWriter(),
                                        target,
                                        File(temporaryFolder.root, "cache-empty"),
                                    ),
                                    DownloadNotifications(appContext),
                                    syncTrigger,
                                    log,
                                    clock,
                                )
                        },
                    ).build()

            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            assertNull(ledger.get(EPISODE_KEY))
        }
}
