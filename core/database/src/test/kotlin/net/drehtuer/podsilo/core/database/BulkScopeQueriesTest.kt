// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeListRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two queries behind a bulk *Mark as played* (`decisions/0013`): the preview the confirmation
 * dialog shows, and the rows the write then touches.
 *
 * A bulk `PLAY` reaches the shared Nextcloud log and cannot be taken back (CLAUDE.md §5), so the
 * dialog's number is the only safeguard — and it is only a safeguard if
 * [EpisodeListRepositoryImpl.previewUndecided] and [EpisodeListRepositoryImpl.undecided] select
 * exactly the same episodes. Every case here checks both, against one fixture:
 *
 * | key        | feed | pubDate | ledger              |
 * |------------|------|---------|---------------------|
 * | a-old      | a    | 100     | —                   |
 * | a-mid      | a    | 500     | —                   |
 * | a-new      | a    | 900     | —                   |
 * | a-undated  | a    | null    | —                   |
 * | a-played   | a    | 50      | SKIPPED             |
 * | a-unplayed | a    | 60      | UNPLAYED (withdrawn) |
 * | b-old      | b    | 200     | —                   |
 * | b-queued   | b    | 10      | QUEUED              |
 */
class BulkScopeQueriesTest : RoomTestBase() {
    private val feeds by lazy { FeedRepositoryImpl(db.feedDao()) }
    private val episodes by lazy { EpisodeRepositoryImpl(db.episodeDao()) }
    private val ledger by lazy { EpisodeLedgerRepositoryImpl(db.episodeLedgerDao()) }
    private val list by lazy { EpisodeListRepositoryImpl(db.episodeListDao()) }

    private suspend fun seed() {
        feeds.replaceAll(listOf(feed("a"), feed("b")))
        episodes.replaceForFeed(
            "a",
            listOf(
                episode("a-old", "a", pubDate = 100),
                episode("a-mid", "a", pubDate = 500),
                episode("a-new", "a", pubDate = 900),
                episode("a-undated", "a", pubDate = null),
                episode("a-played", "a", pubDate = 50),
                episode("a-unplayed", "a", pubDate = 60),
            ),
        )
        episodes.replaceForFeed(
            "b",
            listOf(episode("b-old", "b", pubDate = 200), episode("b-queued", "b", pubDate = 10)),
        )
        ledger.upsert(ledgerRow("a-played", "a", LedgerState.SKIPPED))
        ledger.upsert(ledgerRow("a-unplayed", "a", LedgerState.UNPLAYED))
        ledger.upsert(ledgerRow("b-queued", "b", LedgerState.QUEUED))
    }

    private data class Case(
        val name: String,
        val scope: BulkScope,
        val expectedKeys: List<String>,
        val expectedCounts: List<FeedUndecidedCount>,
    )

    private val cases =
        listOf(
            // No date restriction: undated episodes are included, because nothing is being claimed
            // about their age. Decided rows are out; a withdrawn (UNPLAYED) one is back in.
            Case(
                name = "all undecided, every feed",
                scope = BulkScope(kind = BulkScopeKind.ALL_UNDECIDED),
                expectedKeys = listOf("a-new", "a-mid", "b-old", "a-old", "a-unplayed", "a-undated"),
                // Largest first, so the dialog leads with the feed that matters most.
                expectedCounts = listOf(FeedUndecidedCount("a", 5), FeedUndecidedCount("b", 1)),
            ),
            // The cutoff is strict and excludes undated episodes: a missing pubDate is not evidence
            // of being old, and sweeping one up would emit a PLAY the user never agreed to.
            Case(
                name = "older than 500, every feed",
                scope = BulkScope(kind = BulkScopeKind.OLDER_THAN, olderThanMillis = 500),
                expectedKeys = listOf("b-old", "a-old", "a-unplayed"),
                expectedCounts = listOf(FeedUndecidedCount("a", 2), FeedUndecidedCount("b", 1)),
            ),
            Case(
                name = "older than 500, one feed",
                scope = BulkScope(kind = BulkScopeKind.OLDER_THAN, olderThanMillis = 500, feedUrl = "a"),
                expectedKeys = listOf("a-old", "a-unplayed"),
                expectedCounts = listOf(FeedUndecidedCount("a", 2)),
            ),
            Case(
                name = "all undecided, one feed",
                scope = BulkScope(kind = BulkScopeKind.ALL_UNDECIDED, feedUrl = "b"),
                expectedKeys = listOf("b-old"),
                expectedCounts = listOf(FeedUndecidedCount("b", 1)),
            ),
            // ALL_UNDECIDED means "no cutoff" even when a stale olderThanMillis rides along in the
            // scope — the repository must not let it narrow the set behind the dialog's back.
            Case(
                name = "all undecided ignores a leftover cutoff",
                scope = BulkScope(kind = BulkScopeKind.ALL_UNDECIDED, olderThanMillis = 1, feedUrl = "b"),
                expectedKeys = listOf("b-old"),
                expectedCounts = listOf(FeedUndecidedCount("b", 1)),
            ),
            Case(
                name = "a cutoff older than everything",
                scope = BulkScope(kind = BulkScopeKind.OLDER_THAN, olderThanMillis = 0),
                expectedKeys = emptyList(),
                expectedCounts = emptyList(),
            ),
        )

    @Test
    fun `preview and write select the same episodes for every scope`() =
        runTest {
            seed()

            cases.forEach { case ->
                val keys = list.undecided(case.scope).map { it.episodeKey }
                val counts = list.previewUndecided(case.scope)

                assertEquals("${case.name}: rows, newest first", case.expectedKeys, keys)
                assertEquals("${case.name}: dialog counts", case.expectedCounts, counts)
                assertEquals(
                    "${case.name}: the dialog promises exactly what gets written",
                    keys.size,
                    counts.sumOf { it.count },
                )
            }
        }

    @Test
    fun `feeds with equal counts are listed in a stable order`() =
        runTest {
            feeds.replaceAll(listOf(feed("z"), feed("m")))
            episodes.replaceForFeed("z", listOf(episode("z1", "z", pubDate = 1)))
            episodes.replaceForFeed("m", listOf(episode("m1", "m", pubDate = 1)))

            assertEquals(
                listOf("m", "z"),
                list.previewUndecided(BulkScope(kind = BulkScopeKind.ALL_UNDECIDED)).map { it.feedUrl },
            )
        }
}
