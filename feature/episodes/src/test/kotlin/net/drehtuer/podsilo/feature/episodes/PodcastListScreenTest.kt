// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * S1's rendering. The load-bearing assertions are the two the design keeps insisting on: a screen
 * with no subscriptions must point at Nextcloud and **never** offer an add-feed control, and a
 * never-fetched feed shows "–" rather than a confident zero.
 */
@RunWith(RobolectricTestRunner::class)
class PodcastListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<PodcastListEvent>()
    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun render(state: PodcastListUiState) {
        compose.setContent {
            PodcastListScreen(state = state, onEvent = { events += it }, now = now)
        }
    }

    private fun feeds(vararg rows: FeedUi) =
        PodcastListUiState(content = PodcastListUiState.Content.Feeds(rows.toList()))

    @Test
    fun `a feed renders its title and its count`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 12)))

        compose.onNodeWithText("Der Podcast").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `a feed with no title yet renders its URL, never Unknown podcast`() {
        // A feed has no title until the first successful fetch (architecture §4).
        render(feeds(FeedUi(url = "https://example.org/feed.xml", title = null)))

        compose.onNodeWithText("https://example.org/feed.xml").assertIsDisplayed()
        compose.onAllNodes(hasText("Unknown", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a never-fetched feed shows a dash and says so, rather than zero`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = null)))

        compose.onNodeWithText("–").assertIsDisplayed()
        compose.onNode(hasText("never refreshed", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("0")).assertCountEquals(0)
    }

    @Test
    fun `a refreshed feed reports how long ago, relatively`() {
        render(
            feeds(
                FeedUi(
                    url = "a",
                    title = "Der Podcast",
                    lastRefreshedAt = now.minusSeconds(600),
                    undecidedCount = 3,
                ),
            ),
        )

        compose.onNode(hasText("10 min ago", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `tapping a feed opens it`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 1)))

        compose.onNodeWithText("Der Podcast").performClick()

        assertEquals(listOf(PodcastListEvent.FeedClicked("a")), events)
    }

    @Test
    fun `the no-subscriptions state points at Nextcloud and offers no way to add a feed`() {
        // This empty state is the main place the read-only design becomes visible (CLAUDE.md §10),
        // so the absence of an add button is the assertion, not the wording alone.
        render(PodcastListUiState(content = PodcastListUiState.Content.NoSubscriptions))

        compose.onNode(hasText("add feeds in Nextcloud", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("Add", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("New podcast", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `an unconfigured app offers Connect rather than an empty list`() {
        render(PodcastListUiState(content = PodcastListUiState.Content.NotConfigured))

        compose.onNode(hasText("subscriptions in your Nextcloud", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Connect Nextcloud").performClick()

        assertTrue(events.contains(PodcastListEvent.ConnectNextcloudClicked))
    }

    @Test
    fun `the app bar carries the mark without announcing the name twice`() {
        // §4.1: the mark is `null`-described because "Podsilo" is live type beside it.
        render(PodcastListUiState(content = PodcastListUiState.Content.Feeds(emptyList())))

        compose.onNodeWithText("Podsilo").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Podsilo").assertCountEquals(0)
    }

    @Test
    fun `a filter that hides everything says caught up, not no subscriptions`() {
        render(PodcastListUiState(content = PodcastListUiState.Content.Feeds(emptyList())))

        compose.onNodeWithText("Nothing new. All caught up.").assertIsDisplayed()
        compose.onNodeWithText("Show all podcasts").performClick()

        assertEquals(
            listOf(PodcastListEvent.FilterChanged(PodcastFilter.ALL)),
            events,
        )
    }

    @Test
    fun `the setup checklist shows each step and its remaining action`() {
        render(
            PodcastListUiState(
                content = PodcastListUiState.Content.Feeds(emptyList()),
                setup =
                    SetupChecklist(
                        nextcloudConnected = true,
                        instanceLabel = "https://cloud.example.org",
                        folderState = FolderState.NOT_CHOSEN,
                        namingPreview = "Der Podcast/20260714_Warum.mp3",
                    ),
            ),
        )

        compose.onNode(hasText("✓ 1. Connect Nextcloud", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("○ 2. Choose a download folder", substring = true)).assertIsDisplayed()
        // Step 3 is optional, so it renders as done and never holds the card open.
        compose.onNode(hasText("✓ 3. Check file naming", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("20260714_Warum.mp3", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a revoked grant says the folder is gone, not that none was chosen`() {
        // Otherwise the user re-picks the folder that just failed (CLAUDE.md §11).
        render(
            PodcastListUiState(
                content = PodcastListUiState.Content.Feeds(emptyList()),
                setup =
                    SetupChecklist(
                        nextcloudConnected = true,
                        instanceLabel = "https://cloud.example.org",
                        folderState = FolderState.REVOKED,
                        namingPreview = "x",
                    ),
            ),
        )

        compose.onNode(hasText("no longer available", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the paused banner does not double up with the checklist that says the same thing`() {
        // Regression from the first device run: both rendered on first launch, one above the other,
        // and the checklist is the more useful of the two.
        render(
            PodcastListUiState(
                content = PodcastListUiState.Content.NoSubscriptions,
                queueStatus = QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_NOT_CHOSEN, queuedCount = 0),
                setup =
                    SetupChecklist(
                        nextcloudConnected = false,
                        instanceLabel = null,
                        folderState = FolderState.NOT_CHOSEN,
                        namingPreview = "x",
                    ),
            ),
        )

        compose.onAllNodes(hasText("Downloads paused", substring = true)).assertCountEquals(0)
        compose.onNode(hasText("2. Choose a download folder", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the summary line counts what is on screen`() {
        assertEquals(
            "18 episodes to decide across 7 podcasts with new episodes",
            summaryLine(18, 7, PodcastFilter.WITH_NEW),
        )
        assertEquals(
            "0 episodes to decide across 9 podcasts",
            summaryLine(0, 9, PodcastFilter.ALL),
        )
    }

    @Test
    fun `relative times stay coarse`() {
        val then = Instant.parse("2026-08-02T12:00:00Z")
        assertEquals("just now", relativeTime(then, then.plusSeconds(30)))
        assertEquals("5 min ago", relativeTime(then, then.plusSeconds(300)))
        assertEquals("2 h ago", relativeTime(then, then.plusSeconds(7_200)))
        assertEquals("3 d ago", relativeTime(then, then.plusSeconds(259_200)))
    }

    @Test
    fun `a podcast row draws its artwork, and its monogram before the first fetch`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", artworkUrl = null, undecidedCount = 3)))

        compose.onNodeWithContentDescription("cover art for Der Podcast").assertIsDisplayed()
    }

    /**
     * The same defect issue #48 reported on S2, on the screen where it was never reported.
     *
     * Two chips fit a 360 dp phone at the default font scale, which is why nobody hit it — but the
     * labels are long, and a narrow screen or a large font scale is all it takes. The shipped row
     * had no scroll, so the second chip was clipped and unreachable by any gesture;
     * `performScrollTo` fails on a node whose parents cannot scroll, so this fails against it.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `both filter chips are reachable on a narrow screen`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 12)))

        compose.onNodeWithText("All podcasts").performScrollTo().performClick()

        assertEquals(listOf(PodcastListEvent.FilterChanged(PodcastFilter.ALL)), events)
    }

    /**
     * The regression test for what the first real-account run exposed.
     *
     * `PullToRefresh` existed as an event and the view model handled it, but **nothing in the UI
     * ever emitted it** — the sole caller was the Refresh button in the *no subscriptions* empty
     * state, which by definition never renders once there are feeds. With four real subscriptions
     * the app therefore had no way to fetch a feed at all, and every row read "never refreshed"
     * permanently. Asserting on the *populated* state is the whole point: the empty state was never
     * the broken one.
     */
    @Test
    fun `a screen with feeds can be refreshed by pulling it down`() {
        render(feeds(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 12)))

        // Swiping the feed row rather than `hasScrollAction()`: S1's chip row became scrollable in
        // its own right when it got #48's fix, so that matcher now finds two nodes. Nested scroll
        // carries the gesture from the row up to the `PullToRefreshBox` regardless.
        compose.onNodeWithText("Der Podcast").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + PULL_DISTANCE_PX)
        }

        assertTrue(
            "pulling a populated list down must request a refresh, got $events",
            events.contains(PodcastListEvent.PullToRefresh),
        )
    }
}
