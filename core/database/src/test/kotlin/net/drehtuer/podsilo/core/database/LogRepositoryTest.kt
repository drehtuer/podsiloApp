// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.dao.LogDao
import net.drehtuer.podsilo.core.database.repository.LogRepositoryImpl
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** S8's backing store: the collapse rule, eviction, and the promise that no credential is ever written. */
@RunWith(RobolectricTestRunner::class)
class LogRepositoryTest {
    private lateinit var db: PodsiloDatabase
    private var now = Instant.parse("2026-08-01T12:00:00Z")

    private val log by lazy {
        LogRepositoryImpl(
            logDao = db.logDao(),
            clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId?): Clock = this

                    override fun instant(): Instant = now
                },
            zone = ZoneOffset.UTC,
        )
    }

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PodsiloDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an identical failure collapses instead of appending`() =
        runTest {
            repeat(3) { log.record(feedTimeout()) }

            val entries = log.observe(null).first()

            assertEquals(1, entries.size)
            assertEquals(3, entries.single().occurrences)
        }

    @Test
    fun `collapsing keeps the first timestamp and advances the last`() =
        runTest {
            log.record(feedTimeout())
            now = Instant.parse("2026-08-01T18:00:00Z")
            log.record(feedTimeout())

            val entry = log.observe(null).first().single()

            assertEquals(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli(), entry.firstSeenAt)
            assertEquals(Instant.parse("2026-08-01T18:00:00Z").toEpochMilli(), entry.at)
        }

    @Test
    fun `different feeds do not collapse together`() =
        runTest {
            log.record(feedTimeout(feedUrl = "https://a.example/feed.xml"))
            log.record(feedTimeout(feedUrl = "https://b.example/feed.xml"))

            assertEquals(2, log.observe(null).first().size)
        }

    @Test
    fun `the newest occurrence's detail replaces the older one`() =
        runTest {
            log.record(feedTimeout(detail = "first attempt"))
            log.record(feedTimeout(detail = "second attempt"))

            assertEquals(
                "second attempt",
                log
                    .observe(null)
                    .first()
                    .single()
                    .detail,
            )
        }

    @Test
    fun `the filter selects one category`() =
        runTest {
            log.record(feedTimeout())
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "Nextcloud returned 401"))

            assertEquals(2, log.observe(null).first().size)
            assertEquals(1, log.observe(LogCategory.SYNC).first().size)
            assertEquals(
                LogCategory.SYNC,
                log
                    .observe(LogCategory.SYNC)
                    .first()
                    .single()
                    .category,
            )
        }

    @Test
    fun `entries come back newest first`() =
        runTest {
            log.record(NewLogEntry(category = LogCategory.FEED, message = "older"))
            now = now.plusSeconds(60)
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "newer"))

            assertEquals(listOf("newer", "older"), log.observe(null).first().map { it.message })
        }

    @Test
    fun `a message differing only in numbers collapses, even when the failures are unrelated`() =
        runTest {
            // The whole point of normalising: the same failure never carries the same text twice —
            // a fresh timeout, a fresh port, a rotating CDN host. An identity over the raw message
            // would collapse nothing and the buffer would evict every one-off error within a day.
            log.record(feedTimeout("Feed server did not respond (timeout after 30012 ms)"))
            log.record(feedTimeout("Feed server did not respond (timeout after 29998 ms)"))

            // A deliberate over-reach, recorded so it is not discovered as a surprise: the identity
            // folds *all* digits, so "attempt 1 of 3" and "attempt 2 of 3" are one entry — which is
            // what we want — but so are two genuinely different failures whose text happens to
            // differ only numerically. Anything that must stay separate gets a distinct feed or
            // episode key, which the identity keeps verbatim.
            log.record(NewLogEntry(category = LogCategory.FEED, message = "failure 1"))
            log.record(NewLogEntry(category = LogCategory.FEED, message = "failure 2"))

            assertEquals(listOf(2, 2), log.observe(null).first().map { it.occurrences })
        }

    @Test
    fun `the buffer evicts the oldest beyond its cap`() =
        runTest {
            // Distinct feeds, not distinct numbers — see the normalising test for why numbering them would
            // have produced exactly one collapsed row and proved nothing about eviction.
            repeat(LogDao.MAX_ENTRIES + 20) { index ->
                now = now.plusSeconds(1)
                log.record(feedTimeout(feedUrl = "https://feed-$index.example/rss"))
            }

            val entries = log.observe(null).first()

            assertEquals(LogDao.MAX_ENTRIES, entries.size)
            assertTrue(entries.none { it.feedUrl == "https://feed-0.example/rss" })
            assertTrue(entries.any { it.feedUrl == "https://feed-${LogDao.MAX_ENTRIES + 19}.example/rss" })
        }

    @Test
    fun `clearing empties the whole buffer, not the current filter`() =
        runTest {
            log.record(feedTimeout())
            log.record(NewLogEntry(category = LogCategory.SYNC, message = "Nextcloud returned 401"))

            log.clear()

            assertEquals(emptyList<String>(), log.observe(null).first().map { it.message })
        }

    @Test
    fun `the plain-text export carries the message, the count and the detail`() =
        runTest {
            log.record(feedTimeout(detail = "SocketTimeoutException"))
            log.record(feedTimeout(detail = "SocketTimeoutException"))

            val text = log.exportPlainText()

            assertTrue(text.contains("Feed server did not respond"))
            assertTrue(text.contains("×2"))
            assertTrue(text.contains("SocketTimeoutException"))
            assertTrue(text.contains("2026-08-01"))
        }

    @Test
    fun `an empty log still exports something readable`() =
        runTest {
            assertTrue(log.exportPlainText().contains("empty"))
        }

    /**
     * `UI.adoc` §11: *"Never contains the app password, the Basic-auth header, or full URLs with
     * credentials"*.
     *
     * This used to be a weaker test whose comment said the store "cannot scrub what it is handed"
     * and deferred the real assertion to the write points "asserted in :app" — where nothing
     * asserted it. It now scrubs, which is the only version of the rule that a sixth write point
     * cannot forget: a credential reaches the log inside an exception message, and every write point
     * is a place to forget it.
     *
     * The shapes are exhaustively covered by `LogRedactionTest`; what this pins is that `record`
     * applies it, on the way in, for every field a user can read.
     */
    @Test
    fun `a credential in what a caller passes never reaches the store`() =
        runTest {
            val appPassword = "aBcD3-fGhIj-KlMnO-pQrSt"

            log.record(
                NewLogEntry(
                    category = LogCategory.AUTH,
                    message = "Sync failed for https://podsilo:$appPassword@cloud.example.org",
                    detail = "Authorization: Basic cG9kc2lsbzphQmNEMw==",
                ),
            )

            val stored = log.observe(category = null).first().single()
            assertFalse("the message must not carry it", stored.message.contains(appPassword))
            assertFalse("nor the detail", stored.detail.orEmpty().contains("cG9kc2lsbzphQmNEMw=="))
            assertEquals("Sync failed for https://cloud.example.org", stored.message)
            assertEquals("Authorization: <redacted>", stored.detail)

            val text = log.exportPlainText()
            assertFalse("nor the text the user shares", text.contains(appPassword))
            assertFalse(text.contains("cG9kc2lsbzphQmNEMw=="))
        }

    /**
     * Redaction must not change what collapses onto what — the identity is built from the redacted
     * message, so two occurrences of the same failure still land on one entry.
     */
    @Test
    fun `redacted entries still collapse onto each other`() =
        runTest {
            repeat(2) {
                log.record(
                    NewLogEntry(
                        category = LogCategory.AUTH,
                        message = "Sync failed for https://podsilo:secret-pw@cloud.example.org",
                    ),
                )
            }

            val entries = log.observe(category = null).first()
            assertEquals(1, entries.size)
            assertEquals(2, entries.single().occurrences)
        }

    private fun feedTimeout(
        message: String = "Feed server did not respond (timeout after 30000 ms)",
        feedUrl: String? = "https://example.com/feed.xml",
        detail: String? = null,
    ) = NewLogEntry(category = LogCategory.FEED, feedUrl = feedUrl, message = message, detail = detail)
}
