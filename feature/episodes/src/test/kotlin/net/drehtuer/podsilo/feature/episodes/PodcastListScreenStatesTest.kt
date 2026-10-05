// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * S1's states that `PodcastListScreenTest` never rendered: every pause cause, offline, loading, and
 * the two app-bar routes with the activity badge.
 *
 * Table-driven through one `mutableStateOf`, since a compose rule takes one `setContent` per test.
 */
@RunWith(RobolectricTestRunner::class)
class PodcastListScreenStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<PodcastListEvent>()

    private val base =
        PodcastListUiState(
            content =
                PodcastListUiState.Content.Feeds(
                    listOf(FeedUi(url = "a", title = "Der Podcast", undecidedCount = 3)),
                ),
        )

    private val state = mutableStateOf(base)

    private fun render(initial: PodcastListUiState = base) {
        state.value = initial
        compose.setContent {
            PodcastListScreen(state = state.value, onEvent = { events += it }, now = NOW)
        }
    }

    private fun show(next: PodcastListUiState) {
        state.value = next
        compose.waitForIdle()
    }

    /** The same three causes S2 distinguishes, each with its fix as a button (`UI.adoc` §12.11). */
    @Test
    fun `every pause cause names its own problem and carries its own fix`() {
        val cases =
            mapOf(
                QueueStatus.PauseCause.FOLDER_NOT_CHOSEN to ("no download folder chosen" to "Choose folder"),
                QueueStatus.PauseCause.FOLDER_REVOKED to ("no longer available" to "Choose folder"),
                QueueStatus.PauseCause.DISK_FULL to ("no space left" to "Free up space"),
            )
        render()

        cases.forEach { (cause, expected) ->
            val (problem, fix) = expected
            show(base.copy(queueStatus = QueueStatus.Paused(cause, queuedCount = 0)))
            events.clear()

            compose.onNode(hasContentDescription(problem, substring = true)).assertIsDisplayed()
            compose.onNodeWithText(fix).performClick()

            assertEquals("cause=$cause", listOf(PodcastListEvent.PausedBannerActionClicked), events)
        }
    }

    @Test
    fun `offline shows its banner above the list, and online shows none`() {
        render(base.copy(isOffline = true))

        compose.onNodeWithText("No network connection").assertIsDisplayed()
        compose.onNodeWithText("Der Podcast").assertIsDisplayed()

        show(base)
        compose.onAllNodes(hasText("No network connection")).assertCountEquals(0)
    }

    /** Placeholder rows, not an empty state: "not loaded yet" must not read as "no subscriptions". */
    @Test
    fun `loading shows placeholder rows and no empty-state sentence`() {
        render(PodcastListUiState(content = PodcastListUiState.Content.Loading))

        compose.onAllNodesWithContentDescription("Loading podcasts").onFirst().assertIsDisplayed()
        compose.onAllNodes(hasText("No subscriptions", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("caught up", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `the app bar reaches Activity and Settings`() {
        render()

        compose.onNodeWithContentDescription("Activity").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(listOf(PodcastListEvent.ActivityClicked, PodcastListEvent.SettingsClicked), events)
    }

    /** A dot, not a count — but a screen-reader user still has to be told something is running. */
    @Test
    fun `the activity badge is announced while something runs`() {
        render(base.copy(activityBadge = true))

        compose.onNodeWithContentDescription("Activity, running").assertIsDisplayed()

        show(base)
        compose.onNodeWithContentDescription("Activity").assertIsDisplayed()
    }

    /** Each unfinished step carries its own action; a finished one offers nothing to do again. */
    @Test
    fun `each checklist step emits its own route, and a done step has no button`() {
        render(
            base.copy(
                setup =
                    SetupChecklist(
                        nextcloudConnected = false,
                        instanceLabel = null,
                        folderState = FolderState.NOT_CHOSEN,
                        namingPreview = "Der Podcast/20260714_Warum.mp3",
                    ),
            ),
        )

        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Choose folder").performClick()
        compose.onNodeWithText("Naming").performClick()
        assertEquals(
            listOf(
                PodcastListEvent.ConnectNextcloudClicked,
                PodcastListEvent.ChooseFolderClicked,
                PodcastListEvent.NamingClicked,
            ),
            events,
        )

        show(
            base.copy(
                setup =
                    SetupChecklist(
                        nextcloudConnected = true,
                        instanceLabel = "https://cloud.example.org",
                        folderState = FolderState.NOT_CHOSEN,
                        namingPreview = "x",
                    ),
            ),
        )
        compose.onAllNodes(hasText("Connect")).assertCountEquals(0)
    }

    @Test
    fun `Refresh on the no-subscriptions state asks for a refresh`() {
        // The only affordance that state has, and it must not be an add-feed one (CLAUDE.md §10).
        render(PodcastListUiState(content = PodcastListUiState.Content.NoSubscriptions))

        compose.onNodeWithText("Refresh").performClick()

        assertEquals(listOf(PodcastListEvent.PullToRefresh), events)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-02T12:00:00Z")
    }
}
