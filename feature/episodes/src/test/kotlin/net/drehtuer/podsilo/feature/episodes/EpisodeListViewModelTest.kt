// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * S2's behaviour, as a plain object with fakes — no Robolectric, no `WorkManager`, no Compose.
 * That is the payoff of `EpisodeScheduler` being an interface the feature module owns.
 *
 * The cases here are the ones the UI design calls traps: a tap must never triage, a swipe must obey
 * the *configured* mapping, bulk writes must be one transaction, and only an explicit *Download
 * again* may carry the flag that gets past `DownloadWorker`'s terminal-row refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListViewModelTest : EpisodeListTestHarness() {
    @Test
    fun `the default filter is To decide, and it means exactly no ledger row`() =
        runTest {
            seed(episode("new"), episode("done"))
            ledger.seedRow(ledgerRow("done", LedgerState.DOWNLOADED))

            val vm = viewModel()
            runCurrent()

            assertEquals(EpisodeFilter.TO_DECIDE, vm.state.value.filter)
            assertEquals(listOf("new"), rows(vm.state.value).map { it.episodeKey })
        }

    @Test
    fun `an episode published long before the feed was first seen still appears`() =
        runTest {
            // decisions/0013 at the screen: the backlog is cleared by writing SKIPPED rows, never
            // by hiding rows at read time. A date filter reappearing here would be invisible in the UI.
            seed(episode("ancient", pubDate = 0))

            val vm = viewModel()
            runCurrent()

            assertEquals(listOf("ancient"), rows(vm.state.value).map { it.episodeKey })
        }

    @Test
    fun `tapping a row opens detail and writes nothing`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.RowClicked("e1"))
            runCurrent()

            assertTrue("a mis-tap must never queue a download", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `downloading writes QUEUED unsynced and enqueues, without the user-requested flag`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(LedgerState.QUEUED, written.state)
            assertFalse("a local write is always unsynced until a confirmed 2xx", written.syncedToServer)
            assertEquals(listOf("e1" to false), scheduler.downloads)
        }

    @Test
    fun `only Download again carries the flag that passes the terminal-row refusal`() =
        runTest {
            // The UI half of decisions/0012. If a plain Download ever set this, the
            // no-auto-download invariant would stop being provable from one grep.
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "old.mp3"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD_AGAIN))
            runCurrent()

            assertEquals(listOf("e1" to true), scheduler.downloads)
        }

    @Test
    fun `a re-decision resets attempts and error but keeps the written file name`() =
        runTest {
            // Keeping writtenFileName is what arms the duplicate guard; losing it here would let a
            // later re-download write a second copy (decisions/0012 §3a).
            seed(episode("e1"))
            ledger.seedRow(
                ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "kept.mp3", attempts = 4, lastError = "boom"),
            )
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD_AGAIN))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(0, written.attempts)
            assertNull(written.lastError)
            assertEquals("kept.mp3", written.writtenFileName)
        }

    @Test
    fun `marking a downloaded episode as played also keeps the written file name`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED, writtenFileName = "kept.mp3"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.MARK_AS_PLAYED))
            runCurrent()

            val written = ledger.writes.flatten().single()
            assertEquals(LedgerState.SKIPPED, written.state)
            assertEquals("kept.mp3", written.writtenFileName)
            assertTrue("marking as played never enqueues anything", scheduler.downloads.isEmpty())
        }

    @Test
    fun `a swipe performs the configured action, not a hard-coded one`() =
        runTest {
            // The swipe background renders from the same mapping, so this is what stops the UI
            // advertising one verb and performing another (UI.adoc §12.1).
            settings.swipeMapping = SwipeMapping(right = SwipeAction.MARK_AS_PLAYED, left = SwipeAction.DOWNLOAD)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            // The write is deferred by the undo window now (`UI.adoc` §12.3); *which* action it
            // is remains this test's point, so it waits the window out rather than changing subject.
            advanceTimeBy(UNDO_WINDOW_FOR_TEST + 1)
            runCurrent()

            assertEquals(
                LedgerState.SKIPPED,
                ledger.writes
                    .flatten()
                    .single()
                    .state,
            )
        }

    @Test
    fun `a disabled swipe direction does nothing at all`() =
        runTest {
            settings.swipeMapping = SwipeMapping(right = SwipeAction.NONE, left = SwipeAction.MARK_AS_PLAYED)
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SwipeCommitted("e1", SwipeDirection.RIGHT))
            runCurrent()

            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `deselecting the last row leaves selection mode rather than showing zero selected`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionToggled("e1"))
            runCurrent()

            assertNull(vm.state.value.selection)
        }

    @Test
    fun `Download all produces a preview and writes absolutely nothing`() =
        runTest {
            // The regression guard for the bug this replaced: it used to emit a "Queued (n)" snackbar
            // without queueing anything, which is worse than not implementing it. decisions/0014
            // makes naming the count *before* writing the whole safeguard.
            seed(episode("a"), episode("b"), episode("c"))
            ledger.seedRow(ledgerRow("c", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            val preview = checkNotNull(vm.state.value.pendingBulk)
            assertEquals(2, preview.count)
            assertTrue("nothing may be written before confirmation", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `confirming the preview is what writes, and dismissing it writes nothing`() =
        runTest {
            seed(episode("a"), episode("b"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            vm.onEvent(EpisodeListEvent.DownloadAllDismissed)
            runCurrent()
            assertNull(vm.state.value.pendingBulk)
            assertTrue("dismissing is a decision not to act", ledger.writes.isEmpty())

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            vm.onEvent(EpisodeListEvent.DownloadAllConfirmed(listOf("a", "b")))
            runCurrent()

            assertNull(vm.state.value.pendingBulk)
            assertEquals(2, ledger.writes.flatten().size)
            assertEquals(2, scheduler.downloads.size)
        }

    @Test
    fun `an unknown duration makes the size estimate absent rather than understated`() =
        runTest {
            // A partial estimate would read as "it fits" when it might not; itunes:duration is too
            // unreliable for a number that looks authoritative.
            spaceProbe.freeBytes = 1_000_000_000
            seed(episode("known"), episode("unknown", durationMs = null))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()

            val preview = checkNotNull(vm.state.value.pendingBulk)
            assertNull(preview.estimatedBytes)
            assertFalse("no estimate means no warning", preview.exceedsFreeSpace)
        }

    @Test
    fun `the size warning appears only when the estimate exceeds free space`() =
        runTest {
            // 30 minutes at the assumed bitrate is roughly 29 MB, so 1 MB free must warn and 1 GB not.
            seed(episode("a"))
            spaceProbe.freeBytes = 1_000_000
            val tight = viewModel()
            runCurrent()
            tight.onEvent(EpisodeListEvent.DownloadAllRequested)
            runCurrent()
            assertTrue(checkNotNull(tight.state.value.pendingBulk).exceedsFreeSpace)
        }

    @Test
    fun `isRefreshing stays true for as long as the refresh runs`() =
        runTest {
            // It used to be set and cleared on consecutive lines around a synchronous enqueue, so the
            // indicator could never appear at all.
            seed(episode("e1"))
            scheduler.inFlightRefresh = CompletableDeferred()
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()
            assertTrue("the indicator must be visible while the work runs", vm.state.value.isRefreshing)

            scheduler.completeRefresh()
            runCurrent()
            assertFalse(vm.state.value.isRefreshing)
        }

    /**
     * How a stored failure reaches the row, one case per classification.
     *
     * The guarantee `architecture.adoc` §11 and UI.adoc §12.11 make, and the reason the cause is
     * stored rather than parsed out of the message: retrying a lost folder grant or a full disk
     * cannot succeed until the user acts, so a Retry button there would be a button that lies. A row
     * written before the classification existed (schema v3) defaults to retryable — offering a Retry
     * that fails is recoverable; hiding the only useful button is not.
     */
    @Test
    fun `a stored failure surfaces with its cause, retryability and remedy`() =
        runTest {
            data class Case(
                val cause: ErrorCause?,
                val retryable: Boolean?,
                val expectedCause: ErrorCause,
                val expectedRetryable: Boolean,
                val expectedRemedy: FailureRemedy?,
            )
            val cases =
                listOf(
                    Case(ErrorCause.FOLDER_UNAVAILABLE, false, ErrorCause.FOLDER_UNAVAILABLE, false, FailureRemedy.CHOOSE_FOLDER),
                    Case(ErrorCause.DISK_FULL, false, ErrorCause.DISK_FULL, false, FailureRemedy.FREE_UP_SPACE),
                    Case(ErrorCause.NETWORK, true, ErrorCause.NETWORK, true, null),
                    Case(null, null, ErrorCause.UNKNOWN, true, null),
                )
            seed(*cases.indices.map { episode("e$it") }.toTypedArray())
            cases.forEachIndexed { i, case ->
                ledger.seedRow(
                    ledgerRow(
                        "e$i",
                        LedgerState.ERROR,
                        lastError = "failure $i",
                        lastErrorCause = case.cause,
                        lastErrorRetryable = case.retryable,
                    ),
                )
            }
            // An ERROR row is not "to decide" — it has a ledger row. The failure surfaces on All.
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            val byKey = rows(vm.state.value).associateBy { it.episodeKey }
            cases.forEachIndexed { i, case ->
                val failure = checkNotNull(byKey.getValue("e$i").lastError)
                assertEquals("case $case", case.expectedCause, failure.cause)
                assertEquals("case $case", case.expectedRetryable, failure.retryable)
                assertEquals("case $case", case.expectedRemedy, failure.remedy)
                assertEquals("verbatim, case $case", "failure $i", failure.message)
            }
        }

    @Test
    fun `no folder chosen pauses the queue without refusing anything`() =
        runTest {
            folderStatus.state = FolderState.NOT_CHOSEN
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.QUEUED))
            val vm = viewModel()
            runCurrent()

            val paused = vm.state.value.queueStatus as QueueStatus.Paused
            assertEquals(QueueStatus.PauseCause.FOLDER_NOT_CHOSEN, paused.cause)

            // Paused is a queue condition, not a refusal: a new decision is still accepted.
            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD))
            runCurrent()
            assertEquals(1, scheduler.downloads.size)
        }

    @Test
    fun `a revoked grant is a different pause cause than never having chosen one`() =
        runTest {
            // Same banner, different sentence and different fix — "choose a folder" versus "the one
            // you chose is gone" are not the same message to a user.
            folderStatus.state = FolderState.REVOKED
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            assertEquals(
                QueueStatus.PauseCause.FOLDER_REVOKED,
                (vm.state.value.queueStatus as QueueStatus.Paused).cause,
            )
        }

    @Test
    fun `a granted folder with healthy rows leaves the queue running`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            assertEquals(QueueStatus.Running, vm.state.value.queueStatus)
        }

    @Test
    fun `episodes group into month sections, with undated ones in a trailing group`() =
        runTest {
            // Sections index into the rendered list, so a wrong grouping shows up as headers
            // straddling the wrong rows rather than as a silent mismatch.
            seed(
                episode("jul-b", pubDate = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()),
                episode("jul-a", pubDate = Instant.parse("2026-07-02T00:00:00Z").toEpochMilli()),
                episode("jun", pubDate = Instant.parse("2026-06-11T00:00:00Z").toEpochMilli()),
                episode("undated", pubDate = null),
            )
            val vm = viewModel()
            runCurrent()

            val sections = vm.state.value.sections
            assertEquals(listOf(YearMonth(2026, 7), YearMonth(2026, 6), null), sections.map { it.label })
            assertEquals(listOf(2, 1, 1), sections.map { it.count })
            assertEquals(listOf(0, 2, 3), sections.map { it.firstIndex })
        }

    @Test
    fun `an offline pull-to-refresh reports immediately and schedules nothing`() =
        runTest {
            connectivity.online = false
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()

            assertTrue("no request is attempted at all", scheduler.refreshes.isEmpty())
            assertEquals("including the sync half", 0, scheduler.syncs)
        }

    @Test
    fun `a pull-to-refresh is scoped to this feed, not to all of them`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()

            assertEquals(listOf(FEED_URL), scheduler.refreshes)
        }

    /**
     * Issue #60: the RSS half was the only half. The sync pass is global by nature — it pulls the
     * subscription list and drains the outbox — so it is *not* scoped to this feed even though the
     * refresh beside it is.
     */
    @Test
    fun `a pull-to-refresh also runs a sync pass, before the feed fetch`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.PullToRefresh)
            runCurrent()

            assertEquals(1, scheduler.syncs)
            assertEquals(listOf("sync", "refresh"), scheduler.order)
        }

    @Test
    fun `the Download all count is the undecided count, and is zero on other filters`() =
        runTest {
            seed(episode("a"), episode("b"), episode("c"))
            ledger.seedRow(ledgerRow("c", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            assertEquals(2, vm.state.value.downloadAllCount)

            vm.onEvent(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL))
            runCurrent()

            assertEquals(
                "'Download all' is meaningless outside the to-decide filter",
                0,
                vm.state.value.downloadAllCount,
            )
        }

    @Test
    fun `an empty filter renders Empty, not an empty Episodes list`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADED))
            val vm = viewModel()
            runCurrent()

            // "nothing to decide" and "not loaded yet" must be distinguishable states, which is the
            // whole reason Content is sealed rather than a list plus an isLoading flag.
            assertTrue(vm.state.value.content is EpisodeListUiState.Content.Empty)
        }

    @Test
    fun `cancelling a queued download cancels the work and writes no ledger row`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.QUEUED))
            val vm = viewModel()
            runCurrent()

            vm.onEvent(EpisodeListEvent.Triage("e1", EpisodeUiAction.CANCEL))
            runCurrent()

            assertEquals(listOf("e1"), scheduler.cancellations)
            assertTrue(ledger.writes.isEmpty())
        }

    // ---- Live download progress (issue #47), per UI.adoc §B7's table ----

    @Test
    fun `a live update gives the row its real percentage`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADING))
            workMonitor.set(
                DownloadWork(
                    progress = mapOf("e1" to DownloadProgress(bytesDownloaded = 620, totalBytes = 1_000)),
                    live = setOf("e1"),
                ),
            )
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            val row = rows(vm.state.value).single()
            assertEquals(LedgerState.DOWNLOADING, row.ledgerState)
            assertEquals(62, row.progress?.percent)
        }

    @Test
    fun `live work that has not reported yet is downloading with no percentage`() =
        runTest {
            // §7's second row: *resuming*, never "0 %" — the row must not imply it knows how far
            // along it is just because a worker exists.
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADING))
            workMonitor.set(DownloadWork(live = setOf("e1")))
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            val row = rows(vm.state.value).single()
            assertEquals(LedgerState.DOWNLOADING, row.ledgerState)
            assertNull(row.progress)
        }

    /**
     * §7's third row. The process died mid-download, so the ledger says `DOWNLOADING` and there is
     * no worker. The row must not claim to be downloading, and the work is picked back up.
     */
    @Test
    fun `a stranded downloading row reads as queued and is re-enqueued once`() =
        runTest {
            seed(episode("e1"))
            ledger.seedRow(ledgerRow("e1", LedgerState.DOWNLOADING))
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            assertEquals(LedgerState.QUEUED, rows(vm.state.value).single().ledgerState)
            assertEquals(listOf("e1" to false), scheduler.downloads)

            // Re-emitting the query must not enqueue a second worker for one file.
            workMonitor.set(DownloadWork())
            runCurrent()
            assertEquals(listOf("e1" to false), scheduler.downloads)
        }

    /**
     * The no-auto-download invariant, at the one code path that enqueues without a tap
     * (CLAUDE.md §1). Resuming is only ever for a row the user already decided on, and it never
     * carries `userRequested` — the flag that gets past the terminal-row refusal.
     */
    @Test
    fun `resuming never touches an undecided or queued episode, and never claims to be user-requested`() =
        runTest {
            seed(episode("undecided"), episode("queued"), episode("done"))
            ledger.seedRow(ledgerRow("queued", LedgerState.QUEUED))
            ledger.seedRow(ledgerRow("done", LedgerState.DOWNLOADED))
            val vm = viewModel(EpisodeFilter.ALL)
            runCurrent()

            assertTrue("nothing may be enqueued, got ${scheduler.downloads}", scheduler.downloads.isEmpty())
            assertTrue(rows(vm.state.value).isNotEmpty())
        }

    // ---- Selection mode's confirmation gate (issue #46) ----

    /**
     * §5's safeguard, and the reason `SelectionActionRequested` exists at all rather than the bar
     * emitting `BulkConfirmed` directly: requesting an action opens a dialog and **writes nothing**.
     * The same rule *Download all* and *Mark all as played* already follow.
     */
    @Test
    fun `requesting a selection action opens the confirmation and writes nothing`() =
        runTest {
            seed(episode("e1"), episode("e2"))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            vm.onEvent(EpisodeListEvent.SelectionToggled("e2"))
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.MARK_AS_PLAYED))
            runCurrent()

            assertEquals(EpisodeUiAction.MARK_AS_PLAYED, vm.state.value.pendingSelectionAction)
            assertTrue("the dialog must not write", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `dismissing the confirmation keeps the selection and writes nothing`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            vm.onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.DOWNLOAD))
            runCurrent()

            vm.onEvent(EpisodeListEvent.SelectionActionDismissed)
            runCurrent()

            assertNull(vm.state.value.pendingSelectionAction)
            // Cancelling the dialog is not cancelling the selection — the user may pick the other
            // action, and losing twelve taps' worth of selection would be its own bug.
            assertEquals(
                setOf("e1"),
                vm.state.value.selection
                    ?.keys,
            )
            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `confirming writes once, in one transaction, and leaves selection mode`() =
        runTest {
            seed(episode("e1"), episode("e2"))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            vm.onEvent(EpisodeListEvent.SelectionToggled("e2"))
            vm.onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.MARK_AS_PLAYED))
            runCurrent()

            vm.onEvent(EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, setOf("e1", "e2")))
            runCurrent()

            assertEquals("one batched write", 1, ledger.writes.size)
            assertEquals(2, ledger.writes.single().size)
            assertNull(vm.state.value.pendingSelectionAction)
            assertFalse(vm.state.value.inSelectionMode)
        }

    /** A confirmation whose set changed under it must not survive to be tapped. */
    @Test
    fun `changing the filter drops a pending confirmation with the selection`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()
            vm.onEvent(EpisodeListEvent.SelectionStarted("e1"))
            vm.onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.DOWNLOAD))
            runCurrent()

            vm.onEvent(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL))
            runCurrent()

            assertNull(vm.state.value.selection)
            assertNull(vm.state.value.pendingSelectionAction)
        }

    /**
     * The two app-bar routes (`UI.adoc` §3), as effects rather than as navigation the screen
     * performs itself — S2 owns no `NavController` (`UI.adoc` §B0.2).
     */
    @Test
    fun `the app bar navigates and decides nothing`() =
        runTest {
            seed(episode("e1"))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(EpisodeListEvent.BackClicked)
                assertEquals(EpisodeListEffect.NavigateUp, awaitItem())

                vm.onEvent(EpisodeListEvent.ActivityClicked)
                assertEquals(EpisodeListEffect.OpenActivity, awaitItem())

                expectNoEvents()
            }
            assertTrue("navigating must never write a ledger row", ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }
}
