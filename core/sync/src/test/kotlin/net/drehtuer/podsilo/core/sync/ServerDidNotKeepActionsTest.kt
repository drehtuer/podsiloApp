// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import net.drehtuer.podsilo.core.model.port.LogCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * **A 2xx from `episode_action/create` is not proof the server kept anything.**
 *
 * `nextcloud-gpodder` 3.17.0 stores an episode action's URLs in 500-character columns and silently
 * discards an action that does not fit, answering 200 all the same
 * (`thrillfall/nextcloud-gpodder#81`). The author's Supercast feeds sign each enclosure URL with a
 * per-subscriber token and run 516–517 characters, so every decision made on those podcasts was
 * marked synced here and stored nowhere — leaving the episodes new in RePod for ever, with nothing
 * in the app to say so.
 *
 * The fixture below is that feed's real shape rather than an invented long string: the length is
 * what the bug turns on, so a test using a 40-character URL would assert nothing about it.
 */
class ServerDidNotKeepActionsTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC)

    private fun orchestratorOf(
        ledgerRepository: FakeEpisodeLedgerRepository,
        gpodderClient: FakeGpodderClient,
        logRepository: RecordingLogRepository,
        syncStateRepository: FakeSyncStateRepository = FakeSyncStateRepository(),
    ) = SyncOrchestrator(
        FakeFeedRepository(),
        ledgerRepository,
        syncStateRepository,
        gpodderClient,
        logRepository,
        fixedClock,
    )

    private fun skippedRow(
        episodeKey: String,
        feedUrl: String = SUPERCAST_FEED,
        actionedAt: Long = DECIDED_AT,
    ) = EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        enclosureUrl = supercastEnclosure(episodeKey),
        state = LedgerState.SKIPPED,
        actionedAt = actionedAt,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
    )

    private fun playOf(row: EpisodeLedgerRow) =
        EpisodeAction(
            podcast = row.feedUrl,
            episode = row.enclosureUrl,
            guid = row.episodeKey,
            action = EpisodeActionType.PLAY,
            timestamp = row.actionedAt.toGpodderTimestamp(),
            started = 0,
            position = 1,
            total = 1,
        )

    private fun pageOf(vararg actions: EpisodeAction) =
        EpisodeActionPage(actions = actions.toList(), timestamp = SERVER_TIMESTAMP)

    @Test
    fun `a decision the server accepted but did not keep is reported`() {
        val ledger = FakeEpisodeLedgerRepository(listOf(skippedRow("guid-1")))
        val log = RecordingLogRepository()
        // The server took the POST and returned nothing on the pull that followed: the silent drop.
        val client = FakeGpodderClient(episodeActionsPage = pageOf())

        val outcome = runBlocking { orchestratorOf(ledger, client, log).sync() }

        assertTrue(outcome is SyncOutcome.Success)
        val entry = log.recorded.single()
        assertEquals(LogCategory.SYNC, entry.category)
        assertEquals(SUPERCAST_FEED, entry.feedUrl)
        assertTrue(entry.message, entry.message.contains("1 decision(s)"))
        assertTrue(entry.message, entry.message.contains("did not keep them"))
    }

    @Test
    fun `a decision the server echoed back is not reported`() {
        val row = skippedRow("guid-1")
        val ledger = FakeEpisodeLedgerRepository(listOf(row))
        val log = RecordingLogRepository()
        val client = FakeGpodderClient(episodeActionsPage = pageOf(playOf(row)))

        runBlocking { orchestratorOf(ledger, client, log).sync() }

        assertEquals(emptyList<Any>(), log.recorded)
    }

    /**
     * The truncating half of the same bug: a MySQL that is not in strict mode stores 500 characters
     * of the URL rather than refusing it. The action survives, keyed by its `guid`, so this must
     * stay quiet — matching on the enclosure URL instead would report a false loss here.
     */
    @Test
    fun `a decision echoed back with a truncated enclosure URL is not reported`() {
        val row = skippedRow("guid-1")
        val ledger = FakeEpisodeLedgerRepository(listOf(row))
        val log = RecordingLogRepository()
        val truncated = playOf(row).copy(episode = row.enclosureUrl.take(GPODDER_COLUMN_WIDTH))
        val client = FakeGpodderClient(episodeActionsPage = pageOf(truncated))

        runBlocking { orchestratorOf(ledger, client, log).sync() }

        assertEquals(emptyList<Any>(), log.recorded)
    }

    /**
     * The skew guard. The server selects `timestamp_epoch > :since` on the *client-authored*
     * timestamp, so a row older than the `since` we asked for is invisible to the pull whether the
     * server kept it or not — and reporting it would be crying wolf. Silence is the only safe
     * answer, and it is the direction a device whose clock runs behind the server's lands in.
     */
    @Test
    fun `a decision older than the cursor we asked for is not reported`() {
        val row = skippedRow("guid-1", actionedAt = Instant.parse("2026-09-01T09:00:00Z").toEpochMilli())
        val ledger = FakeEpisodeLedgerRepository(listOf(row))
        val log = RecordingLogRepository()
        val client = FakeGpodderClient(episodeActionsPage = pageOf())
        // A day is rewound off this before it is sent (`CURSOR_OVERLAP_SECONDS`), leaving 09-12.
        val syncState =
            FakeSyncStateRepository(
                SyncState(
                    lastEpisodeActionSyncTs = Instant.parse("2026-09-13T12:00:00Z").epochSecond,
                    deviceId = "fake-device",
                ),
            )

        runBlocking { orchestratorOf(ledger, client, log, syncState).sync() }

        assertEquals(emptyList<Any>(), log.recorded)
    }

    @Test
    fun `losses are grouped per podcast, because the cause is a property of the feed`() {
        val ledger =
            FakeEpisodeLedgerRepository(
                listOf(
                    skippedRow("guid-1"),
                    skippedRow("guid-2"),
                    skippedRow("guid-3"),
                    skippedRow("guid-4", feedUrl = OTHER_FEED),
                ),
            )
        val log = RecordingLogRepository()
        val client = FakeGpodderClient(episodeActionsPage = pageOf())

        runBlocking { orchestratorOf(ledger, client, log).sync() }

        assertEquals(2, log.recorded.size)
        val supercast = log.recorded.single { it.feedUrl == SUPERCAST_FEED }
        assertTrue(supercast.message, supercast.message.contains("3 decision(s)"))
        val other = log.recorded.single { it.feedUrl == OTHER_FEED }
        assertTrue(other.message, other.message.contains("1 decision(s)"))
    }

    /**
     * The detail half carries the measured length, because that is what identifies the cause — and
     * carries no URL at all, per `UI.adoc` §11. These are precisely the feeds whose enclosure URLs
     * embed a subscriber token, so writing one into the log would be writing a credential into it.
     */
    @Test
    fun `the detail names the measured length and never the URL`() {
        val row = skippedRow("guid-1")
        val ledger = FakeEpisodeLedgerRepository(listOf(row))
        val log = RecordingLogRepository()
        val client = FakeGpodderClient(episodeActionsPage = pageOf())

        runBlocking { orchestratorOf(ledger, client, log).sync() }

        val entry = log.recorded.single()
        val detail = entry.detail.orEmpty()
        assertTrue(detail, detail.contains("${row.enclosureUrl.length} characters"))
        assertFalse(detail, detail.contains(SUBSCRIBER_TOKEN))
        assertFalse(detail, detail.contains("https://"))
        assertFalse(entry.message, entry.message.contains("https://"))
    }

    /**
     * The rows stay marked synced. Re-sending the same oversized URL fails identically for ever, so
     * an outbox that kept retrying would be a permanent stuck count and a permanent repeat of this
     * entry; the repair is the server's column, and the entry names it. `decisions/0029`.
     */
    @Test
    fun `a dropped decision is not put back in the outbox`() {
        val ledger = FakeEpisodeLedgerRepository(listOf(skippedRow("guid-1")))
        val log = RecordingLogRepository()
        val client = FakeGpodderClient(episodeActionsPage = pageOf())

        runBlocking { orchestratorOf(ledger, client, log).sync() }

        assertEquals(emptyList<EpisodeLedgerRow>(), runBlocking { ledger.getUnsynced() })
    }

    private companion object {
        const val SUPERCAST_FEED = "https://feeds.supercast.com/feeds/4SKcpKm1xDiuPS3EwRR1HWLm"
        const val OTHER_FEED = "https://example.com/feed.xml"
        const val SUBSCRIBER_TOKEN = "4SKcpKm1xDiuPS3EwRR1HWLm"

        /** `gpodder_episode_action.episode` as 3.17.0 creates it. */
        const val GPODDER_COLUMN_WIDTH = 500

        /** The real shape: 37 characters of host and path, a signed hash, then the token and a version. */
        const val SIGNED_HASH_LENGTH = 434

        val DECIDED_AT: Long = Instant.parse("2026-09-13T11:00:00Z").toEpochMilli()
        const val SERVER_TIMESTAMP = 1_789_000_000L

        fun supercastEnclosure(episodeKey: String): String =
            "https://feeds.supercast.com/episodes/" +
                episodeKey.hashCode().toString().padStart(SIGNED_HASH_LENGTH, 'a') +
                ".mp3?key=$SUBSCRIBER_TOKEN&v=369390762"
    }
}
