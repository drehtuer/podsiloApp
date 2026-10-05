// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S4's two directional sync rows (`decisions/0025`).
 *
 * The property worth pinning is not that the buttons work — it is that **nothing happens until the
 * confirmation is accepted**, and that the push's count is honest. The push writes to a shared,
 * append-only log that other clients act on and that the API cannot retract; a button that fired on
 * the first tap would be the one operation in this app able to do that without being asked twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectionalSyncViewModelTest {
    private val settings =
        FakeSettingsRepository().apply {
            storedCredentials = NextcloudCredentials("https://cloud.example.org", "author", "app-password")
        }
    private val ledger = FakeLedgerRepository()
    private val directionalSync = RecordingDirectionalSync()
    private val clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        SettingsViewModel(
            settingsRepository = settings,
            ledgerRepository = ledger,
            listRepository = FakeListRepository(),
            feedRepository = FakeFeedRepository(),
            folderStatus = { MutableStateFlow(FolderUi()) },
            counts = FakeCounts(),
            namingSummary = { "" },
            syncStatus = { MutableStateFlow(null) },
            archive = FakeDatabaseArchive(),
            syncTrigger = RecordingSyncTrigger(),
            directionalSync = directionalSync,
            clock = clock,
            version = "0.1.0",
            build = "42",
            zone = ZoneOffset.UTC,
        )

    private fun ledgerRow(
        key: String,
        state: LedgerState,
    ) = EpisodeLedgerRow(
        episodeKey = key,
        feedUrl = "https://example.org/feed.xml",
        enclosureUrl = "https://example.org/$key.mp3",
        state = state,
        actionedAt = 0L,
        syncedToServer = true,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        durationSeconds = 1_800,
    )

    @Test
    fun `requesting a pass opens a confirmation, and cancelling it starts nothing`() =
        runTest {
            listOf(SyncDirection.PULL, SyncDirection.PUSH).forEach { direction ->
                val viewModel = viewModel()
                viewModel.state.test {
                    viewModel.onEvent(SettingsEvent.DirectionalSyncRequested(direction))
                    val pending = awaitUntil { it.pendingDirectionalSync != null }.pendingDirectionalSync
                    assertEquals(direction, pending?.direction)
                    assertTrue(
                        "$direction: the confirmation is the safeguard, so nothing may run yet",
                        directionalSync.requests.isEmpty(),
                    )

                    viewModel.onEvent(SettingsEvent.DirectionalSyncCancelled)
                    awaitUntil { it.pendingDirectionalSync == null }
                }

                assertTrue("$direction: cancelling must not run it either", directionalSync.requests.isEmpty())
            }
        }

    @Test
    fun `confirming runs the direction that was asked for`() =
        runTest {
            listOf(SyncDirection.PULL, SyncDirection.PUSH).forEach { direction ->
                directionalSync.requests.clear()
                val viewModel = viewModel()

                viewModel.state.test {
                    viewModel.onEvent(SettingsEvent.DirectionalSyncRequested(direction))
                    awaitUntil { it.pendingDirectionalSync != null }
                    viewModel.onEvent(SettingsEvent.DirectionalSyncConfirmed)
                    awaitUntil { it.pendingDirectionalSync == null }
                }

                assertEquals(listOf(direction), directionalSync.requests)
            }
        }

    /**
     * The count is what makes the push's confirmation mean anything, and it counts *what would
     * actually be sent* — the three states that map to an action — rather than every ledger row.
     */
    @Test
    fun `the push names how many decisions it would send`() =
        runTest {
            ledger.upsert(ledgerRow("a", LedgerState.SKIPPED))
            ledger.upsert(ledgerRow("b", LedgerState.DOWNLOADED))
            ledger.upsert(ledgerRow("c", LedgerState.UNPLAYED))
            // None of these can be represented as an episode action, so none of them count.
            ledger.upsert(ledgerRow("d", LedgerState.QUEUED))
            ledger.upsert(ledgerRow("e", LedgerState.ERROR))
            ledger.upsert(ledgerRow("f", LedgerState.HANDLED_REMOTELY))
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(SettingsEvent.DirectionalSyncRequested(SyncDirection.PUSH))
                val state = awaitUntil { it.pendingDirectionalSync != null }

                assertEquals(3, state.pendingDirectionalSync?.pushableCount)
            }
        }

    /**
     * **Refused without an account**, the same rule *Restore from backup* follows. The rows are not
     * even rendered in that case, but the guard lives in the view model too, so it holds however the
     * event arrives.
     */
    @Test
    fun `neither direction is offered without a connected account`() =
        runTest {
            settings.storedCredentials = null
            val viewModel = viewModel()

            viewModel.onEvent(SettingsEvent.DirectionalSyncRequested(SyncDirection.PUSH))
            runCurrent()

            assertNull(viewModel.state.value.pendingDirectionalSync)
            assertTrue(directionalSync.requests.isEmpty())
        }
}
