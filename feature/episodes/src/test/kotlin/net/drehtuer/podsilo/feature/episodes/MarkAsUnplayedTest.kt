// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * *Mark as unplayed* (`decisions/0024`) — the affordance the project declined three times, and
 * the reason it can exist now.
 *
 * Every refusal was the same objection: "undecided" is the **absence** of a ledger row, so un-marking
 * means deleting one, and that row is the only thing standing between the user and downloading a
 * file they already have (CLAUDE.md §11). These tests pin the resolution — a state instead of a
 * deletion — because it is the property that makes the feature safe rather than a detail of it.
 */
class MarkAsUnplayedTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC)
    private val ledger = FakeLedgerRepository()
    private val syncTrigger = RecordingSyncTrigger()
    private val writer = TriageWriter(ledger, clock, syncTrigger)

    private fun episode(key: String = "e1") =
        Episode(
            episodeKey = key,
            feedUrl = "https://example.org/feed.xml",
            guid = key,
            enclosureUrl = "https://example.org/$key.mp3",
            title = "Episode $key",
            description = null,
            pubDate = null,
            durationMs = 1_800_000,
        )

    private fun row(
        state: LedgerState,
        writtenFileName: String? = null,
    ) = EpisodeLedgerRow(
        episodeKey = "e1",
        feedUrl = "https://example.org/feed.xml",
        enclosureUrl = "https://example.org/e1.mp3",
        state = state,
        actionedAt = 0L,
        syncedToServer = true,
        attempts = 3,
        lastError = null,
        writtenFileName = writtenFileName,
        durationSeconds = 1_800,
    )

    @Test
    fun `marking unplayed writes the state rather than deleting the row`() =
        runTest {
            ledger.seedRow(row(LedgerState.SKIPPED))

            writer.markAsUnplayed(listOf(episode()))

            val stored = ledger.get("e1")
            assertNotNull("the row must survive — it is the dedup authority", stored)
            assertEquals(LedgerState.UNPLAYED, stored?.state)
        }

    /**
     * The specific thing a delete would have destroyed. `writtenFileName` is what the duplicate guard
     * checks before a re-download, so losing it here would let a second copy of a file the user
     * already has be written later (`decisions/0012`).
     */
    @Test
    fun `the written file name survives being marked unplayed`() =
        runTest {
            ledger.seedRow(row(LedgerState.DOWNLOADED, writtenFileName = "20260714_Episode.mp3"))

            writer.markAsUnplayed(listOf(episode()))

            assertEquals("20260714_Episode.mp3", ledger.get("e1")?.writtenFileName)
        }

    /** It is a decision like any other, so it goes to Nextcloud on the same terms. */
    @Test
    fun `it is unsynced and asks for a pass, exactly as marking played does`() =
        runTest {
            ledger.seedRow(row(LedgerState.SKIPPED))

            writer.markAsUnplayed(listOf(episode()))

            assertEquals(false, ledger.get("e1")?.syncedToServer)
            assertEquals(1, syncTrigger.requests)
        }

    @Test
    fun `marking nothing writes nothing and asks for nothing`() =
        runTest {
            writer.markAsUnplayed(emptyList())

            assertNull("nothing may be written", ledger.get("e1"))
            assertEquals(0, syncTrigger.requests)
        }

    /** One transaction and one pass for a batch, the same rule the other bulk writes follow. */
    @Test
    fun `a batch asks for one pass, not one per episode`() =
        runTest {
            writer.markAsUnplayed((1..20).map { episode("e$it") })

            assertEquals(1, syncTrigger.requests)
        }
}
