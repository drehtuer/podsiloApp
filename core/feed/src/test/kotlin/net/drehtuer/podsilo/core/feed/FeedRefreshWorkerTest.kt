// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.OlderThan
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val NOW_MILLIS = 1_784_019_600_000

/**
 * [FeedRefreshWorker] against MockWebServer + in-memory ports. Robolectric because rssparser's
 * Android target resolves `XmlPullParserFactory` at runtime (`architecture.adoc` §7) — headless, no
 * emulator.
 */
@RunWith(RobolectricTestRunner::class)
class FeedRefreshWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private val feeds = RecordingFeedRepository()
    private val episodes = RecordingEpisodeRepository()
    private val ledger = RecordingLedgerRepository()
    private val settings = FakeRefreshSettings()

    /** The mark-old rule asks for a sync pass when it writes; counted so a test can pin "only then". */
    private val syncTrigger = RecordingFeedSyncTrigger()
    private val log = RecordingLogRepository()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feedXml(itemTitles: List<String>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>")
            append("<title>Der Podcast</title>")
            itemTitles.forEachIndexed { index, title ->
                append("<item><title>$title</title><guid>guid-$index</guid>")
                append("<enclosure url=\"https://example.org/ep$index.mp3\" type=\"audio/mpeg\"/></item>")
            }
            append("</channel></rss>")
        }

    private fun feed(
        path: String = "/feed.xml",
        title: String = "placeholder",
        etag: String? = null,
    ) = Feed(
        url = server.url(path).toString(),
        title = title,
        imageUrl = null,
        firstSeenAt = 0,
        lastRefreshedAt = null,
        httpEtag = etag,
        httpLastModified = null,
    )

    private fun buildWorker(
        feedUrl: String? = null,
        runAttemptCount: Int = 0,
    ): FeedRefreshWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    FeedRefreshWorker(
                        appContext = appContext,
                        workerParameters = workerParameters,
                        feedRefresher =
                            FeedRefresher(
                                feedRepository = feeds,
                                episodeRepository = episodes,
                                feedFetcher = FeedFetcher(),
                                feedXmlParser = FeedXmlParser(),
                                clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC),
                                logRepository = log,
                                markOldEpisodesRule =
                                    MarkOldEpisodesRule(
                                        ledgerRepository = ledger,
                                        listRepository = ledger,
                                        settingsRepository = settings,
                                        syncTrigger = syncTrigger,
                                        clock = Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC),
                                        zone = ZoneOffset.UTC,
                                    ),
                            ),
                    )
            }
        return TestListenableWorkerBuilder<FeedRefreshWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .apply { feedUrl?.let { setInputData(workDataOf(FeedRefreshWorker.KEY_FEED_URL to it)) } }
            .build()
    }

    @Test
    fun `a 200 replaces the feed's episodes and records its title and validators`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v1\"")
                    .setHeader("Last-Modified", "Tue, 14 Jul 2026 09:00:00 GMT")
                    .setBody(feedXml(listOf("Folge 1", "Folge 2"))),
            )
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(
                2,
                episodes.stored.values
                    .single()
                    .size,
            )
            val refreshed = checkNotNull(feeds.get(feed().url))
            assertEquals("Der Podcast", refreshed.title)
            assertEquals("\"v1\"", refreshed.httpEtag)
            assertEquals("Tue, 14 Jul 2026 09:00:00 GMT", refreshed.httpLastModified)
            assertEquals(NOW_MILLIS, refreshed.lastRefreshedAt)
        }

    @Test
    fun `a 304 sends the stored validators and leaves the cached episodes alone`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(304))
            feeds.seed(feed(title = "Der Podcast", etag = "\"v1\""))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            assertTrue("a 304 must not rewrite the episode cache", episodes.stored.isEmpty())

            // **Changed 2026-08-10.** This used to assert `metadataUpdates == 0`, i.e. that a 304
            // wrote nothing at all. But a 304 is a *successful check* — the feed was reached and is
            // unchanged — and recording nothing meant `lastRefreshedAt` never moved for a feed that
            // rarely changes. S1 would show "last refreshed 3 d ago" for a feed checked every 15
            // minutes, and S2's feed-error banner (which shows an error newer than the last success)
            // could never clear. What must not change is everything the 304 did not re-fetch.
            val stored = checkNotNull(feeds.get(feed(title = "Der Podcast", etag = "\"v1\"").url))
            assertEquals("the validators that produced the 304 must survive it", "\"v1\"", stored.httpEtag)
            assertEquals("Der Podcast", stored.title)
            assertEquals(NOW_MILLIS, stored.lastRefreshedAt)
        }

    @Test
    fun `one feed failing transiently does not stop the others, and asks for a retry`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed("/broken.xml"), feed("/working.xml"))

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertEquals(setOf(feed("/working.xml").url), episodes.stored.keys)
        }

    @Test
    fun `a permanently gone feed is not retried and is not unsubscribed either`() =
        runBlocking {
            // Subscriptions belong to the server; a 404 here is not ours to act on (CLAUDE.md §1).
            server.enqueue(MockResponse().setResponseCode(404))
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, feeds.getAll().size)
        }

    @Test
    fun `unparseable XML keeps the previous cache instead of wiping it`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("this is not XML at all"))
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertTrue(episodes.stored.isEmpty())
        }

    @Test
    fun `refreshing a large feed writes episodes and nothing else`() =
        runBlocking {
            // The no-auto-download invariant at the refresh layer (CLAUDE.md §7 item 6): a feed with
            // hundreds of untouched episodes must produce exactly zero downloads and zero actions.
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml((1..500).map { "Folge $it" })))
            feeds.seed(feed())

            buildWorker().doWork()

            assertEquals(
                500,
                episodes.stored.values
                    .single()
                    .size,
            )
            assertTrue("a refresh writes no ledger rows when the mark-old rule is off", ledger.writes.isEmpty())
        }

    @Test
    fun `with the mark-old rule off, nothing is ever marked`() =
        runBlocking {
            settings.markOldOlderThan = OlderThan.OFF
            ledger.seedUndecided(listOf(oldEpisode("ancient", pubDate = 0)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed())

            buildWorker().doWork()

            assertTrue(ledger.writes.isEmpty())
            assertTrue("the rule must not even query when it is off", ledger.queriedScopes.isEmpty())
            assertEquals("and a refresh that marks nothing schedules no sync", 0, syncTrigger.requests)
        }

    @Test
    fun `the mark-old rule writes SKIPPED, never QUEUED`() =
        runBlocking {
            // decisions/0013 amended CLAUDE.md §5 to permit this write — but only this one.
            // FeedRefresher has no download dependency at all, so a QUEUED row is not merely absent,
            // it is unreachable; this pins the guarantee against a future edit that adds one.
            settings.markOldOlderThan = OlderThan.MONTH_3
            ledger.seedUndecided(listOf(oldEpisode("ancient", pubDate = 0)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed())

            buildWorker().doWork()

            val written = ledger.writes.single()
            assertEquals("ancient", written.episodeKey)
            assertEquals(LedgerState.SKIPPED, written.state)
            assertTrue("the PLAY action goes through the normal outbox", !written.syncedToServer)
            // Issue #60: these are `PLAY` actions the author consented to once, at the setting, and they
            // are worth no less than a hand-made decision — so the rule asks for a pass of its own
            // rather than leaving them for whatever happens to sync next.
            assertEquals(1, syncTrigger.requests)
        }

    @Test
    fun `the rule uses the configured cutoff, and undated episodes are never swept up`() =
        runBlocking {
            settings.markOldOlderThan = OlderThan.MONTH_1
            ledger.seedUndecided(
                listOf(
                    oldEpisode("ancient", pubDate = 0),
                    // No pubDate is not evidence of being old — marking it would emit a PLAY the
                    // user never agreed to, and it cannot be taken back.
                    oldEpisode("undated", pubDate = null),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed())

            buildWorker().doWork()

            assertEquals(listOf("ancient"), ledger.writes.map { it.episodeKey })
            val scope = ledger.queriedScopes.single()
            assertEquals(BulkScopeKind.OLDER_THAN, scope.kind)
            assertEquals(
                OlderThan.MONTH_1.cutoffMillis(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC),
                scope.olderThanMillis,
            )
        }

    @Test
    fun `a feed failure is written to the error log in plain language`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            feeds.seed(feed())

            buildWorker().doWork()

            val entry = log.recorded.single()
            assertEquals(LogCategory.FEED, entry.category)
            assertTrue("the sentence the user reads comes first", entry.message.contains("could not be loaded"))
            assertTrue("the technical half is separate", entry.detail.orEmpty().contains("503"))
        }

    @Test
    fun `a feed server that drops the connection is logged and retried`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            feeds.seed(feed())

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            val entry = log.recorded.single()
            assertEquals(LogCategory.FEED, entry.category)
            assertTrue(entry.message.contains("did not respond"))
            assertTrue("the cached episodes stand", episodes.stored.isEmpty())
        }

    @Test
    fun `out of attempts, a failing pass ends as success - the next scheduled pass tries again`() =
        runBlocking {
            // "Not now", not "never": a failure here would mark the unique work FAILED, and nothing
            // is lost — episodes are a disposable cache (CLAUDE.md §5).
            server.enqueue(MockResponse().setResponseCode(503))
            feeds.seed(feed())

            assertEquals(ListenableWorker.Result.success(), buildWorker(runAttemptCount = 3).doWork())
        }

    @Test
    fun `a pass scoped to one feed fetches only that feed`() =
        runBlocking {
            // S2's pull-to-refresh is about the podcast on screen; refreshing every feed for it would
            // make one pull cost a whole pass.
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed("/other.xml"), feed("/pulled.xml"))

            val result = buildWorker(feedUrl = feed("/pulled.xml").url).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, server.requestCount)
            assertEquals("/pulled.xml", server.takeRequest().path)
            assertEquals(setOf(feed("/pulled.xml").url), episodes.stored.keys)
        }

    @Test
    fun `a scoped pass for a feed that has since been unsubscribed is a quiet success`() =
        runBlocking {
            // The subscription list is the server's (CLAUDE.md §1); the screen empties on the next sync.
            feeds.seed(feed("/other.xml"))

            val result = buildWorker(feedUrl = feed("/gone.xml").url).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, server.requestCount)
            assertTrue(log.recorded.isEmpty())
        }

    @Test
    fun `with the rule on but nothing old enough, nothing is written and no sync is asked for`() =
        runBlocking {
            // An empty refresh must not schedule work.
            settings.markOldOlderThan = OlderThan.MONTH_3
            server.enqueue(MockResponse().setResponseCode(200).setBody(feedXml(listOf("Folge 1"))))
            feeds.seed(feed())

            buildWorker().doWork()

            assertEquals("the rule did run", 1, ledger.queriedScopes.size)
            assertTrue(ledger.writes.isEmpty())
            assertEquals(0, syncTrigger.requests)
        }

    @Test
    fun `work requests carry their scope, need a network, and coalesce per feed`() {
        val scoped = FeedRefreshWorker.request("https://example.org/feed.xml")

        assertEquals("https://example.org/feed.xml", scoped.workSpec.input.getString(FeedRefreshWorker.KEY_FEED_URL))
        val everything = FeedRefreshWorker.request().workSpec.input
        assertNull("absent means all feeds", everything.getString(FeedRefreshWorker.KEY_FEED_URL))
        assertEquals(NetworkType.CONNECTED, scoped.workSpec.constraints.requiredNetworkType)
        assertEquals(
            NetworkType.CONNECTED,
            FeedRefreshWorker
                .periodicRequest(60)
                .workSpec.constraints.requiredNetworkType,
        )
        assertEquals(FeedRefreshWorker.UNIQUE_WORK_NAME, FeedRefreshWorker.uniqueWorkName(null))
        assertNotEquals(
            "two pulls on different feeds must not replace each other",
            FeedRefreshWorker.uniqueWorkName("https://a.example/feed.xml"),
            FeedRefreshWorker.uniqueWorkName("https://b.example/feed.xml"),
        )
    }

    private fun oldEpisode(
        key: String,
        pubDate: Long?,
    ) = Episode(
        episodeKey = key,
        feedUrl = feed().url,
        guid = key,
        enclosureUrl = "https://example.org/$key.mp3",
        title = key,
        description = null,
        pubDate = pubDate,
        durationMs = null,
    )
}

