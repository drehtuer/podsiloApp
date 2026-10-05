// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Conditional-GET behaviour for the feed HTTP layer. Plain JVM + MockWebServer -- no Robolectric
 * needed here (unlike [FeedXmlParserTest], which drives rssparser's Android target).
 */
class FeedFetcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(readTimeoutMillis: Long = 5_000) =
        FeedFetcher(
            OkHttpClient
                .Builder()
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .build(),
        )

    private fun feedUrl() = server.url("/feed.xml").toString()

    @Test
    fun `a 200 returns the raw bytes plus both cache validators`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"abc123\"")
                    .setHeader("Last-Modified", "Tue, 14 Jul 2026 09:00:00 GMT")
                    .setBody("<rss></rss>"),
            )

            val result = fetcher().fetch(feedUrl())

            val fetched = result as FeedFetchResult.Fetched
            assertEquals("<rss></rss>", String(fetched.bytes))
            assertEquals("\"abc123\"", fetched.httpEtag)
            assertEquals("Tue, 14 Jul 2026 09:00:00 GMT", fetched.httpLastModified)
        }

    @Test
    fun `no conditional headers are sent on a first fetch`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<rss></rss>"))

            fetcher().fetch(feedUrl())

            val request = server.takeRequest()
            assertNull(request.getHeader("If-None-Match"))
            assertNull(request.getHeader("If-Modified-Since"))
        }

    @Test
    fun `stored validators are sent as conditional headers`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(304))

            val result =
                fetcher().fetch(
                    feedUrl(),
                    httpEtag = "\"abc123\"",
                    httpLastModified = "Tue, 14 Jul 2026 09:00:00 GMT",
                )

            // A 304 is NotModified: no body to parse, the cached episodes stand.
            assertEquals(FeedFetchResult.NotModified, result)
            val request = server.takeRequest()
            assertEquals("\"abc123\"", request.getHeader("If-None-Match"))
            assertEquals("Tue, 14 Jul 2026 09:00:00 GMT", request.getHeader("If-Modified-Since"))
        }

    @Test
    fun `each validator is sent independently when only one is known`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(304))

            fetcher().fetch(feedUrl(), httpEtag = "\"only-etag\"")

            val request = server.takeRequest()
            assertEquals("\"only-etag\"", request.getHeader("If-None-Match"))
            assertNull(request.getHeader("If-Modified-Since"))
        }

    @Test
    fun `a 200 with no validators returns nulls rather than failing`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<rss></rss>"))

            val fetched = fetcher().fetch(feedUrl()) as FeedFetchResult.Fetched

            assertNull(fetched.httpEtag)
            assertNull(fetched.httpLastModified)
        }

    @Test
    fun `a redirect is followed transparently and the final body is returned`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .setHeader("Location", server.url("/moved-feed.xml").toString()),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("<rss>moved</rss>"))

            val fetched = fetcher().fetch(feedUrl()) as FeedFetchResult.Fetched

            assertEquals("<rss>moved</rss>", String(fetched.bytes))
            assertEquals(2, server.requestCount)
            server.takeRequest()
            assertEquals("/moved-feed.xml", server.takeRequest().path)
        }

    @Test
    fun `a non-2xx other than 304 yields HttpError carrying the status code rather than throwing`() =
        runBlocking {
            // The code is what lets FeedRefresher tell a gone feed (404) from one worth retrying (5xx).
            for (code in listOf(404, 500)) {
                server.enqueue(MockResponse().setResponseCode(code))

                val result = fetcher().fetch(feedUrl())

                assertEquals(code, (result as FeedFetchResult.HttpError).code)
            }
        }

    @Test
    fun `a read timeout yields NetworkError rather than throwing`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<rss></rss>")
                    .setBodyDelay(2, TimeUnit.SECONDS),
            )

            val result = fetcher(readTimeoutMillis = 250).fetch(feedUrl())

            assertTrue("expected NetworkError, got $result", result is FeedFetchResult.NetworkError)
        }

    @Test
    fun `an unreachable host yields NetworkError rather than throwing`() =
        runBlocking {
            val unreachable = server.url("/feed.xml").toString()
            server.shutdown()

            val result = fetcher().fetch(unreachable)

            assertTrue("expected NetworkError, got $result", result is FeedFetchResult.NetworkError)
        }

    @Test
    fun `fetched bytes compare by content, not identity`() {
        val a = FeedFetchResult.Fetched("<rss/>".toByteArray(), "etag", null)
        val b = FeedFetchResult.Fetched("<rss/>".toByteArray(), "etag", null)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // The fetch -> parse composition test lives in FeedXmlParserTest instead: it drives rssparser,
    // which needs the Robolectric runner (`architecture.adoc` §7). Keeping it out of this class means
    // these pure-HTTP tests stay on the plain JVM runner.
}
