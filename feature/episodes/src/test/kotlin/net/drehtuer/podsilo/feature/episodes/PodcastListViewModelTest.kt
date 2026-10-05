// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.NextcloudAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S1's view model. Two rules carry real weight here and get most of the tests: the ordering is
 * **frozen** between explicit refreshes, and a count of `null` ("never fetched") is not a count of
 * zero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastListViewModelTest {
    private val feeds = FakeFeedRepository()

    /**
     * One store behind both fakes. S1 reads the episode cache twice — for the counts and for the
     * ordering — and two fakes with two lists could disagree about which episodes exist, which is
     * precisely the drift the real DAO cannot have.
     */
    private val store = mutableListOf<Episode>()
    private val episodes = FakeEpisodeRepository(store)
    private val ledger = FakeLedgerRepository(store)
    private val settings = FakeSettingsRepository()
    private val connectivity = FakeConnectivityMonitor()
    private val scheduler = RecordingScheduler()
    private val folder = FakeFolderStatus()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings.account.value = NextcloudAccount(serverUrl = "https://cloud.example.org", username = "author")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        PodcastListViewModel(
            feedRepository = feeds,
            episodeRepository = episodes,
            listRepository = ledger,
            settingsRepository = settings,
            connectivityMonitor = connectivity,
            scheduler = scheduler,
            folderStatus = folder,
            namingPreview = { "Der Podcast/20260714_Warum.mp3" },
            // Issue #91: the projection is dispatched off the main thread in production; a test has
            // to name a dispatcher it controls, or its emissions race the test scheduler.
            projectionContext = UnconfinedTestDispatcher(),
        )

    @Test
    fun `feeds are ordered by newest episode, never-fetched last, then title`() =
        runTest {
            feeds.seed(
                feed(url = "b", title = "Bravo"),
                feed(url = "a", title = "Alpha", lastRefreshedAt = 1),
                feed(url = "c", title = "Charlie", lastRefreshedAt = 1),
            )
            episodes.seed(
                episode(key = "c1", feedUrl = "c", pubDate = 3_000),
                episode(key = "a1", feedUrl = "a", pubDate = 9_000),
            )

            viewModel().state.test {
                val rows = awaitUntilFeeds()
                // "b" has no episodes at all, so it sorts last rather than as ancient.
                assertEquals(listOf("a", "c", "b"), rows.map { it.url })
            }
        }

    @Test
    fun `a background sync never reorders the list under the user`() =
        runTest {
            // The whole point of the frozen order (UI.adoc §4): rows update in place.
            feeds.seed(
                feed(url = "a", title = "Alpha", lastRefreshedAt = 1),
                feed(url = "b", title = "Bravo", lastRefreshedAt = 1),
            )
            episodes.seed(
                episode(key = "a1", feedUrl = "a", pubDate = 9_000),
                episode(key = "b1", feedUrl = "b", pubDate = 1_000),
            )
            val viewModel = viewModel()

            viewModel.state.test {
                // Under "All podcasts", so this test is about order alone and not about which rows
                // the default filter hides.
                viewModel.onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL))
                assertEquals(listOf("a", "b"), awaitUntilFeeds(PodcastFilter.ALL).map { it.url })

                // "b" now has by far the newest episode — under a live sort it would jump to the top.
                episodes.seed(episode(key = "b2", feedUrl = "b", pubDate = 99_000))
                ledger.seedRow(ledgerRow("a1", state = LedgerState.SKIPPED))

                assertEquals(listOf("a", "b"), awaitUntilFeeds(PodcastFilter.ALL).map { it.url })
            }
        }

    @Test
    fun `a feed subscribed since the last freeze is appended, not sorted in`() {
        // It has to appear — we follow the server — but inserting it mid-list would shift every row
        // below it, which is the movement the freeze exists to prevent.
        val rows =
            listOf(
                FeedUi(url = "a", title = "Alpha"),
                FeedUi(url = "new", title = "New"),
                FeedUi(url = "b", title = "Bravo"),
            )

        assertEquals(
            listOf("a", "b", "new"),
            rows.inFrozenOrder(listOf("a", "b")).map { it.url },
        )
    }

    @Test
    fun `a never-fetched feed shows no count rather than zero`() =
        runTest {
            // "Never fetched is not zero" (UI.adoc §12.5) — the difference between "nothing new"
            // and "we have not looked yet".
            feeds.seed(feed(url = "a", title = "Alpha"))

            viewModel().state.test {
                assertNull(awaitUntilFeeds().single().undecidedCount)
            }
        }

    @Test
    fun `a fetched feed with everything decided shows zero, not a dash`() =
        runTest {
            feeds.seed(feed(url = "a", title = "Alpha", lastRefreshedAt = 1))
            episodes.seed(episode(key = "a1", feedUrl = "a"))
            ledger.seedRow(ledgerRow("a1", state = LedgerState.DOWNLOADED))

            val viewModel = viewModel()

            viewModel.state.test {
                // Under "All podcasts": the default filter hides a caught-up feed, which is the
                // subject of a different test.
                viewModel.onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL))
                assertEquals(0, awaitUntilFeeds(PodcastFilter.ALL).single().undecidedCount)
            }
        }

    @Test
    fun `the default filter hides caught-up feeds but keeps unfetched ones`() =
        runTest {
            feeds.seed(
                feed(url = "done", title = "Done", lastRefreshedAt = 1),
                feed(url = "new", title = "New", lastRefreshedAt = 1),
                feed(url = "never", title = "Never"),
            )
            episodes.seed(
                episode(key = "d1", feedUrl = "done"),
                episode(key = "n1", feedUrl = "new"),
            )
            ledger.seedRow(ledgerRow("d1", state = LedgerState.SKIPPED))

            viewModel().state.test {
                val visible = awaitUntilFeeds().map { it.url }
                assertTrue("an unfetched feed must not be hidden", visible.contains("never"))
                assertTrue(visible.contains("new"))
                assertFalse("a caught-up feed is hidden by the default filter", visible.contains("done"))
            }
        }

    @Test
    fun `switching to All shows the caught-up feed again`() =
        runTest {
            feeds.seed(feed(url = "done", title = "Done", lastRefreshedAt = 1))
            episodes.seed(episode(key = "d1", feedUrl = "done"))
            ledger.seedRow(ledgerRow("d1", state = LedgerState.SKIPPED))
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                viewModel.onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL))

                assertEquals(listOf("done"), awaitUntilFeeds(PodcastFilter.ALL).map { it.url })
            }
        }

    @Test
    fun `with no Nextcloud account the screen is not-configured, not empty`() =
        runTest {
            // Different states with different answers: one offers Connect, the other explains that
            // subscriptions live in Nextcloud. Neither offers an add-feed field.
            settings.account.value = null

            viewModel().state.test {
                assertEquals(PodcastListUiState.Content.NotConfigured, awaitItem().content)
            }
        }

    @Test
    fun `configured with zero subscriptions is the read-only empty state`() =
        runTest {
            viewModel().state.test {
                val state = awaitUntil { it.content !is PodcastListUiState.Content.Loading }
                assertEquals(PodcastListUiState.Content.NoSubscriptions, state.content)
            }
        }

    @Test
    fun `refreshing offline reports it immediately instead of attempting anything`() =
        runTest {
            connectivity.online = false
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(PodcastListEvent.PullToRefresh)

                assertEquals(PodcastListEffect.ShowMessage(SnackbarText.Offline), awaitItem())
            }
            assertTrue("nothing may be scheduled while offline", scheduler.refreshes.isEmpty())
            assertEquals("nor a sync pass — offline is a precondition, not a failure", 0, scheduler.syncs)
        }

    /**
     * Issue #60. `UI.adoc` §4 specifies a sync pass **and** a feed refresh, and only the second
     * one shipped — so the gesture fetched RSS and never touched the action log in either direction.
     *
     * The order is asserted, not incidental: the pass replaces the subscription list, so refreshing
     * first would fetch the set of feeds the sync is about to replace.
     */
    @Test
    fun `pull to refresh syncs, then refreshes every feed rather than one`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(PodcastListEvent.PullToRefresh)

            assertEquals(1, scheduler.syncs)
            assertEquals(listOf("sync", "refresh"), scheduler.order)
            // `null` is "every feed": S1 refreshes the whole subscription list, S2 only its own.
            assertEquals(listOf<String?>(null), scheduler.refreshes)
        }

    @Test
    fun `tapping a feed navigates and decides nothing`() =
        runTest {
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(PodcastListEvent.FeedClicked("a"))

                assertEquals(PodcastListEffect.OpenEpisodes("a"), awaitItem())
            }
            assertTrue(ledger.writes.isEmpty())
        }

    /** S1 owns no navigation (`UI.adoc` §B0.2): every route is an effect the host performs. */
    @Test
    fun `every app-bar and checklist route is an effect, and none decides anything`() =
        runTest {
            val routes =
                mapOf(
                    PodcastListEvent.ActivityClicked to PodcastListEffect.OpenActivity,
                    PodcastListEvent.SettingsClicked to PodcastListEffect.OpenSettings,
                    PodcastListEvent.ConnectNextcloudClicked to PodcastListEffect.OpenConnect,
                    PodcastListEvent.ChooseFolderClicked to PodcastListEffect.ChooseFolder,
                    PodcastListEvent.NamingClicked to PodcastListEffect.OpenNaming,
                    PodcastListEvent.PausedBannerActionClicked to PodcastListEffect.ResolvePausedQueue,
                )
            val viewModel = viewModel()

            viewModel.effect.test {
                routes.forEach { (event, effect) ->
                    viewModel.onEvent(event)
                    assertEquals("event=$event", effect, awaitItem())
                }
                expectNoEvents()
            }
            assertTrue(ledger.writes.isEmpty())
            assertTrue(scheduler.refreshes.isEmpty())
        }

    /**
     * `activeDownloads` had a default of 0 and no assignment anywhere, so "n downloading" and the
     * app-bar badge were both dead. Only *running* work counts: an `ERROR` row is in flight for S7
     * but is emphatically not a download in progress (`UI.adoc` §12.5).
     */
    @Test
    fun `running downloads are counted per feed and light the badge, failures are not`() =
        runTest {
            feeds.seed(
                feed(url = FEED_URL, title = "Alpha", lastRefreshedAt = 1),
                feed(url = "idle", title = "Bravo", lastRefreshedAt = 1),
            )
            episodes.seed(
                episode(key = "q"),
                episode(key = "d"),
                episode(key = "x"),
                episode(key = "undecided", feedUrl = "idle"),
            )
            ledger.seedRow(ledgerRow("q", state = LedgerState.QUEUED))
            ledger.seedRow(ledgerRow("d", state = LedgerState.DOWNLOADING))
            ledger.seedRow(ledgerRow("x", state = LedgerState.ERROR, lastError = "boom"))
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL))
                val state = awaitUntil { (it.content as? PodcastListUiState.Content.Feeds)?.feeds?.size == 2 }
                val rows = (state.content as PodcastListUiState.Content.Feeds).feeds.associateBy { it.url }

                assertEquals(2, rows.getValue(FEED_URL).activeDownloads)
                assertEquals(0, rows.getValue("idle").activeDownloads)
                assertTrue(state.activityBadge)
            }
        }

    @Test
    fun `nothing running leaves the badge dark`() =
        runTest {
            feeds.seed(feed(url = "a", title = "Alpha", lastRefreshedAt = 1))
            episodes.seed(episode(key = "a1", feedUrl = "a"))
            ledger.seedRow(ledgerRow("a1", state = LedgerState.DOWNLOADED))

            viewModel().state.test {
                assertFalse(awaitUntil { it.content is PodcastListUiState.Content.Feeds }.activityBadge)
            }
        }

    /** The summary sums what is known; a never-fetched feed's unknown count is not a zero to add. */
    @Test
    fun `the total counts undecided episodes across fetched feeds only`() =
        runTest {
            feeds.seed(
                feed(url = "a", title = "Alpha", lastRefreshedAt = 1),
                feed(url = "b", title = "Bravo", lastRefreshedAt = 1),
                feed(url = "never", title = "Never"),
            )
            episodes.seed(
                episode(key = "a1", feedUrl = "a"),
                episode(key = "a2", feedUrl = "a"),
                episode(key = "b1", feedUrl = "b"),
            )

            viewModel().state.test {
                assertEquals(3, awaitUntil { it.content is PodcastListUiState.Content.Feeds }.totalUndecided)
            }
        }

    @Test
    fun `losing the network is shown on the home screen`() =
        runTest {
            connectivity.online = false

            viewModel().state.test {
                assertTrue(awaitItem().isOffline)
            }
        }

    /**
     * S1's queue banner has only the folder grant to go on, so each grant state maps to exactly one
     * queue state — and the checklist stays up until the app can actually complete a download.
     */
    @Test
    fun `each folder grant state pauses or runs the queue, and holds the checklist open until granted`() =
        runTest {
            val expected =
                mapOf(
                    FolderState.GRANTED to QueueStatus.Running,
                    FolderState.NOT_CHOSEN to
                        QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_NOT_CHOSEN, queuedCount = 0),
                    FolderState.REVOKED to QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_REVOKED, queuedCount = 0),
                )
            expected.forEach { (grant, queue) ->
                folder.state = grant

                viewModel().state.test {
                    val state = awaitItem()
                    assertEquals("grant=$grant", queue, state.queueStatus)
                    if (grant == FolderState.GRANTED) {
                        assertNull("grant=$grant", state.setup)
                    } else {
                        assertEquals("grant=$grant", grant, state.setup?.folderState)
                    }
                }
            }
        }

    /**
     * The other half of the frozen order: an explicit refresh is the one moment the list *may*
     * reorder, because the user asked for it and is watching the indicator.
     */
    @Test
    fun `a pull to refresh re-sorts the list once it finishes`() =
        runTest {
            feeds.seed(
                feed(url = "a", title = "Alpha", lastRefreshedAt = 1),
                feed(url = "b", title = "Bravo", lastRefreshedAt = 1),
            )
            episodes.seed(
                episode(key = "a1", feedUrl = "a", pubDate = 9_000),
                episode(key = "b1", feedUrl = "b", pubDate = 1_000),
            )
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL))
                assertEquals(listOf("a", "b"), awaitUntilFeeds(PodcastFilter.ALL).map { it.url })

                episodes.seed(episode(key = "b2", feedUrl = "b", pubDate = 99_000))
                viewModel.onEvent(PodcastListEvent.PullToRefresh)

                assertEquals(
                    listOf("b", "a"),
                    awaitUntil { state ->
                        (state.content as? PodcastListUiState.Content.Feeds)?.feeds?.firstOrNull()?.url == "b"
                    }.let { (it.content as PodcastListUiState.Content.Feeds).feeds.map { feed -> feed.url } },
                )
            }
        }

    @Test
    fun `the indicator stays up for the whole of the refresh`() =
        runTest {
            scheduler.inFlightRefresh = CompletableDeferred()
            val viewModel = viewModel()

            viewModel.state.test {
                viewModel.onEvent(PodcastListEvent.PullToRefresh)
                awaitUntil { it.isRefreshing }

                scheduler.completeRefresh()
                awaitUntil { !it.isRefreshing }
            }
        }
}

/** Consumes emissions until the content is a list, so tests need not count emissions. */
private suspend fun app.cash.turbine.ReceiveTurbine<PodcastListUiState>.awaitUntilFeeds(
    filter: PodcastFilter = PodcastFilter.WITH_NEW,
): List<FeedUi> {
    while (true) {
        val state = awaitItem()
        val content = state.content
        if (content is PodcastListUiState.Content.Feeds && state.filter == filter) return content.feeds
    }
}

private suspend fun app.cash.turbine.ReceiveTurbine<PodcastListUiState>.awaitUntil(
    predicate: (PodcastListUiState) -> Boolean,
): PodcastListUiState {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
