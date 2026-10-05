// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.TitleCleanupRuleSetting
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jaudiotagger.audio.AudioFileIO
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

/**
 * The cache -> verify -> name -> tag -> deliver -> clean-up pipeline (`architecture.adoc` §11),
 * end to end, with MockWebServer for the network and [FakeDownloadTarget] for the SAF write
 * (`architecture.adoc` §11). No Android, no emulator.
 */
class EpisodeDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var target: FakeDownloadTarget

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = temporaryFolder.newFolder("cache")
        target = FakeDownloadTarget(temporaryFolder.newFolder("tree"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(
        artworkFetcher: ArtworkFetcher? = null,
        enclosureDownloader: EnclosureDownloader = EnclosureDownloader(),
    ) = EpisodeDownloader(
        enclosureDownloader = enclosureDownloader,
        audioTagWriter = AudioTagWriter(),
        downloadTarget = target,
        cacheDir = cacheDir,
        zoneId = ZoneId.of("Europe/Berlin"),
        artworkFetcher = artworkFetcher,
    )

    private fun feed() =
        Feed(
            url = "https://example.org/feed.xml",
            title = "Der Podcast",
            imageUrl = null,
            firstSeenAt = 0,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    private fun episode(
        title: String = "Warum Hamburg immer regnet",
        path: String = "/ep1.mp3",
        // 2026-07-14T09:00:00Z
        pubDate: Long? = 1_784_019_600_000,
    ) = Episode(
        episodeKey = "guid-1",
        feedUrl = "https://example.org/feed.xml",
        guid = "guid-1",
        enclosureUrl = server.url(path).toString(),
        title = title,
        description = "Show notes",
        pubDate = pubDate,
        durationMs = null,
    )

    /** A real (tiny, silent) MP3 so the tag writer has a container it can actually parse. */
    private fun mp3Body(): Buffer {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/audio/silence.mp3")).readBytes()
        return Buffer().write(bytes)
    }

    private fun enqueueMp3(contentType: String = "audio/mpeg") {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(mp3Body()))
    }

    @Test
    fun `delivers into the templated folder and file name, then cleans the cache`() =
        runBlocking {
            enqueueMp3()

            val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

            val delivered = outcome as DownloadOutcome.Delivered
            assertEquals("20260714_Warum Hamburg immer regnet.mp3", delivered.fileName)
            assertTrue(target.delivered("Der Podcast", delivered.fileName).isFile)
            assertEquals(listOf("Der Podcast/20260714_Warum Hamburg immer regnet.mp3"), target.deliveries)
            assertEquals(
                "the cache must not keep a copy of a delivered episode",
                emptyList<String>(),
                cacheDir.listFiles().orEmpty().map { it.name },
            )
        }

    @Test
    fun `the response Content-Type beats the url extension`() =
        runBlocking {
            // URL says .mp3, the server says MP4 audio — CLAUDE.md §6 says believe the server.
            enqueueMp3(contentType = "audio/mp4")

            val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

            assertTrue((outcome as DownloadOutcome.Delivered).fileName.endsWith(".m4a"))
        }

    @Test
    fun `a name already taken in the folder is suffixed, keeping the extension last`() =
        runBlocking {
            val folder = File(temporaryFolder.root, "tree/Der Podcast").apply { mkdirs() }
            File(folder, "20260714_Warum Hamburg immer regnet.mp3").writeText("an earlier, different episode")
            enqueueMp3()

            val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

            assertEquals(
                "20260714_Warum Hamburg immer regnet (2).mp3",
                (outcome as DownloadOutcome.Delivered).fileName,
            )
        }

    @Test
    fun `a retry reuses the recorded file name instead of creating a second copy`() =
        runBlocking {
            val folder = File(temporaryFolder.root, "tree/Der Podcast").apply { mkdirs() }
            val existing = File(folder, "20260714_Warum Hamburg immer regnet.mp3")
            existing.writeText("this episode's own earlier attempt")
            enqueueMp3()

            val outcome =
                downloader().download(
                    DownloadRequest(
                        feed = feed(),
                        episode = episode(),
                        naming = NamingSettings(),
                        previousFileName = "20260714_Warum Hamburg immer regnet.mp3",
                    ),
                )

            assertEquals("20260714_Warum Hamburg immer regnet.mp3", (outcome as DownloadOutcome.Delivered).fileName)
            assertEquals(1, folder.listFiles().orEmpty().size)
            assertFalse("the retry must overwrite its own file", existing.readText().startsWith("this episode's"))
        }

    @Test
    fun `title cleanup rules reach both the file name and the tags`() =
        runBlocking {
            enqueueMp3()
            val naming =
                NamingSettings(
                    titleCleanupRules = listOf(TitleCleanupRuleSetting("""^Ep\.? ?\d+ *[-–—:] *""", "")),
                )

            val outcome =
                downloader().download(
                    DownloadRequest(feed(), episode(title = "Ep. 142 - Warum Hamburg immer regnet"), naming),
                )

            val delivered = outcome as DownloadOutcome.Delivered
            assertEquals("20260714_Warum Hamburg immer regnet.mp3", delivered.fileName)
            assertEquals(TagWriteOutcome.Success, delivered.tagOutcome)
        }

    @Test
    fun `an invalid cleanup rule fails the download visibly rather than being ignored`() =
        runBlocking {
            val naming = NamingSettings(titleCleanupRules = listOf(TitleCleanupRuleSetting("[unclosed", "")))

            val outcome = downloader().download(DownloadRequest(feed(), episode(), naming))

            val failed = outcome as DownloadOutcome.Failed
            assertFalse(failed.retryable)
            assertTrue(failed.reason.contains("invalid rule"))
        }

    @Test
    fun `a file the tagger cannot read is still delivered and still counts as downloaded`() =
        runBlocking {
            // CLAUDE.md §6: never lose a successful download because a tag write failed.
            server.enqueue(MockResponse().setResponseCode(200).setBody("not actually audio"))

            val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

            val delivered = outcome as DownloadOutcome.Delivered
            assertTrue(delivered.tagOutcome is TagWriteOutcome.Failure)
            assertTrue(target.delivered("Der Podcast", delivered.fileName).isFile)
        }

    /**
     * The classification the whole `CLEARTEXT_BLOCKED` value exists for: **not retryable**, and its
     * own cause rather than `NETWORK`. Reported as a network error it retried on a backoff for ever
     * and told the user "the server did not respond" about a request that never left the device.
     *
     * The cleartext refusal is produced the same faithful way as in `EnclosureDownloaderTest` — an
     * OkHttp client whose connection specs exclude cleartext raises the same
     * `UnknownServiceException` Android's network security policy does.
     */
    @Test
    fun `a cleartext enclosure fails without retrying and says so`() =
        runBlocking {
            val tlsOnly =
                EnclosureDownloader(
                    OkHttpClient
                        .Builder()
                        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                        .build(),
                )

            val outcome =
                downloader(enclosureDownloader = tlsOnly)
                    .download(DownloadRequest(feed(), episode(), NamingSettings()))

            val failed = outcome as DownloadOutcome.Failed
            assertFalse("every retry is refused identically", failed.retryable)
            assertEquals(ErrorCause.CLEARTEXT_BLOCKED, failed.cause)
            assertEquals(emptyList<String>(), target.deliveries)
        }

    /**
     * What is worth WorkManager's backoff and what is not: 5xx, 408 and 429 are the server saying
     * "later"; any other 4xx (a pulled episode, an expired signed URL) fails identically on retry.
     * Nothing reaches the user's folder in any case.
     */
    @Test
    fun `HTTP failures are classified into retryable and permanent, with a cause`() =
        runBlocking {
            data class Case(
                val code: Int,
                val retryable: Boolean,
                val cause: ErrorCause,
            )
            val cases =
                listOf(
                    Case(404, retryable = false, cause = ErrorCause.SERVER),
                    Case(503, retryable = true, cause = ErrorCause.SERVER),
                )
            for (case in cases) {
                server.enqueue(MockResponse().setResponseCode(case.code))
                val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

                val failed = outcome as DownloadOutcome.Failed

                assertEquals("HTTP ${case.code}", case.retryable, failed.retryable)
                assertEquals("HTTP ${case.code}", case.cause, failed.cause)
            }
            assertEquals(emptyList<String>(), target.deliveries)
        }

    @Test
    fun `a revoked folder grant fails without retrying, and no partial file is left behind`() =
        runBlocking {
            enqueueMp3()
            target.unavailable = DownloadFolderUnavailableException("permission revoked")

            val outcome = downloader().download(DownloadRequest(feed(), episode(), NamingSettings()))

            val failed = outcome as DownloadOutcome.Failed
            assertFalse(failed.retryable)
            assertEquals("permission revoked", failed.reason)
            assertEquals(emptyList<String>(), cacheDir.listFiles().orEmpty().map { it.name })
        }

    @Test
    fun `an episode with no pubDate still gets a sortable name rather than a bare underscore`() =
        runBlocking {
            enqueueMp3()

            val outcome = downloader().download(DownloadRequest(feed(), episode(pubDate = null), NamingSettings()))

            assertEquals(
                "00000000_Warum Hamburg immer regnet.mp3",
                (outcome as DownloadOutcome.Delivered).fileName,
            )
        }

    @Test
    fun `a delivered episode carries the podcast cover when the feed names one`() =
        runBlocking {
            // The wiring the unit tests either side of it cannot see: that EpisodeDownloader
            // actually asks the fetcher and hands the result to the tag writer.
            enqueueMp3()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(byteArrayOf(7, 7, 7, 7))),
            )
            val downloader = downloader(ArtworkFetcher(OkHttpClient()))

            downloader.download(
                DownloadRequest(
                    feed = feed().copy(imageUrl = server.url("/cover.jpg").toString()),
                    episode = episode(),
                    naming = NamingSettings(),
                ),
            )

            val delivered = target.delivered("Der Podcast", "20260714_Warum Hamburg immer regnet.mp3")
            assertArrayEquals(
                byteArrayOf(7, 7, 7, 7),
                AudioFileIO
                    .read(delivered)
                    .tag
                    ?.firstArtwork
                    ?.binaryData,
            )
        }

    @Test
    fun `the episode's own cover is preferred over the podcast's`() =
        runBlocking {
            enqueueMp3()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(byteArrayOf(1, 1))),
            )
            val downloader = downloader(ArtworkFetcher(OkHttpClient()))

            downloader.download(
                DownloadRequest(
                    feed = feed().copy(imageUrl = server.url("/podcast.jpg").toString()),
                    episode = episode().copy(imageUrl = server.url("/episode.png").toString()),
                    naming = NamingSettings(),
                ),
            )

            val delivered = target.delivered("Der Podcast", "20260714_Warum Hamburg immer regnet.mp3")
            assertArrayEquals(
                byteArrayOf(1, 1),
                AudioFileIO
                    .read(delivered)
                    .tag
                    ?.firstArtwork
                    ?.binaryData,
            )
        }

    @Test
    fun `a dead cover host still delivers the episode`() =
        runBlocking {
            // CLAUDE.md §6: a tagging problem must never lose a successful download, and artwork is
            // the most optional part of tagging.
            enqueueMp3()
            val downloader = downloader(ArtworkFetcher(OkHttpClient()))

            val outcome =
                downloader.download(
                    DownloadRequest(
                        feed = feed().copy(imageUrl = "http://podsilo.invalid/cover.jpg"),
                        episode = episode(),
                        naming = NamingSettings(),
                    ),
                )

            assertTrue("the episode was lost over a cover: $outcome", outcome is DownloadOutcome.Delivered)
        }
}