private class RecordingFeedRepository : FeedRepository {
    private val feeds = mutableListOf<Feed>()
    var metadataUpdates: Int = 0
        private set

    fun seed(vararg seeded: Feed) {
        feeds += seeded
    }

    override fun observeAll(): Flow<List<Feed>> = MutableStateFlow(feeds.toList())

    override suspend fun getAll(): List<Feed> = feeds.toList()

    override suspend fun get(url: String): Feed? = feeds.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) = error("the refresh worker never writes the subscription list")

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) {
        metadataUpdates++
        val index = feeds.indexOfFirst { it.url == feedUrl }
        if (index < 0) return
        feeds[index] =
            feeds[index].copy(
                title = metadata.title,
                imageUrl = metadata.imageUrl,
                httpEtag = metadata.httpEtag,
                httpLastModified = metadata.httpLastModified,
                lastRefreshedAt = metadata.refreshedAt,
            )
    }
}

private class RecordingEpisodeRepository : EpisodeRepository {
    val stored = mutableMapOf<String, List<Episode>>()

    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> = MutableStateFlow(stored[feedUrl].orEmpty())

    override suspend fun latestPublicationByFeed(): Map<String, Long> =
        stored
            .mapValues { (_, episodes) -> episodes.mapNotNull { it.pubDate }.maxOrNull() }
            .mapNotNull { (feedUrl, latest) -> latest?.let { feedUrl to it } }
            .toMap()

    override suspend fun get(episodeKey: String): Episode? =
        stored.values.flatten().firstOrNull {
            it.episodeKey ==
                episodeKey
        }

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) {
        stored[feedUrl] = episodes
    }

    override suspend fun deleteForFeed(feedUrl: String) {
        stored.remove(feedUrl)
    }
}
