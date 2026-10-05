// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2's rendering, driven through the real Compose runtime under Robolectric — headless, no emulator
 * (CLAUDE.md §4's Tier 1 definition: "Android-framework bits via Robolectric").
 *
 * These assert the things a screen can get wrong on its own, independently of the view model: that a
 * tap on the body opens detail rather than triaging, that a failure the user cannot retry away shows
 * the action that *can* clear it, and that a `DOWNLOADING` row with no live progress says *resuming*
 * rather than drawing a stale percentage.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<EpisodeListEvent>()

    @Suppress("LongParameterList") // A builder's parameters are the type's fields.
    private fun row(
        key: String = "e1",
        title: String = "Warum Hamburg immer regnet",
        ledgerState: LedgerState? = null,
        progress: DownloadProgress? = null,
        failure: FailureUi? = null,
        publishedAt: Instant? = Instant.parse("2026-07-14T09:00:00Z"),
        durationMinutes: Long? = 48,
        sizeBytes: Long? = null,
        episodePageUrl: String? = null,
    ) = EpisodeUi(
        episodeKey = key,
        feedUrl = FEED_URL,
        feedTitle = "Der Podcast",
        title = title,
        artworkUrl = null,
        publishedAt = publishedAt,
        duration = durationMinutes?.let { java.time.Duration.ofMinutes(it) },
        sizeBytes = sizeBytes,
        descriptionSnippet = "Eine Folge über Regen",
        ledgerState = ledgerState,
        progress = progress,
        lastError = failure,
        episodePageUrl = episodePageUrl,
    )

    private fun render(state: EpisodeListUiState = EpisodeListUiState(feedUrl = FEED_URL, feedTitle = "Der Podcast")) {
        compose.setContent {
            EpisodeListScreen(state = state, onEvent = { events += it }, zone = ZoneOffset.UTC)
        }
    }

    private fun listOf(vararg rows: EpisodeUi) =
        EpisodeListUiState(
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            content = EpisodeListUiState.Content.Episodes(rows.toList()),
        )

    @Test
    fun `an episode renders its title, date and duration`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onNode(hasText("48 min", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a missing duration simply has no part in the meta line`() {
        // Never "unknown", never a fabricated value (UI.adoc §5).
        render(listOf(row(publishedAt = null, durationMinutes = null)))

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onAllNodes(hasText("min", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `tapping the row body opens detail and never triages`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").performClick()

        assertEquals(kotlin.collections.listOf(EpisodeListEvent.RowClicked("e1")), events)
    }

    /**
     * `UI.adoc` §5's row overflow, and §12.1's **mandatory** non-gesture equivalent of the swipes.
     * It did not exist: the row rendered inline `TextButton`s instead, and this replaces them.
     */
    @Test
    fun `an undecided episode offers Download and Mark as played in its overflow`() {
        render(listOf(row()))

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()

        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Mark as played").assertIsDisplayed()
    }

    @Test
    fun `Download emits a triage event for that episode`() {
        render(listOf(row()))

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()
        compose.onNodeWithText("Download").performClick()

        assertEquals(
            kotlin.collections.listOf(EpisodeListEvent.Triage("e1", EpisodeUiAction.DOWNLOAD)),
            events,
        )
    }

    /**
     * The two actions that had **no row-level call site at all**: `labelFor` returned `null` for
     * both, so they were reachable only from S3 even though §5 lists them in the row's overflow.
     */
    @Test
    fun `the overflow offers the two actions the row could never reach`() {
        render(listOf(row(episodePageUrl = "https://example.org/episodes/1")))

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()

        compose.onNodeWithText("Open in browser").assertIsDisplayed()
        compose.onNodeWithText("Copy episode link").performClick()

        assertEquals(
            kotlin.collections.listOf(EpisodeListEvent.Triage("e1", EpisodeUiAction.COPY_LINK)),
            events,
        )
    }

    /** The menu is built from `actions`, so a terminal row offers a re-decision and not a first one. */
    @Test
    fun `a downloaded episode offers Download again rather than Download`() {
        render(listOf(row(ledgerState = LedgerState.DOWNLOADED)))

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()

        compose.onNodeWithText("Download again").assertIsDisplayed()
    }

    /** Selection mode owns the row; a per-row menu would compete with the selection bar's actions. */
    @Test
    fun `the row overflow is absent in selection mode`() {
        render(selecting("e1"))

        compose.onAllNodesWithContentDescription("Actions for Warum Hamburg immer regnet").assertCountEquals(0)
    }

    // ---- The feed-error banner (UI.adoc §5), which nothing could ever show ----

    @Test
    fun `a feed failure shows its plain sentence above the list, with Try again`() {
        render(listOf(row()).copy(feedError = "Feed server did not respond."))

        compose.onNodeWithText("Feed server did not respond.").assertIsDisplayed()
        // The episodes stay listed: a failed refresh must not empty the screen (§5).
        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()

        compose.onNodeWithText("Try again").performClick()
        assertTrue(events.contains(EpisodeListEvent.RetryFeedClicked))
    }

    @Test
    fun `no banner when the feed is healthy`() {
        render(listOf(row()))

        compose.onAllNodes(hasText("Try again")).assertCountEquals(0)
    }

    @Test
    fun `a lost folder grant shows Choose folder instead of Retry`() {
        // The rendered half of `architecture.adoc` §11: a Retry button here cannot possibly work, so
        // the row must offer the action that can.
        render(
            listOf(
                row(
                    ledgerState = LedgerState.ERROR,
                    failure =
                        FailureUi(
                            cause = ErrorCause.FOLDER_UNAVAILABLE,
                            message = "the download folder is no longer accessible",
                            attempts = 1,
                            retryable = false,
                        ),
                ),
            ),
        )

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onAllNodes(hasText("Retry", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a network failure does show Retry`() {
        render(
            listOf(
                row(
                    ledgerState = LedgerState.ERROR,
                    failure =
                        FailureUi(
                            cause = ErrorCause.NETWORK,
                            message = "connection reset",
                            attempts = 2,
                            retryable = true,
                        ),
                ),
            ),
        )

        compose.onNodeWithContentDescription("Actions for Warum Hamburg immer regnet").performClick()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        // The message is shown verbatim — it is the one string the UI does not re-word.
        compose.onNode(hasText("connection reset", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a downloading row with no live progress says resuming, not zero percent`() {
        // `UI.adoc` §B7: a percentage is only ever drawn from an update seen in this process, so
        // after process death the row must not imply it knows how far along it is.
        render(listOf(row(ledgerState = LedgerState.DOWNLOADING, progress = null)))

        compose.onNodeWithContentDescription("resuming").assertIsDisplayed()
    }

    @Test
    fun `a downloading row with live progress draws the percentage`() {
        render(
            listOf(
                row(
                    ledgerState = LedgerState.DOWNLOADING,
                    progress = DownloadProgress(bytesDownloaded = 620, totalBytes = 1_000),
                ),
            ),
        )

        compose.onNodeWithContentDescription("downloading, 62 percent").assertIsDisplayed()
    }

    @Test
    fun `the empty state names the filter rather than looking like a loading screen`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Empty(EpisodeFilter.TO_DECIDE),
            ),
        )

        compose.onNodeWithText("Nothing to decide in this podcast.").assertIsDisplayed()
    }

    @Test
    fun `changing the filter emits, and does not decide anything locally`() {
        render(listOf(row()))

        compose.onNodeWithText("Downloaded").performClick()

        assertEquals(
            kotlin.collections.listOf(EpisodeListEvent.FilterChanged(EpisodeFilter.DOWNLOADED)),
            events,
        )
    }

    @Test
    fun `the Download all dialog names the count and writes nothing until confirmed`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(row())),
                pendingBulk =
                    BulkPreview(
                        episodeKeys = kotlin.collections.listOf("a", "b", "c"),
                        perFeed = kotlin.collections.listOf(FeedBreakdown(FEED_URL, 3)),
                        estimatedBytes = null,
                        freeBytes = null,
                    ),
            ),
        )

        compose.onNode(hasText("Download 3 episodes?", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        assertTrue(events.contains(EpisodeListEvent.DownloadAllDismissed))
        assertTrue("dismissing must not confirm", events.none { it is EpisodeListEvent.DownloadAllConfirmed })
    }

    @Test
    fun `the size warning appears only when the estimate exceeds free space`() {
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(row())),
                pendingBulk =
                    BulkPreview(
                        episodeKeys = kotlin.collections.listOf("a"),
                        perFeed = kotlin.collections.listOf(FeedBreakdown(FEED_URL, 1)),
                        estimatedBytes = 5_000_000_000,
                        freeBytes = 1_000_000,
                    ),
            ),
        )

        compose.onNode(hasText("may not fit", substring = true)).assertIsDisplayed()
        // The confirm button stays present: the estimate is a guess and must not veto the decision.
        // One node now, not two: the row's own Download moved into its overflow (UI.adoc §5).
        compose.onAllNodes(hasText("Download")).assertCountEquals(1)
    }

    /**
     * Artwork was specified in `UI.adoc` §5's row anatomy and never drawn — Coil was approved
     * (UI.adoc §18), added to the catalog, and depended on by no module at all.
     */
    @Test
    fun `an episode row draws its artwork`() {
        render(listOf(row()))

        compose.onNodeWithContentDescription("cover art for Warum Hamburg immer regnet").assertIsDisplayed()
    }

    /**
     * S2's half of the missing-refresh bug (see `PodcastListScreenTest`): the event and its handler
     * existed, and **no affordance anywhere on this screen emitted it** — so a feed whose fetch had
     * failed could not be retried from the screen that shows the failure.
     *
     * Swiping the row rather than `hasScrollAction()`: the chip row became scrollable in its own
     * right when issue #48 was fixed, so that matcher now finds two nodes. Nested scroll carries the
     * gesture from the row up to the `PullToRefreshBox` regardless.
     */
    @Test
    fun `pulling the episode list down refreshes this feed`() {
        render(listOf(row()))

        // An explicit distance, not the default `swipeDown()`. That swipes from the node's top to
        // its bottom, and the row got shorter when its buttons moved into the overflow — short
        // enough that the gesture no longer crossed the pull-to-refresh threshold. The row's height
        // is not what this test is about.
        compose.onNodeWithText("Warum Hamburg immer regnet").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + PULL_DISTANCE_PX)
        }

        assertTrue(
            "pulling the episode list down must request a refresh, got $events",
            events.contains(EpisodeListEvent.PullToRefresh),
        )
    }

    /**
     * *Mark all as played* on the **Downloaded** filter (the author's request).
     *
     * Scoped to that filter: *To decide* already has S4's per-feed preview, and on *Played /
     * handled* it would be a no-op. It confirms first because this writes `PLAY` actions to a shared
     * log that other clients act on, and no undo reaches them (`decisions/0013`).
     */
    @Test
    fun `Downloaded offers Mark all as played with its count`() {
        render(downloaded(row(key = "a"), row(key = "b")))

        compose.onNodeWithText("Mark all 2 as played").performClick()

        assertTrue(events.contains(EpisodeListEvent.MarkAllRequested))
    }

    @Test
    fun `Mark all is absent on the To decide filter`() {
        render(listOf(row()))

        compose.onAllNodes(hasText("Mark all", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `the Mark all dialog names the count and says the state reaches Nextcloud`() {
        render(downloaded(row(key = "a"), row(key = "b")).copy(pendingMarkAll = listOf("a", "b")))

        compose.onNode(hasText("Mark 2 downloaded episodes as played?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
        // Podsilo never deletes a file, and the dialog has to say so or "mark as played" reads as tidy-up.
        compose.onNode(hasText("never deletes files", substring = true)).assertIsDisplayed()
    }

    private fun downloaded(vararg rows: EpisodeUi) =
        EpisodeListUiState(
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            filter = EpisodeFilter.DOWNLOADED,
            content = EpisodeListUiState.Content.Episodes(rows.toList()),
        )

    /**
     * The size the feed advertises, beside the duration (the author's request).
     *
     * Whole megabytes and never a decimal: the source is `<enclosure length>`, a publisher's claim,
     * and decimals imply a precision it does not have — the same reason durations render in whole
     * minutes.
     */
    @Test
    fun `the meta line carries the advertised size beside the duration`() {
        render(listOf(row(sizeBytes = 29_947_412)))

        compose.onNode(hasText("29 MB", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("48 min", substring = true)).assertIsDisplayed()
    }

    /** A feed that gives no size simply has no size part — never "0 MB", never "unknown". */
    @Test
    fun `an episode with no advertised size shows no size part`() {
        render(listOf(row(sizeBytes = null)))

        compose.onAllNodes(hasText("MB", substring = true)).assertCountEquals(0)
    }

    /** "0 MB" reads as free. A feed advertising a few hundred bytes is wrong, not generous. */
    @Test
    fun `a sub-megabyte size does not render as zero`() {
        render(listOf(row(sizeBytes = 4_096)))

        compose.onNode(hasText("<1 MB", substring = true)).assertIsDisplayed()
    }

    // ---- The app bar, and the filter row that had no room (issue #48) ----

    /**
     * The regression test for issue #48, and it is deliberately about *reachability*, not visibility.
     *
     * On a 320 dp screen the four chips genuinely cannot all be on screen at once — that is what
     * decision D3 accepted when it chose a scrolling line over a wrapping one. What the shipped row
     * got wrong was having no scroll at all, so the last chip was clipped and `All` could not be
     * reached by any means. `performScrollTo` fails on a node inside a container that cannot scroll,
     * which is exactly the bug, so this test fails against the old `Row`.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `every filter chip is reachable on a narrow screen`() {
        render(listOf(row()))

        compose.onNodeWithText("All").performScrollTo().performClick()

        assertTrue(
            "the last filter chip must be reachable at 320 dp, got $events",
            events.contains(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL)),
        )
    }

    /**
     * An invariant guard, and **not** a reproduction of #48's reported overlap — stated plainly
     * because it passes against the unfixed row too, so it did not find that bug and cannot claim to.
     *
     * What the screenshot shows is a row *scrolled under* the chips: the chip row is fixed while the
     * `LazyColumn` scrolls beneath it, so a partially scrolled row shows only its bottom edge — its
     * action buttons — hard up against the chips, with no vertical gap to read as a boundary. The
     * fix for that is the row's own padding, which is a legibility change rather than a layout one.
     * The genuine layout fault #48 reports is the clipping, covered by the test above.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `the chip row does not overlap the first episode row`() {
        render(listOf(row()))

        val chips = compose.onNodeWithText("To decide").getUnclippedBoundsInRoot()
        val firstRow = compose.onNodeWithText("Warum Hamburg immer regnet").getUnclippedBoundsInRoot()

        assertTrue(
            "the first episode row (top=${firstRow.top}) must start below the chips (bottom=${chips.bottom})",
            firstRow.top >= chips.bottom,
        )
    }

    /**
     * S2 shipped with no app bar at all — alone among the eight screens — so there was no up
     * navigation, no feed title, and no host for the overflow or for #46's selection bar.
     */
    @Test
    fun `the app bar names the feed and offers up navigation`() {
        render(listOf(row()))

        compose.onNodeWithText("Der Podcast").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        assertTrue(events.contains(EpisodeListEvent.BackClicked))
    }

    @Test
    fun `the app bar reaches Activity, the second route the navigation map draws into it`() {
        render(listOf(row()))

        compose.onNodeWithContentDescription("Activity").performClick()

        assertTrue(events.contains(EpisodeListEvent.ActivityClicked))
    }

    /**
     * *Download all (n)* had a view-model handler, a `BulkPreview`, a confirmation dialog and tests —
     * and nothing that could emit `DownloadAllRequested`, because the app bar it belongs in did not
     * exist.
     */
    @Test
    fun `the overflow offers Download all with its count, and writes nothing by opening`() {
        render(listOf(row()).copy(downloadAllCount = 12))

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Download all (12)").assertIsDisplayed()

        assertTrue("opening the menu must decide nothing", events.isEmpty())

        compose.onNodeWithText("Download all (12)").performClick()
        assertTrue(events.contains(EpisodeListEvent.DownloadAllRequested))
    }

    /** An overflow whose only item is absent is a button that opens an empty menu. */
    @Test
    fun `the overflow is absent when there is nothing to download`() {
        render(listOf(row()).copy(downloadAllCount = 0))

        compose.onAllNodesWithContentDescription("More actions").assertCountEquals(0)
    }

    // ---- Selection mode (issue #46) ----

    /**
     * The entry point issue #46 was actually missing. The whole selection model — five events, the
     * "empty selection leaves the mode" rule, the "a filter change drops it" rule — was implemented
     * and unit-tested; `EpisodeRow` used `clickable`, so **nothing could emit `SelectionStarted`**
     * and the mode was unreachable.
     */
    @Test
    fun `long-pressing a row enters selection mode on that row`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").performTouchInput { longClick() }

        assertEquals(kotlin.collections.listOf(EpisodeListEvent.SelectionStarted("e1")), events)
    }

    /**
     * `UI.adoc` §12.12: selection must be reachable **without** a long-press, because a
     * gesture-only affordance is unreachable for a TalkBack user. Same event, not a parallel path.
     */
    @Test
    fun `selection is reachable through an accessibility action, not only by gesture`() {
        render(listOf(row()))

        compose.onNodeWithText("Warum Hamburg immer regnet").performCustomAccessibilityActionLabelled("Select")

        assertEquals(kotlin.collections.listOf(EpisodeListEvent.SelectionStarted("e1")), events)
    }

    @Test
    fun `the selection bar replaces the normal one and announces the count`() {
        render(selecting("e1"))

        compose.onNodeWithText("1 selected").assertIsDisplayed()
        // Replaced, not added to: leaving "Back" beside "1 selected" invites leaving the screen
        // when the user meant to leave the mode.
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    @Test
    fun `tapping a row in selection mode toggles it rather than opening detail`() {
        render(selecting("e1", rows = arrayOf(row(key = "e1"), row(key = "e2", title = "Die Elbe von unten"))))

        compose.onNodeWithText("Die Elbe von unten").performClick()

        assertEquals(kotlin.collections.listOf(EpisodeListEvent.SelectionToggled("e2")), events)
        assertTrue("a tap in selection mode must never open detail", events.none { it is EpisodeListEvent.RowClicked })
    }

    @Test
    fun `a selected row shows a checked box and an unselected one an empty box`() {
        render(selecting("e1", rows = arrayOf(row(key = "e1"), row(key = "e2", title = "Die Elbe von unten"))))

        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Not selected").assertIsDisplayed()
    }

    @Test
    fun `the selection bar offers both actions and Select all`() {
        render(selecting("e1"))

        compose.onNodeWithText("Select all").performClick()
        assertTrue(events.contains(EpisodeListEvent.SelectAllInFilter))

        compose.onNodeWithContentDescription("Download selected").performClick()
        compose.onNodeWithContentDescription("Mark selected as played").performClick()

        assertEquals(
            kotlin.collections.listOf(
                EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.DOWNLOAD),
                EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.MARK_AS_PLAYED),
            ),
            events.filterIsInstance<EpisodeListEvent.SelectionActionRequested>(),
        )
    }

    @Test
    fun `✕ leaves selection mode`() {
        render(selecting("e1"))

        compose.onNodeWithContentDescription("Leave selection mode").performClick()

        assertTrue(events.contains(EpisodeListEvent.SelectionCleared))
    }

    /**
     * §5's safeguard: a selection action names its count before anything is written. It matters most
     * for *Mark as played*, which emits `PLAY` actions to a shared log that no undo reaches.
     */
    @Test
    fun `acting on a selection confirms with the count first, and writes nothing by opening`() {
        render(selecting("e1", "e2", pendingAction = EpisodeUiAction.MARK_AS_PLAYED))

        compose.onNode(hasText("Mark 2 episodes as played?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
        assertTrue("opening the dialog must confirm nothing", events.none { it is EpisodeListEvent.BulkConfirmed })

        // Disambiguated from the row's own "Mark as played" button behind the dialog.
        compose.onNode(hasText("Mark as played") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(
            EpisodeListEvent.BulkConfirmed(EpisodeUiAction.MARK_AS_PLAYED, setOf("e1", "e2")),
            events.filterIsInstance<EpisodeListEvent.BulkConfirmed>().single(),
        )
    }

    @Test
    fun `dismissing the selection confirmation writes nothing`() {
        render(selecting("e1", pendingAction = EpisodeUiAction.DOWNLOAD))

        compose.onNode(hasText("Download 1 episode?", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(events.contains(EpisodeListEvent.SelectionActionDismissed))
        assertTrue(events.none { it is EpisodeListEvent.BulkConfirmed })
    }

    private fun selecting(
        vararg keys: String,
        rows: Array<EpisodeUi> = arrayOf(row()),
        pendingAction: EpisodeUiAction? = null,
    ) = EpisodeListUiState(
        feedUrl = FEED_URL,
        feedTitle = "Der Podcast",
        content = EpisodeListUiState.Content.Episodes(rows.toList()),
        selection = Selection(keys.toSet(), allInFilter = rows.size),
        pendingSelectionAction = pendingAction,
    )

    /**
     * Guards the month-header lookup after issue #91 replaced `items.indexOf(episode)` — a linear
     * scan run inside every visible row's composition — with a map keyed by the section's first
     * index. The headers must still land against the rows they label, and land once.
     */
    @Test
    fun `month headers land against the rows they label`() {
        val july = row(key = "e1", title = "Julifolge")
        val june = row(key = "e2", title = "Junifolge", publishedAt = Instant.parse("2026-06-02T09:00:00Z"))
        render(
            EpisodeListUiState(
                feedUrl = FEED_URL,
                feedTitle = "Der Podcast",
                // `listOf` is shadowed in this class by a helper that builds a whole ui state.
                content = EpisodeListUiState.Content.Episodes(kotlin.collections.listOf(july, june)),
                sections =
                    kotlin.collections.listOf(
                        MonthSection(label = YearMonth(2026, 7), firstIndex = 0, count = 1),
                        MonthSection(label = YearMonth(2026, 6), firstIndex = 1, count = 1),
                    ),
            ),
        )

        compose.onNodeWithText("2026-07").assertIsDisplayed()
        compose.onNodeWithText("2026-06").assertIsDisplayed()
    }
}

/**
 * Invokes a custom accessibility action by its label.
 *
 * Compose's test API has no built-in matcher for this, and the affordance it reaches is a
 * requirement rather than a nicety (`UI.adoc` §12.12: selection reachable without a long-press),
 * so it is worth the four lines to be able to assert it.
 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performCustomAccessibilityActionLabelled(
    label: String,
): androidx.compose.ui.test.SemanticsNodeInteraction =
    also {
        val actions =
            fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        val action =
            actions.firstOrNull { it.label == label }
                ?: error("no custom accessibility action labelled '$label'; found ${actions.map { a -> a.label }}")
        action.action()
    }

/** Comfortably past the pull-to-refresh threshold, and independent of any row's height. */
