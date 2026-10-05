// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OutboundEpisodeActionTest {
    private fun row(
        state: LedgerState,
        durationSeconds: Int? = null,
        guid: String? = "guid-123",
        enclosureUrl: String = "https://example.com/ep.mp3",
    ) = EpisodeLedgerRow(
        episodeKey = guid ?: enclosureUrl,
        feedUrl = "https://example.com/feed.xml",
        enclosureUrl = enclosureUrl,
        state = state,
        actionedAt = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli(),
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        durationSeconds = durationSeconds,
    )

    /**
     * **Changed 2026-08-14** (`decisions/0023`). A completed download used to emit `DOWNLOAD`
     * and nothing else, which against a real Nextcloud means *nothing at all*: the server discards
     * `DOWNLOAD` on arrival and still answers 200, so a downloaded episode stayed new in every other
     * client for ever.
     *
     * The order matters. `DOWNLOAD` first, `PLAY` second — a server that keeps both stores one row
     * per episode, so the later action survives, and the one worth surviving is the one that makes
     * the episode read as handled.
     */
    @Test
    fun `downloaded emits DOWNLOAD and then PLAY`() {
        val actions = row(LedgerState.DOWNLOADED, durationSeconds = 1_800).toOutboundActions()

        assertEquals(listOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.PLAY), actions.map { it.action })

        val download = actions.first()
        assertEquals("https://example.com/feed.xml", download.podcast)
        assertEquals("https://example.com/ep.mp3", download.episode)
        assertEquals("guid-123", download.guid)
        assertEquals("2026-07-14T09:00:00", download.timestamp)
        // A DOWNLOAD carries no playback claim of its own.
        assertNull(download.started)
        assertNull(download.position)
        assertNull(download.total)
    }

    /** The `PLAY` beside a download says *finished*, on exactly the terms every reader uses. */
    @Test
    fun `the download's PLAY reads as ended, with or without a known duration`() {
        listOf(null, 1_800).forEach { duration ->
            val play = row(LedgerState.DOWNLOADED, durationSeconds = duration).toOutboundActions().last()
            val position = requireNotNull(play.position)
            val total = requireNotNull(play.total)

            assertEquals(EpisodeActionType.PLAY, play.action)
            assertEquals(0, play.started)
            assertTrue(
                "duration=$duration produced $position/$total, which reads as unplayed",
                position > 0 && total > 0 && position >= total,
            )
        }
    }

    @Test
    fun `skipped with a known duration encodes started 0, position equals total`() {
        val action = row(LedgerState.SKIPPED, durationSeconds = 1800).toOutboundActions().single()

        assertEquals(EpisodeActionType.PLAY, action.action)
        assertEquals(0, action.started)
        assertEquals(1800, action.position)
        assertEquals(1800, action.total)
    }

    /**
     * `1` is not a fabricated duration. It is the smallest value that says "there was something and
     * it is finished", which is the claim a skip actually makes; CLAUDE.md §6's rule against
     * inventing a plausible-looking duration is what rules out guessing 45 minutes
     * (`decisions/0022`).
     */
    @Test
    fun `skipped with unknown duration sends 1, which is a marker and not a duration`() {
        val action = row(LedgerState.SKIPPED, durationSeconds = null).toOutboundActions().single()

        assertEquals(0, action.started)
        assertEquals(1, action.position)
        assertEquals(1, action.total)
    }

    @Test
    fun `a guid-less row derives guid as null on every action it produces`() {
        val ledgerRow = row(LedgerState.DOWNLOADED, guid = null, enclosureUrl = "https://example.com/ep.mp3")

        assertTrue(ledgerRow.toOutboundActions().all { it.guid == null })
    }

    @Test
    fun `local-only states never produce an outbound action`() {
        val localOnlyStates =
            listOf(
                LedgerState.QUEUED,
                LedgerState.DOWNLOADING,
                LedgerState.ERROR,
                LedgerState.HANDLED_REMOTELY,
            )
        for (state in localOnlyStates) {
            assertTrue("state=$state", row(state).toOutboundActions().isEmpty())
        }
    }

    /**
     * *Mark as unplayed* (`decisions/0024`): the API cannot delete an action, so the withdrawal is a
     * `PLAY` at position 0 with the duration left in `total` -- the shape every gpodder client
     * already writes for *unread*.
     */
    @Test
    fun `unplayed emits a single PLAY at position 0, keeping the duration`() {
        listOf(1_800 to 1_800, null to 1).forEach { (duration, expectedTotal) ->
            val action = row(LedgerState.UNPLAYED, durationSeconds = duration).toOutboundActions().single()

            assertEquals(EpisodeActionType.PLAY, action.action)
            assertEquals(0, action.started)
            assertEquals("duration=$duration", 0, action.position)
            assertEquals("duration=$duration", expectedTotal, action.total)
            assertEquals("2026-07-14T09:00:00", action.timestamp)
        }
    }

    /**
     * What we send has to mean, to a second Podsilo device reading it back, what we meant: a download
     * or a skip is *handled*, an unplayed mark is not. Asserted through the real reading rule rather
     * than restated, so the two halves cannot drift apart.
     */
    @Test
    fun `every outbound action reads back through our own rule as intended`() {
        val expected =
            mapOf(
                LedgerState.DOWNLOADED to true,
                LedgerState.SKIPPED to true,
                LedgerState.UNPLAYED to false,
            )
        for ((state, handled) in expected) {
            for (duration in listOf(null, 1_800)) {
                val actions = row(state, durationSeconds = duration).toOutboundActions()
                assertTrue(
                    "$state/duration=$duration",
                    actions.isNotEmpty() && actions.all { it.meansHandledElsewhere() == handled },
                )
            }
        }
    }
}
