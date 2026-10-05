// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Which cover ends up on a downloaded episode, and — more importantly — every way the answer is
 * "none", since artwork must never cost a delivery (CLAUDE.md §6).
 */
class ArtworkFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: ArtworkFetcher

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = ArtworkFetcher(OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun image(bytes: ByteArray = jpeg) =
        MockResponse()
            .setHeader("Content-Type", "image/jpeg")
            .setBody(Buffer().write(bytes))

    @Test
    fun `the episode's own artwork wins over the podcast's`() {
        server.enqueue(image())

        val artwork = fetcher.fetch(server.url("/episode.jpg").toString(), server.url("/podcast.jpg").toString())

        assertEquals(EpisodeArtwork.Source.EPISODE, artwork?.source)
        assertArrayEquals(jpeg, artwork?.bytes)
        assertEquals("image/jpeg", artwork?.mimeType)
        assertEquals("the podcast cover should not have been fetched at all", 1, server.requestCount)
    }

    @Test
    fun `with no episode artwork it falls back to the podcast's`() {
        server.enqueue(image())

        val artwork = fetcher.fetch(null, server.url("/podcast.jpg").toString())

        assertEquals(EpisodeArtwork.Source.PODCAST, artwork?.source)
    }

    @Test
    fun `a listed but broken episode cover falls through to the podcast's`() {
        // Feeds do list images that 404. Falling through beats leaving the episode bare.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(image())

        val artwork = fetcher.fetch(server.url("/gone.jpg").toString(), server.url("/podcast.jpg").toString())

        assertEquals(EpisodeArtwork.Source.PODCAST, artwork?.source)
    }

    @Test
    fun `an HTML error page served with 200 is not artwork`() {
        // The failure mode that would otherwise embed a login page as a cover.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>nope</html>"))

        assertNull(fetcher.fetch(server.url("/lying.jpg").toString(), null))
    }

    @Test
    fun `the response's content type is trusted over the URL's extension`() {
        // Same rule the enclosure extension follows (CLAUDE.md §6): the URL is not evidence.
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(jpeg)))

        assertEquals("image/png", fetcher.fetch(server.url("/cover.jpg").toString(), null)?.mimeType)
    }

    @Test
    fun `no images anywhere is null, not an error`() {
        assertNull(fetcher.fetch(null, null))
        assertNull(fetcher.fetch("", "   "))
        assertEquals("nothing should have been requested", 0, server.requestCount)
    }

    @Test
    fun `an empty body is not artwork`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(""))

        assertNull(fetcher.fetch(server.url("/empty.jpg").toString(), null))
    }

    @Test
    fun `an unreachable host resolves to null rather than throwing`() {
        // A dead image host must not propagate out of a download.
        assertNull(ArtworkFetcher(unresolvableHttpClient()).fetch("https://covers.example.org/cover.jpg", null))
    }

    @Test
    fun `large artwork is embedded as-is, because there is no cap`() {
        // The author's decision: real covers are ~300 KB against a 30 MB episode, and any cap would
        // eventually drop some podcast's art for a reason the user cannot see.
        val large = ByteArray(3 * 1024 * 1024) { it.toByte() }
        server.enqueue(image(large))

        assertEquals(large.size, fetcher.fetch(server.url("/huge.jpg").toString(), null)?.bytes?.size)
    }

    @Test
    fun `artwork compares by its bytes, not by array identity`() {
        // A data class over a ByteArray would otherwise compare two identical downloads unequal, and
        // an assertion of "the same cover" could never pass.
        val a = EpisodeArtwork(jpeg.copyOf(), "image/jpeg", EpisodeArtwork.Source.EPISODE)
        val b = EpisodeArtwork(jpeg.copyOf(), "image/jpeg", EpisodeArtwork.Source.EPISODE)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(source = EpisodeArtwork.Source.PODCAST))
        assertNotEquals(a, b.copy(bytes = byteArrayOf(1)))
    }
}
