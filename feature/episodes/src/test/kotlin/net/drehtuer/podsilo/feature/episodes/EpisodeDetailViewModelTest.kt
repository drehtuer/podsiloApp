// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.LedgerState
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
 * S3's view model. The sheet is a read step that can also decide, so the tests that matter are
 * about the *decision* half behaving identically to S2's — a divergence there would write two
 * different ledger rows for one user action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeDetailViewModelTest {
    private val episodes = FakeEpisodeRepository()
    private val ledger = FakeLedgerRepository()
    private val feeds = FakeFeedRepository()
    private val scheduler = RecordingScheduler()
    private val workMonitor = FakeDownloadWorkMonitor()
    private val clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
    private val syncTrigger = RecordingSyncTrigger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Reuses the shared builder; only the description matters here, and only that it is raw. */
    private fun sampleEpisode(link: String? = null) =
        episode(
            key = "e1",
            title = "Warum Hamburg immer regnet",
            description = "<p>Eine Folge über <b>Regen</b></p>",
            link = link,
        )

    private fun viewModel(
        episodeKey: String = "e1",
        folder: String? = "Podcasts",
    ) = EpisodeDetailViewModel(
        episodeKey = episodeKey,
        episodeRepository = episodes,
        ledgerRepository = ledger,
        feedRepository = feeds,
        triageWriter = TriageWriter(ledger, clock, syncTrigger),
        scheduler = scheduler,
        folderLabel = { folder },
        workMonitor = workMonitor,
    )

    @Test
    fun `the sheet opens for an episode with no ledger row and offers both decisions`() =
        runTest {
            episodes.seed(sampleEpisode())
            feeds.seed(feed(title = "Der Podcast"))

            viewModel().state.test {
                val state = awaitItem() ?: awaitItem()!!
                assertEquals("Der Podcast", state.episode.feedTitle)
                assertNull(state.episode.ledgerState)
                assertEquals(
                    setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_PLAYED),
                    state.episode.actions,
                )
                // Raw, not sanitised: sanitising is the Composable's job (architecture §4).
                assertTrue(state.descriptionHtml.contains("<b>"))
            }
        }

    @Test
    fun `the sheet opens for a skipped episode too, and offers download anyway`() =
        runTest {
            // "Reachable for every episode regardless of state, including greyed-out ones"
            // (UI.adoc §6) — an explicit requirement, so it gets an explicit test.
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.SKIPPED))

            viewModel().state.test {
                val state = awaitItem() ?: awaitItem()!!
                assertEquals(LedgerState.SKIPPED, state.episode.ledgerState)
                // Since `decisions/0024` a decided episode also offers the way back out of the
                // decision — the sheet is where a user who disagrees with a past self goes.
                assertEquals(
                    setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_UNPLAYED),
                    state.episode.actions,
                )
            }
        }

    @Test
    fun `a downloaded episode reports where the file went, folder and name`() =
        runTest {
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADED, writtenFileName = "20260714_Warum.mp3"))

            viewModel().state.test {
                val state = awaitItem() ?: awaitItem()!!
                assertEquals("Podcasts/20260714_Warum.mp3", state.deliveredTo)
            }
        }

    @Test
    fun `with no folder label it still names the file rather than saying nothing`() =
        runTest {
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADED, writtenFileName = "20260714_Warum.mp3"))

            viewModel(folder = null).state.test {
                val state = awaitItem() ?: awaitItem()!!
                assertEquals("20260714_Warum.mp3", state.deliveredTo)
            }
        }

    @Test
    fun `deliveredTo is never claimed for a state that never wrote a file`() {
        // The file may already have been deleted by the player; what this line reports is what *we*
        // wrote, and only when we wrote it (CLAUDE.md §11).
        assertNull(deliveredTo(LedgerState.SKIPPED, "x.mp3", "Podcasts"))
        assertNull(deliveredTo(LedgerState.DOWNLOADED, null, "Podcasts"))
        assertNull(deliveredTo(null, "x.mp3", "Podcasts"))
    }

    @Test
    fun `the sheet stays live while the download it started runs`() =
        runTest {
            episodes.seed(sampleEpisode())
            // The work has to actually be live, or §7's third case applies and the row correctly
            // reads *queued* instead — see the test below.
            workMonitor.set(DownloadWork(live = setOf("e1")))
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                skipItems(0)
                ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADING))

                val downloading = awaitUntil { it?.episode?.ledgerState == LedgerState.DOWNLOADING }
                // Not "0 %": progress after a state change is only ever a live update (§7).
                assertNull(downloading.episode.progress)
                assertEquals(setOf(EpisodeUiAction.CANCEL), downloading.episode.actions)
            }
        }

    /**
     * `UI.adoc` §B7's first row, which nothing in the app could satisfy before issue
     * #47: `DownloadWorker` never published progress and no screen observed any, so a running
     * download drew the indeterminate bar from start to finish.
     */
    @Test
    fun `a live update draws the real percentage`() =
        runTest {
            episodes.seed(sampleEpisode())
            workMonitor.set(
                DownloadWork(
                    progress = mapOf("e1" to DownloadProgress(bytesDownloaded = 620, totalBytes = 1_000)),
                    live = setOf("e1"),
                ),
            )
            ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADING))
            val viewModel = viewModel()

            viewModel.state.test {
                val downloading = awaitUntil { it?.episode?.progress != null }
                assertEquals(62, downloading.episode.progress?.percent)
            }
        }

    /**
     * §7's third case: a `DOWNLOADING` ledger row with no work behind it was killed mid-download.
     * Rendering it as *downloading* would claim something is happening that is not.
     */
    @Test
    fun `a downloading row with no work behind it reads as queued`() =
        runTest {
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADING))
            val viewModel = viewModel()

            viewModel.state.test {
                val stranded = awaitUntil { it?.episode?.ledgerState != null }
                assertEquals(LedgerState.QUEUED, stranded.episode.ledgerState)
                assertNull(stranded.episode.progress)
            }
        }

    @Test
    fun `downloading writes a queued row and enqueues, without userRequested`() =
        runTest {
            episodes.seed(sampleEpisode())
            val viewModel = viewModel()

            viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.DOWNLOAD))

            assertEquals(
                LedgerState.QUEUED,
                ledger.writes
                    .single()
                    .single()
                    .state,
            )
            assertEquals(listOf("e1" to false), scheduler.downloads)
        }

    @Test
    fun `download again is the only action that carries userRequested`() =
        runTest {
            // The flag is the sole way past DownloadWorker's terminal-row refusal
            // (decisions/0012); setting it for an ordinary download would erase the guarantee.
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.DOWNLOADED, writtenFileName = "a.mp3"))

            viewModel().onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.DOWNLOAD_AGAIN))

            assertEquals(listOf("e1" to true), scheduler.downloads)
        }

    @Test
    fun `marking as played writes SKIPPED unsynced and closes the sheet`() =
        runTest {
            episodes.seed(sampleEpisode())
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.MARK_AS_PLAYED))

                val written = ledger.writes.single().single()
                assertEquals(LedgerState.SKIPPED, written.state)
                // The durable row exists before anything is posted; only a 2xx flips this
                // (CLAUDE.md §5).
                assertEquals(false, written.syncedToServer)

                assertEquals(EpisodeDetailEffect.ShowMessage(SnackbarText.BulkApplied(1)), awaitItem())
                // Deciding closes the sheet (UI.adoc §6).
                assertEquals(EpisodeDetailEffect.Close, awaitItem())
            }
        }

    @Test
    fun `opening the episode page does not close the sheet`() =
        runTest {
            // Leaving to read show notes is not a triage decision, and coming back must not cost the
            // user their place (UI.adoc §6).
            episodes.seed(sampleEpisode(link = "https://example.org/episodes/1"))
            val viewModel = viewModel()
            viewModel.state.test { awaitItem() ?: awaitItem() }

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.OpenInBrowserClicked)

                assertEquals(
                    EpisodeDetailEffect.OpenUrl("https://example.org/episodes/1"),
                    awaitItem(),
                )
                expectNoEvents()
            }
        }

    @Test
    fun `a link inside the description opens without deciding anything`() =
        runTest {
            episodes.seed(sampleEpisode())
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.LinkClicked("https://example.org/notes"))

                assertEquals(EpisodeDetailEffect.OpenUrl("https://example.org/notes"), awaitItem())
                expectNoEvents()
            }
            assertTrue("a link tap must never write a ledger row", ledger.writes.isEmpty())
        }

    @Test
    fun `an episode pruned while its sheet is open resolves to null rather than a hollow sheet`() =
        runTest {
            // Unsubscribing a feed deletes its cached episodes (CLAUDE.md §5); the host closes on
            // null instead of rendering a sheet with no episode in it.
            viewModel(episodeKey = "gone").state.test {
                assertNull(awaitItem())
            }
        }

    /** decisions/0024 at S3: a state rather than a deletion, announced, and the sheet closes. */
    @Test
    fun `marking unplayed writes UNPLAYED, says so and closes the sheet`() =
        runTest {
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.SKIPPED))
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.MARK_AS_UNPLAYED))

                assertEquals(EpisodeDetailEffect.ShowMessage(SnackbarText.MarkedUnplayed(1)), awaitItem())
                assertEquals(EpisodeDetailEffect.Close, awaitItem())
            }
            assertEquals(
                LedgerState.UNPLAYED,
                ledger.writes
                    .single()
                    .single()
                    .state,
            )
            assertTrue(scheduler.downloads.isEmpty())
        }

    @Test
    fun `cancelling cancels the work, writes nothing, and closes the sheet`() =
        runTest {
            episodes.seed(sampleEpisode())
            ledger.seedRow(ledgerRow("e1", state = LedgerState.QUEUED))
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.CANCEL))

                assertEquals(EpisodeDetailEffect.Close, awaitItem())
            }
            assertEquals(listOf("e1"), scheduler.cancellations)
            assertTrue(ledger.writes.isEmpty())
        }

    /**
     * The two link actions are not decisions, so neither closes the sheet — and copying is not
     * opening: both used to emit `OpenUrl`, so *Copy episode link* launched a browser.
     */
    @Test
    fun `the link actions leave the sheet open, and copy copies rather than opening`() =
        runTest {
            episodes.seed(sampleEpisode(link = "https://example.org/episodes/1"))
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.COPY_LINK))
                assertEquals(EpisodeDetailEffect.CopyLink("https://example.org/episodes/1"), awaitItem())
                assertEquals(EpisodeDetailEffect.ShowMessage(SnackbarText.LinkCopied), awaitItem())

                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.OPEN_IN_BROWSER))
                assertEquals(EpisodeDetailEffect.OpenUrl("https://example.org/episodes/1"), awaitItem())

                expectNoEvents()
            }
            assertTrue(ledger.writes.isEmpty())
        }

    @Test
    fun `back and Error details are navigation the host performs`() =
        runTest {
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Dismissed)
                assertEquals(EpisodeDetailEffect.Close, awaitItem())

                viewModel.onEvent(EpisodeDetailEvent.ErrorDetailsClicked)
                assertEquals(EpisodeDetailEffect.OpenErrorLog, awaitItem())
            }
        }

    @Test
    fun `a decision on an episode pruned under the sheet writes nothing and emits nothing`() =
        runTest {
            val viewModel = viewModel(episodeKey = "gone")

            viewModel.effect.test {
                viewModel.onEvent(EpisodeDetailEvent.Triage(EpisodeUiAction.DOWNLOAD))

                expectNoEvents()
            }
            assertTrue(ledger.writes.isEmpty())
            assertTrue(scheduler.downloads.isEmpty())
        }
}

/** Consumes emissions until [predicate] holds, so a test does not depend on the emission count. */
private suspend fun app.cash.turbine.ReceiveTurbine<EpisodeDetailUiState?>.awaitUntil(
    predicate: (EpisodeDetailUiState?) -> Boolean,
): EpisodeDetailUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item!!
    }
}
