// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the full bytes -> [ParsedFeed] pipeline against static fixtures in
 * `src/test/resources/feeds/` -- no network call anywhere in this class (CLAUDE.md section 7 item
 * 2). Robolectric is required for rssparser's Android target to resolve an
 * `org.xmlpull.v1.XmlPullParserFactory` implementation -- see `architecture.adoc` §7.
 */
@RunWith(RobolectricTestRunner::class)
class FeedXmlParserTest {
    private val feedUrl = "https://example.com/feed.xml"
    private val parser = FeedXmlParser()

    private fun fixtureBytes(name: String): ByteArray {
        val bytes = javaClass.classLoader?.getResourceAsStream("feeds/$name")?.use { it.readBytes() }
        return requireNotNull(bytes) { "missing fixture: $name" }
    }

    private fun parseFixture(name: String) = runBlocking { parser.parse(feedUrl, fixtureBytes(name)) }

    @Test
    fun `a valid feed maps channel metadata and both episodes, newest first, with parsed durations`() {
        val result = parseFixture("valid_minimal.xml")

        assertEquals("Der Podcast", result.metadata.title)
        assertEquals("https://example.com/art.jpg", result.metadata.imageUrl)

        assertEquals(2, result.episodes.size)
        val first = result.episodes[0]
        assertEquals("Folge 2: Warum Hamburg immer regnet", first.title)
        assertEquals("guid-episode-2", first.guid)
        assertEquals("guid-episode-2", first.episodeKey)
        assertEquals("https://example.com/episodes/ep2.mp3", first.enclosureUrl)
        assertEquals((1 * 60 + 32) * 60 * 1000L + 15 * 1000L, first.durationMs)
        // Tue, 14 Jul 2026 09:00:00 +0000 — the instant, independent of the device's zone.
        assertEquals(1_784_019_600_000L, first.pubDate)
        assertEquals("Eine Folge über das Wetter in Hamburg.", first.description)

        val second = result.episodes[1]
        assertEquals("Folge 1: Der Anfang", second.title)
        assertEquals(3_600_000L, second.durationMs)
    }

    @Test
    fun `an episode without a guid derives episodeKey from the enclosure url`() {
        val result = parseFixture("missing_guid.xml")

        val episode = result.episodes.single()
        assertNull(episode.guid)
        assertEquals("https://example.com/episodes/no-guid.mp3", episode.episodeKey)
    }

    @Test
    fun `a reused guid across items keeps only the first (newest) occurrence`() {
        val result = parseFixture("duplicate_guid.xml")

        assertEquals(1, result.episodes.size)
        assertEquals("Second Episode, Same GUID", result.episodes.single().title)
    }

    @Test
    fun `an item with no enclosure is excluded entirely`() {
        val result = parseFixture("missing_enclosure.xml")

        assertEquals(1, result.episodes.size)
        assertEquals("Episode With an Enclosure", result.episodes.single().title)
    }

    @Test
    fun `malformed and missing pubDate both degrade to null, not a crash`() {
        val result = parseFixture("bad_dates.xml")

        assertEquals(2, result.episodes.size)
        assertTrue(result.episodes.all { it.pubDate == null })
    }

    @Test
    fun `content encoded CDATA HTML is preferred over the plain description`() {
        val result = parseFixture("cdata_html_description.xml")

        val episode = result.episodes.single()
        assertEquals(
            """<p>Full show notes with <a href="https://example.com">a link</a>.</p>""",
            episode.description,
        )
    }

    @Test
    fun `a genuinely non-utf-8-encoded feed is decoded correctly, not mangled`() {
        val result = parseFixture("wrong_encoding_iso_8859_1.xml")

        assertEquals("Über den Wolken - Der Podcast", result.metadata.title)
        assertEquals("Warum Käse besser schmeckt als Brötchen", result.episodes.single().title)
    }

    @Test
    fun `a missing itunes duration yields a null durationMs, never a fabricated one`() {
        val result = parseFixture("no_duration.xml")

        assertNull(result.episodes.single().durationMs)
    }

    @Test
    fun `a 500-episode feed parses to 500 episodes and nothing else -- no side effects to have`() =
        runBlocking {
            // The parsing half of CLAUDE.md §7 item 6's no-auto-download invariant. :core:feed has no
            // ledger, no GpodderClient and no downloader to reach for, so a huge backlog is structurally
            // incapable of triggering a download or an episode action here — this locks that in and
            // proves the parser scales to a realistic worst-case feed. The sync half lives in
            // :core:sync's NoAutoDownloadInvariantTest.
            val episodeCount = 500
            val items =
                (1..episodeCount).joinToString("\n") { index ->
                    """
            |  <item>
            |    <title>Episode $index</title>
            |    <guid isPermaLink="false">guid-$index</guid>
            |    <pubDate>Tue, 14 Jul 2026 09:00:00 +0000</pubDate>
            |    <enclosure url="https://example.com/episodes/ep$index.mp3" length="1000" type="audio/mpeg"/>
            |  </item>
                    """.trimMargin()
                }
            val bigFeed =
                """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
            |  <channel>
            |    <title>A Very Long Running Podcast</title>
            |$items
            |  </channel>
            |</rss>
                """.trimMargin().toByteArray()

            val result = parser.parse(feedUrl, bigFeed)

            assertEquals(episodeCount, result.episodes.size)
            assertEquals("guid-1", result.episodes.first().episodeKey)
            assertEquals("guid-$episodeCount", result.episodes.last().episodeKey)
        }

    @Test
    fun `an unknown enclosure length is no size at all, and the item link is kept trimmed`() =
        runBlocking {
            // Feeds write length="0" when they mean "no idea"; "0 MB" on a row would be a lie.
            val xml =
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<rss version="2.0"><channel><title>T</title>
                |  <item><title>Zero</title><guid>zero</guid><link>  https://example.com/zero  </link>
                |    <enclosure url="https://example.com/zero.mp3" length="0" type="audio/mpeg"/></item>
                |  <item><title>Absent</title><guid>absent</guid>
                |    <enclosure url="https://example.com/absent.mp3" type="audio/mpeg"/></item>
                |</channel></rss>
                """.trimMargin().toByteArray()

            val byGuid = parser.parse(feedUrl, xml).episodes.associateBy { it.guid }

            assertNull(byGuid.getValue("zero").sizeBytes)
            assertNull(byGuid.getValue("absent").sizeBytes)
            assertEquals("https://example.com/zero", byGuid.getValue("zero").link)
            assertNull("no link is never synthesised from the enclosure", byGuid.getValue("absent").link)
        }

    @Test
    fun `bytes fetched over HTTP feed straight into the parser -- the two layers compose`() =
        runBlocking {
            // Lives here rather than in FeedFetcherTest because it drives rssparser, which needs the
            // Robolectric runner (`architecture.adoc` §7). Proves FeedFetcher hands back bytes the parser
            // accepts unmodified — no intermediate decoding step is missing between the two layers.
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(fixtureBytes("valid_minimal.xml"))),
                )
                val url = server.url("/feed.xml").toString()

                val fetched = FeedFetcher().fetch(url) as FeedFetchResult.Fetched
                val parsed = FeedXmlParser().parse(url, fetched.bytes)

                assertEquals("Der Podcast", parsed.metadata.title)
                assertEquals(2, parsed.episodes.size)
            } finally {
                server.shutdown()
            }
        }
}
