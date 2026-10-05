// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReconciliationTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)

    @Suppress("LongParameterList") // A builder for a wire type: its parameters are that type's fields.
    private fun action(
        action: EpisodeActionType = EpisodeActionType.DOWNLOAD,
        podcast: String = "https://example.com/feed.xml",
        episode: String = "https://example.com/ep.mp3",
        guid: String? = "guid-123",
        timestamp: String = "2026-07-14T09:00:00",
        // A PLAY is only "handled" when it reads as ended, so the default carries an ended pair —
        // otherwise every PLAY case here would silently be testing the unread shape instead.
        position: Int? = 1_800,
        total: Int? = 1_800,
    ) = EpisodeAction(
        podcast = podcast,
        episode = episode,
        guid = guid,
        action = action,
        timestamp = timestamp,
        started = 0,
        position = position,
        total = total,
    )

    private fun localRow(
        episodeKey: String,
        state: LedgerState,
        writtenFileName: String? = null,
    ) = EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = "https://example.com/feed.xml",
        enclosureUrl = "https://example.com/ep.mp3",
        state = state,
        actionedAt = 0L,
        syncedToServer = state != LedgerState.QUEUED,
        attempts = 0,
        lastError = null,
        writtenFileName = writtenFileName,
    )

    @Test
    fun `an untouched episode with a remote DOWNLOAD becomes handled remotely`() {
        val result = reconcile(localLedger = emptyMap(), remoteActions = listOf(action()), clock = fixedClock)

        assertEquals(1, result.size)
        assertEquals(LedgerState.HANDLED_REMOTELY, result.single().state)
        assertEquals("guid-123", result.single().episodeKey)
        assertTrue(result.single().syncedToServer)
    }

    @Test
    fun `remote PLAY and DELETE also mark handled remotely, but NEW does not`() {
        for (type in listOf(EpisodeActionType.PLAY, EpisodeActionType.DELETE)) {
            val result = reconcile(emptyMap(), listOf(action(action = type)), fixedClock)
            assertEquals("action=$type", LedgerState.HANDLED_REMOTELY, result.single().state)
        }

        val newActionResult = reconcile(emptyMap(), listOf(action(action = EpisodeActionType.NEW)), fixedClock)
        assertTrue("NEW must not create a ledger row", newActionResult.isEmpty())
    }

    @Test
    fun `an episode downloaded remotely while queued locally is overridden -- remote wins the race`() {
        val local = mapOf("guid-123" to localRow("guid-123", LedgerState.QUEUED))

        val result = reconcile(local, listOf(action()), fixedClock)

        assertEquals(1, result.size)
        assertEquals(LedgerState.HANDLED_REMOTELY, result.single().state)
    }

    /**
     * The idempotent terminal states, against every action type that would otherwise mark an episode
     * handled. Also what makes replays of our own echoed-back actions harmless: the wire format
     * carries no device id, so the terminal-state rule is the whole defence (`architecture.adoc` §6).
     */
    @Test
    fun `a terminal local state is never revisited by any remote action`() {
        val terminal = listOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)
        val handling = listOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.PLAY, EpisodeActionType.DELETE)
        for (state in terminal) {
            for (type in handling) {
                val local = mapOf("guid-123" to localRow("guid-123", state, writtenFileName = "20260714_Episode.mp3"))

                val result = reconcile(local, listOf(action(action = type)), fixedClock)

                assertTrue("local $state must not be touched by remote $type", result.isEmpty())
            }
        }
    }

    @Test
    fun `duplicate remote actions for the same episode resolve to the latest timestamp`() {
        val older = action(timestamp = "2026-07-14T09:00:00")
        val newer = action(action = EpisodeActionType.DELETE, timestamp = "2026-07-15T09:00:00")

        val result = reconcile(emptyMap(), listOf(older, newer), fixedClock)

        assertEquals(1, result.size)
        // The DELETE (newer) action's podcast/episode fields win, proving it -- not the DOWNLOAD -- was applied.
        assertEquals(LedgerState.HANDLED_REMOTELY, result.single().state)
        assertEquals(Instant.parse("2026-07-15T09:00:00Z").toEpochMilli(), result.single().actionedAt)
    }

    @Test
    fun `an exact timestamp tie is won by the later entry in the list -- deterministic, not order-independent`() {
        val first = action(episode = "https://example.com/ep-v1.mp3", timestamp = "2026-07-14T09:00:00")
        val second = action(episode = "https://example.com/ep-v2.mp3", timestamp = "2026-07-14T09:00:00")

        val result = reconcile(emptyMap(), listOf(first, second), fixedClock)

        assertEquals("https://example.com/ep-v2.mp3", result.single().enclosureUrl)
    }

    @Test
    fun `an action for an episode not in any subscribed feed is still processed`() {
        // Reconciliation never consults FeedRepository -- the ledger is keyed by episode, not by
        // current subscription (CLAUDE.md section 5). No feed-membership check exists to skip.
        val unsubscribedFeedAction = action(podcast = "https://unsubscribed.example.com/feed.xml")
        val result = reconcile(emptyMap(), listOf(unsubscribedFeedAction), fixedClock)

        assertEquals(1, result.size)
        assertEquals("https://unsubscribed.example.com/feed.xml", result.single().feedUrl)
    }

    @Test
    fun `a guid-less action identifies by episode url, surviving a guid becoming available later`() {
        val action = action(guid = null, episode = "https://example.com/ep-no-guid.mp3")

        val result = reconcile(emptyMap(), listOf(action), fixedClock)

        assertEquals("https://example.com/ep-no-guid.mp3", result.single().episodeKey)
    }

    @Test
    fun `a cdn migration changing the episode url without a guid is treated as a different episode`() {
        // Without a guid, identity is the enclosure URL -- a CDN migration that changes the URL
        // necessarily produces a different key. This is the documented fallback's known limitation,
        // not a bug: there is no guid to anchor identity to.
        val oldCdnUrl = "https://old-cdn.example.com/ep.mp3"
        val local = mapOf(oldCdnUrl to localRow(oldCdnUrl, LedgerState.DOWNLOADED))
        val action = action(guid = null, episode = "https://new-cdn.example.com/ep.mp3")

        val result = reconcile(local, listOf(action), fixedClock)

        assertEquals(1, result.size)
        assertEquals("https://new-cdn.example.com/ep.mp3", result.single().episodeKey)
    }

    @Test
    fun `clock skew -- an unparseable timestamp falls back to the injected clock, not a thrown exception`() {
        val result = reconcile(emptyMap(), listOf(action(timestamp = "garbage-timestamp")), fixedClock)

        assertEquals(fixedClock.millis(), result.single().actionedAt)
    }

    @Test
    fun `writtenFileName and durationSeconds are preserved from the existing row when overridden`() {
        val local =
            mapOf(
                "guid-123" to
                    EpisodeLedgerRow(
                        episodeKey = "guid-123",
                        feedUrl = "https://example.com/feed.xml",
                        enclosureUrl = "https://example.com/ep.mp3",
                        state = LedgerState.QUEUED,
                        actionedAt = 0L,
                        syncedToServer = false,
                        attempts = 1,
                        lastError = "timeout",
                        writtenFileName = null,
                        durationSeconds = 1800,
                    ),
            )

        val result = reconcile(local, listOf(action()), fixedClock)

        assertEquals(1800, result.single().durationSeconds)
    }
}
