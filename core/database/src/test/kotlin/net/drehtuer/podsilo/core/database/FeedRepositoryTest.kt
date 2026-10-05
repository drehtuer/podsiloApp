// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedRepositoryTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }

    @Test
    fun `replaceAll is wholesale - removed feeds vanish, new feeds appear`() =
        runTest {
            feeds.replaceAll(listOf(feed("a"), feed("b")))
            assertEquals(
                setOf("a", "b"),
                feeds
                    .observeAll()
                    .first()
                    .map { it.url }
                    .toSet(),
            )

            feeds.replaceAll(listOf(feed("b"), feed("c")))
            assertEquals(
                setOf("b", "c"),
                feeds
                    .observeAll()
                    .first()
                    .map { it.url }
                    .toSet(),
            )
        }

    @Test
    fun `an empty subscription list clears the mirror`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            feeds.replaceAll(emptyList())
            assertEquals(emptyList<String>(), feeds.observeAll().first().map { it.url })
        }

    @Test
    fun `re-seeing an existing feed keeps its cached episodes (upsert, not delete+insert)`() =
        runTest {
            feeds.replaceAll(listOf(feed("a", firstSeenAt = 111)))
            episodes.replaceForFeed("a", listOf(episode("e1", feedUrl = "a")))

            // Same feed reappears alongside a new one — its episodes must not be wiped by a REPLACE cascade.
            feeds.replaceAll(listOf(feed("a", firstSeenAt = 111), feed("b", firstSeenAt = 222)))

            assertEquals(listOf("e1"), episodes.observeForFeed("a").first().map { it.episodeKey })
            assertEquals(111L, feeds.get("a")?.firstSeenAt)
        }

    @Test
    fun `getAll and get are one-shot snapshots of the same rows`() =
        runTest {
            feeds.replaceAll(listOf(feed("a"), feed("b")))

            assertEquals(setOf("a", "b"), feeds.getAll().map { it.url }.toSet())
            assertEquals("a", feeds.get("a")?.url)
            assertNull(feeds.get("nope"))
        }

    @Test
    fun `updateRefreshMetadata writes fetch results without touching firstSeenAt`() =
        runTest {
            feeds.replaceAll(listOf(feed("a", title = "a", firstSeenAt = 111)))

            feeds.updateRefreshMetadata(
                feedUrl = "a",
                metadata =
                    FeedRefreshMetadata(
                        title = "Der Podcast",
                        imageUrl = "https://example.org/cover.jpg",
                        httpEtag = "\"etag-1\"",
                        httpLastModified = "Wed, 15 Jul 2026 09:00:00 GMT",
                        refreshedAt = 999,
                    ),
            )

            val stored = checkNotNull(feeds.get("a"))
            assertEquals("Der Podcast", stored.title)
            assertEquals("https://example.org/cover.jpg", stored.imageUrl)
            assertEquals("\"etag-1\"", stored.httpEtag)
            assertEquals("Wed, 15 Jul 2026 09:00:00 GMT", stored.httpLastModified)
            assertEquals(999L, stored.lastRefreshedAt)
            // Write-once: it drives the backlog cutoff, so a refresh must never move it (CLAUDE.md §5).
            assertEquals(111L, stored.firstSeenAt)
        }

    @Test
    fun `updateRefreshMetadata for an unsubscribed feed does not resurrect it`() =
        runTest {
            feeds.replaceAll(listOf(feed("a")))
            feeds.replaceAll(emptyList())

            // An in-flight refresh finishing after the feed was dropped from the server list.
            feeds.updateRefreshMetadata("a", FeedRefreshMetadata("Der Podcast", null, null, null, 999))

            assertEquals(emptyList<String>(), feeds.getAll().map { it.url })
        }
}
