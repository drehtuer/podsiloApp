// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2's events that the screen could emit and no test had ever sent to the view model: *Mark all as
 * played*, *Select all*, leaving selection mode, the two link actions, *Mark as unplayed*, and the
 * effects each one owes the host.
 *
 * Its own class beside `EpisodeListViewModelTest`, sharing its harness, because these are the
 * paths the screen tests prove are *emitted* but nothing proved are *handled*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListEventsTest : EpisodeListTestHarness() {
    // ---- Mark all as played (Downloaded filter only) ----

    /**
     * The confirmation gate, at the view model: requesting names the set and writes nothing. These
     * become `PLAY` actions on a shared log that no undo reaches (decisions/0013).
     */
    @Test
    fun `Mark all names the downloaded set and writes nothing until confirmed`() =
        runTest {
            seed(episode("a"), episode("b"), episode("undecided"))
            ledger.seedRow(ledgerRow("a", LedgerState.DOWNLOADED))
            ledger.seedRow(ledgerRow("b", LedgerState.DOWNLOADED))
            val vm = viewModel(EpisodeFilter.DOWNLOADED)
            runCurrent()

            vm.onEvent(EpisodeListEvent.MarkAllRequested)
            runCurrent()

            assertEquals(
                setOf("a", "b"),
                vm.state.value.pendingMarkAll
                    ?.toSet(),
            )
            assertTrue("requesting must not write", ledger.writes.isEmpty())
        }

    @Test
    fun `confirming Mark all writes one batch of played rows and reports the count`() =
        runTest {
            seed(episode("a"), episode("b"))
            ledger.seedRow(ledgerRow("a", LedgerState.DOWNLOADED, writtenFileName = "a.mp3"))
            ledger.seedRow(ledgerRow("b", LedgerState.DOWNLOADED, writtenFileName = "b.mp3"))
            val vm = viewModel(EpisodeFilter.DOWNLOADED)
            runCurrent()
            vm.onEvent(EpisodeListEvent.MarkAllRequested)
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.MarkAllConfirmed)
                runCurrent()

                assertEquals(EpisodeListEffect.ShowMessage(SnackbarText.BulkApplied(2)), awaitItem())
            }
            assertNull(vm.state.value.pendingMarkAll)
            assertEquals("one transaction, not one per episode", 1, ledger.writes.size)
            val written = ledger.writes.single()
            assertEquals(setOf(LedgerState.SKIPPED), written.map { it.state }.toSet())
            // The duplicate guard's evidence survives the re-decision (decisions/0012 §3a).
            assertEquals(setOf("a.mp3", "b.mp3"), written.map { it.writtenFileName }.toSet())
            assertTrue("marking played never enqueues", scheduler.downloads.isEmpty())
        }

    @Test
    fun `dismissing Mark all writes nothing, and a later confirm has nothing to write`() =
        runTest {
            seed(episode("a"))
            ledger.seedRow(ledgerRow("a", LedgerState.DOWNLOADED))
            val vm = viewModel(EpisodeFilter.DOWNLOADED)
            runCurrent()
            vm.onEvent(EpisodeListEvent.MarkAllRequested)
            runCurrent()

            vm.onEvent(EpisodeListEvent.MarkAllDismissed)
            runCurrent()
            assertNull(vm.state.value.pendingMarkAll)

            // A stray confirm (a double tap racing the dismissal) must not resurrect the set.
            vm.onEvent(EpisodeListEvent.MarkAllConfirmed)
            runCurrent()
            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `Mark all on an empty filter opens no dialog`() =
        runTest {
            seed(episode("undecided"))
            val vm = viewModel(EpisodeFilter.DOWNLOADED)
            runCurrent()

            vm.onEvent(EpisodeListEvent.MarkAllRequested)
            runCurrent()

            assertNull("a dialog reading 'Mark 0 as played?' is a dead end", vm.state.value.pendingMarkAll)
        }

    // ---- Selection mode ----

    @Test
    fun `Select all selects exactly the rows in the current filter`() =
        runTest {
            seed(episode("a"), episode("b"), episode("done"))
            ledger.seedRow(ledgerRow("done", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("a"))
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectAllInFilter)
            runCurrent()

            // "done" is not on screen under To decide, so it must not be swept into the selection.
            assertEquals(Selection(setOf("a", "b"), allInFilter = 2), vm.state.value.selection)
        }

    @Test
    fun `Select all on an empty filter does not enter selection mode`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectAllInFilter)
            runCurrent()

            assertNull(vm.state.value.selection)
        }

    @Test
    fun `leaving selection mode drops the selection and any pending confirmation`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            vm.onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.MARK_AS_PLAYED))
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionCleared)
            runCurrent()

            assertNull(vm.state.value.selection)
            assertNull(vm.state.value.pendingSelectionAction)
            assertTrue(ledger.writes.isEmpty())
        }

    // ---- The two link actions, which write nothing ----

    @Test
    fun `Open in browser hands the episode page to the host and writes nothing`() =
        runTest {
            seed(episode("e1", link = "https://example.org/episodes/1"))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.OPEN_IN_BROWSER))
                runCurrent()

                assertEquals(EpisodeListEffect.OpenUrl("https://example.org/episodes/1"), awaitItem())
                expectNoEvents()
            }
            assertTrue(ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    /** Copying used to emit `OpenUrl`, so *Copy episode link* launched a browser instead. */
    @Test
    fun `Copy link copies rather than opening, and says so`() =
        runTest {
            seed(episode("e1", link = "https://example.org/episodes/1"))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.COPY_LINK))
                runCurrent()

                assertEquals(EpisodeListEffect.CopyLink("https://example.org/episodes/1"), awaitItem())
                assertEquals(EpisodeListEffect.ShowMessage(SnackbarText.LinkCopied), awaitItem())
                expectNoEvents()
            }
            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `a link action on an episode with no page does nothing at all`() =
        runTest {
            seed(episode("e1", link = null))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.OPEN_IN_BROWSER))
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.COPY_LINK))
                runCurrent()

                expectNoEvents()
            }
        }

    // ---- Decisions and what they announce ----

    @Test
    fun `marking unplayed from the list writes UNPLAYED and announces it`() =
        runTest {
            // decisions/0024: a state, never a deletion — the row is the dedup authority.
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.SKIPPED))
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.MARK_AS_UNPLAYED))
                runCurrent()

                assertEquals(EpisodeListEffect.ShowMessage(SnackbarText.MarkedUnplayed(1)), awaitItem())
            }
            assertEquals(
                LedgerState.UNPLAYED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `a download decision announces how many were queued`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD))
                runCurrent()

                assertEquals(EpisodeListEffect.ShowMessage(SnackbarText.Queued(1)), awaitItem())
            }
        }

    /**
     * The undo snackbar already reported a swipe; a second snackbar announcing the same decision
     * five seconds later, with no action on it, would be noise (`UI.adoc` §12.3).
     */
    @Test
    fun `a swipe announces itself once, with Undo, and not again when it commits`() =
        runTest {
            settings.swipeMapping = SwipeMapping(right = SwipeAction.DOWNLOAD)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
                runCurrent()
                assertEquals(EpisodeListEffect.ShowUndo(EpisodeUiAction.DOWNLOAD), awaitItem())

                advanceTimeBy(UNDO_WINDOW_FOR_TEST + 1)
                runCurrent()
                expectNoEvents()
            }
            assertEquals(listOf("e1" to false), scheduler.downloads)
        }

    /** The held download renders as queued at once, so the row does not change twice. */
    @Test
    fun `a swiped download inside its window renders as queued without being stored`() =
        runTest {
            settings.swipeMapping = SwipeMapping(right = SwipeAction.DOWNLOAD)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertEquals(LedgerState.QUEUED, rows(vm.state.value).single().ledgerState)
            assertTrue(ledger.writes.isEmpty())
            assertTrue("nothing is enqueued inside the window", scheduler.downloads.isEmpty())
        }

    @Test
    fun `a decision for an episode that no longer exists writes nothing`() =
        runTest {
            // Pruned between render and tap (an unsubscribe removes the cached episodes, §5).
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("gone", EpisodeUiAction.DOWNLOAD))
            vm.onEvent(EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, setOf("gone")))
            runCurrent()

            assertTrue(ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    // ---- The queue banner and the Download all preview ----

    /**
     * Disk-full is inferred from a row that actually failed for space, not from a free-space probe
     * (`FailureUi.kt`): "space looks tight" is not "a download already failed".
     */
    @Test
    fun `a row that failed for space pauses the queue, counting what is still queued`() =
        runTest {
            seed(episode("full"), episode("waiting"))
            ledger.seedRow(
                ledgerRow(
                    "full",
                    LedgerState.ERROR,
                    lastError = "No space left on device",
                    lastErrorCause = ErrorCause.DISK_FULL,
                    lastErrorRetryable = false,
                ),
            )
            ledger.seedRow(ledgerRow("waiting", LedgerState.QUEUED))
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            assertEquals(
                QueueStatus.Paused(QueueStatus.PauseCause.DISK_FULL, queuedCount = 1),
                vm.state.value.queueStatus,
            )
        }

    @Test
    fun `the paused banner's fix is delegated to the host`() =
        runTest {
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.PausedBannerActionClicked)

                assertEquals(EpisodeListEffect.ResolvePausedQueue, awaitItem())
            }
        }

    @Test
    fun `Download all with nothing undecided opens no dialog`() =
        runTest {
            seed(episode("done"))
            ledger.seedRow(ledgerRow("done", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            assertNull("a dialog reading 'Download 0 episodes?' is a dead end", vm.state.value.pendingBulk)
        }

    @Test
    fun `the preview groups its count by feed and warns only when the estimate does not fit`() =
        runTest {
            // 30 minutes at the assumed bitrate is roughly 29 MB: 1 GB is roomy, an unknown free
            // space is "the provider cannot say", and neither may warn.
            seed(episode("a"), episode("b"))
            spaceProbe.freeBytes = 1_000_000_000
            val roomy = viewModel()
            runCurrent()
            roomy.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            val preview = checkNotNull(roomy.state.value.pendingBulk)
            assertEquals(listOf(FeedBreakdown(FEED_URL, 2)), preview.perFeed)
            assertTrue(checkNotNull(preview.estimatedBytes) > 0)
            assertTrue(!preview.exceedsFreeSpace)
            assertTrue(!preview.copy(freeBytes = null).exceedsFreeSpace)
        }
}
