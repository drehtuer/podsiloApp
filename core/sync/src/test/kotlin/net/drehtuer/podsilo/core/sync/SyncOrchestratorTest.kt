// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SyncOrchestratorTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC)

    private fun orchestratorOf(
        feedRepository: FakeFeedRepository = FakeFeedRepository(),
        ledgerRepository: FakeEpisodeLedgerRepository = FakeEpisodeLedgerRepository(),
        syncStateRepository: FakeSyncStateRepository = FakeSyncStateRepository(),
        gpodderClient: FakeGpodderClient = FakeGpodderClient(),
        logRepository: RecordingLogRepository = RecordingLogRepository(),
    ) = SyncOrchestrator(
        feedRepository,
        ledgerRepository,
        syncStateRepository,
        gpodderClient,
        logRepository,
        fixedClock,
    )

    private fun downloadedRow(episodeKey: String = "guid-1") =
        EpisodeLedgerRow(
            episodeKey = episodeKey,
            feedUrl = "https://example.com/feed.xml",
            enclosureUrl = "https://example.com/ep1.mp3",
            state = LedgerState.DOWNLOADED,
            actionedAt = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli(),
            syncedToServer = false,
            attempts = 0,
            lastError = null,
            writtenFileName = "20260714_Episode.mp3",
        )

    private fun feed(url: String) =
        Feed(
            url = url,
            title = url,
            imageUrl = null,
            firstSeenAt = 0L,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    @Test
    fun `subscriptions are pulled as a full current set, preserving already-known feed metadata`() =
        runBlocking {
            val existingFeed =
                Feed(
                    url = "https://example.com/feed.xml",
                    title = "Der Podcast",
                    imageUrl = "https://example.com/art.png",
                    firstSeenAt = 111L,
                    lastRefreshedAt = 222L,
                    httpEtag = "etag",
                    httpLastModified = "last-mod",
                )
            val feedRepository = FakeFeedRepository(initial = listOf(existingFeed))
            val subscriptions =
                SubscriptionDelta(
                    add = listOf("https://example.com/feed.xml", "https://new.example.com/feed.xml"),
                    remove = emptyList(),
                    timestamp = 0L,
                )
            val gpodderClient = FakeGpodderClient(subscriptions = subscriptions)

            orchestratorOf(feedRepository = feedRepository, gpodderClient = gpodderClient).sync()

            val byUrl = feedRepository.current.associateBy { it.url }
            assertEquals(existingFeed, byUrl.getValue("https://example.com/feed.xml")) // untouched, not re-created
            val newFeed = byUrl.getValue("https://new.example.com/feed.xml")
            assertEquals("https://new.example.com/feed.xml", newFeed.title) // URL placeholder until first fetch
            assertEquals(fixedClock.millis(), newFeed.firstSeenAt)
        }

    @Test
    fun `add and remove both containing the same url is a net removal`() =
        runBlocking {
            val subscriptions =
                SubscriptionDelta(
                    add = listOf("https://example.com/feed.xml"),
                    remove = listOf("https://example.com/feed.xml"),
                    timestamp = 0L,
                )
            val feedRepository = FakeFeedRepository()
            val gpodderClient = FakeGpodderClient(subscriptions = subscriptions)

            orchestratorOf(feedRepository = feedRepository, gpodderClient = gpodderClient).sync()

            assertTrue(feedRepository.current.isEmpty())
        }

    @Test
    fun `a successful push marks the row synced`() =
        runBlocking {
            val ledgerRepository = FakeEpisodeLedgerRepository(initial = listOf(downloadedRow()))
            val gpodderClient = FakeGpodderClient()

            val outcome = orchestratorOf(ledgerRepository = ledgerRepository, gpodderClient = gpodderClient).sync()

            assertEquals(SyncOutcome.Success, outcome)
            // Two actions for one row since `decisions/0023`: DOWNLOAD, then the PLAY that makes
            // it read as handled on a server which discards DOWNLOAD.
            assertEquals(
                listOf(EpisodeActionType.DOWNLOAD, EpisodeActionType.PLAY),
                gpodderClient.postedActions.map { it.action },
            )
            assertTrue(ledgerRepository.allRows.single().syncedToServer)
        }

    @Test
    fun `only downloaded and skipped rows are ever pushed -- never subscription_change`() =
        runBlocking {
            // FakeGpodderClient/GpodderClient expose no subscription_change/create method at all --
            // it is structurally impossible for this orchestrator to call it (CLAUDE.md section 1).
            val queuedRow = downloadedRow("guid-2").copy(state = LedgerState.QUEUED, syncedToServer = false)
            val errorRow = downloadedRow("guid-3").copy(state = LedgerState.ERROR, syncedToServer = false)
            val ledgerRepository = FakeEpisodeLedgerRepository(initial = listOf(downloadedRow(), queuedRow, errorRow))
            val gpodderClient = FakeGpodderClient()

            orchestratorOf(ledgerRepository = ledgerRepository, gpodderClient = gpodderClient).sync()

            // Only the DOWNLOADED row is representable; it produces two actions, and the QUEUED and
            // ERROR rows produce none.
            assertEquals(2, gpodderClient.postedActions.size)
            assertTrue(gpodderClient.postedActions.all { it.guid == "guid-1" })
            val byKey = ledgerRepository.allRows.associateBy { it.episodeKey }
            assertTrue(byKey.getValue("guid-1").syncedToServer)
            val queuedMessage = "QUEUED row has no representable action, so it can't be marked synced"
            val errorMessage = "ERROR row has no representable action, so it can't be marked synced"
            assertFalse(queuedMessage, byKey.getValue("guid-2").syncedToServer)
            assertFalse(errorMessage, byKey.getValue("guid-3").syncedToServer)
        }

    @Test
    fun `a failed push does not advance past reconciliation -- order of operations stops early`() =
        runBlocking {
            val ledgerRepository = FakeEpisodeLedgerRepository(initial = listOf(downloadedRow()))
            val syncStateRepository = FakeSyncStateRepository()
            val gpodderClient =
                FakeGpodderClient(
                    postResult = Result.failure(IOException("connection reset")),
                    episodeActionsPage = EpisodeActionPage(emptyList(), timestamp = 12345L),
                )

            orchestratorOf(
                ledgerRepository = ledgerRepository,
                syncStateRepository = syncStateRepository,
                gpodderClient = gpodderClient,
            ).sync()

            val message = "fetchEpisodeActions must not run after a failed push"
            assertTrue(message, gpodderClient.fetchEpisodeActionsSinceValues.isEmpty())
            assertEquals(0L, syncStateRepository.current.lastEpisodeActionSyncTs)
        }

    @Test
    fun `successful download, then failed POST, then app restart -- retried on the next pass without re-download`() =
        runBlocking {
            // Simulates a restart by constructing a second SyncOrchestrator against the same
            // (persistent, in this fake, in-memory) repositories -- the durability property under
            // test is that the ledger row survives independently of any in-flight orchestrator state.
            val ledgerRepository = FakeEpisodeLedgerRepository(initial = listOf(downloadedRow()))
            val feedRepository = FakeFeedRepository()
            val syncStateRepository = FakeSyncStateRepository()
            val failingClient = FakeGpodderClient(postResult = Result.failure(IOException("offline")))

            val firstPassOutcome =
                orchestratorOf(
                    feedRepository = feedRepository,
                    ledgerRepository = ledgerRepository,
                    syncStateRepository = syncStateRepository,
                    gpodderClient = failingClient,
                ).sync()
            assertTrue(firstPassOutcome is SyncOutcome.Retry)
            assertFalse(ledgerRepository.allRows.single().syncedToServer)

            // "App restart": a fresh orchestrator instance, same repositories, network now available.
            val succeedingClient = FakeGpodderClient()
            val secondPassOutcome =
                orchestratorOf(
                    feedRepository = feedRepository,
                    ledgerRepository = ledgerRepository,
                    syncStateRepository = syncStateRepository,
                    gpodderClient = succeedingClient,
                ).sync()

            assertEquals(SyncOutcome.Success, secondPassOutcome)
            assertTrue(ledgerRepository.allRows.single().syncedToServer)
            // Exactly one push of one row, never duplicated -- two actions because a download emits both.
            assertEquals(2, succeedingClient.postedActions.size)
            assertEquals(1, succeedingClient.postEpisodeActionsCallCount)
        }

    /**
     * CLAUDE.md §7 item 8: successful download + POST + remote echo of our own action. The pass
     * pushes the row, and the pull that follows hands back exactly what we sent -- `DOWNLOAD` and the
     * ended `PLAY` (`decisions/0023`). Because the day of cursor overlap re-delivers our own
     * actions on every pass, this replay has to be free: nothing about the row may change.
     */
    @Test
    fun `a remote echo of our own download is a no-op, not a duplicate download trigger`() =
        runBlocking {
            val row = downloadedRow()
            val ledgerRepository = FakeEpisodeLedgerRepository(initial = listOf(row))
            val page = EpisodeActionPage(row.toOutboundActions(), timestamp = 1L)
            val gpodderClient = FakeGpodderClient(episodeActionsPage = page)

            val outcome = orchestratorOf(ledgerRepository = ledgerRepository, gpodderClient = gpodderClient).sync()

            assertEquals(SyncOutcome.Success, outcome)
            assertEquals(1, gpodderClient.postEpisodeActionsCallCount)
            // Unchanged in every field the echo could have touched: still terminal (never
            // HANDLED_REMOTELY), same timestamp, same written name, and not re-queued for pushing.
            assertEquals(row.copy(syncedToServer = true), ledgerRepository.allRows.single())
        }

    @Test
    fun `a network failure pulling subscriptions yields Retry without touching local state`() =
        runBlocking {
            val feedRepository = FakeFeedRepository(initial = listOf(feed("https://example.com/feed.xml")))
            val gpodderClient = FakeGpodderClient(subscriptionsFailure = IOException("host unreachable"))

            val outcome = orchestratorOf(feedRepository = feedRepository, gpodderClient = gpodderClient).sync()

            assertTrue(outcome is SyncOutcome.Retry)
            assertEquals(1, feedRepository.current.size) // untouched
        }

    @Test
    fun `remote actions are reconciled into the ledger and the new sync timestamp is persisted`() =
        runBlocking {
            val remoteAction =
                EpisodeAction(
                    podcast = "https://example.com/feed.xml",
                    episode = "https://example.com/ep-remote.mp3",
                    guid = "guid-remote",
                    action = EpisodeActionType.DOWNLOAD,
                    timestamp = "2026-07-14T09:00:00",
                )
            val ledgerRepository = FakeEpisodeLedgerRepository()
            val initialSyncState = SyncState(lastEpisodeActionSyncTs = 500L, deviceId = "device-a")
            val syncStateRepository = FakeSyncStateRepository(initialSyncState)
            val page = EpisodeActionPage(listOf(remoteAction), timestamp = 999L)
            val gpodderClient = FakeGpodderClient(episodeActionsPage = page)

            orchestratorOf(
                ledgerRepository = ledgerRepository,
                syncStateRepository = syncStateRepository,
                gpodderClient = gpodderClient,
            ).sync()

            assertEquals(LedgerState.HANDLED_REMOTELY, ledgerRepository.allRows.single().state)
            // What is *persisted* is the server's value, verbatim and un-rewound (CLAUDE.md §11).
            assertEquals(999L, syncStateRepository.current.lastEpisodeActionSyncTs)
            assertEquals("device-a", syncStateRepository.current.deviceId) // device id preserved, not regenerated
            // The cursor is rewound a day before it is sent (issue #60, step 4): the server filters on
            // client-authored timestamps while handing back its own clock, so an action authored
            // before our last pass would otherwise be invisible for ever. 500 - 86 400 floors at 0.
            assertEquals(listOf(0L), gpodderClient.fetchEpisodeActionsSinceValues)
        }
}
