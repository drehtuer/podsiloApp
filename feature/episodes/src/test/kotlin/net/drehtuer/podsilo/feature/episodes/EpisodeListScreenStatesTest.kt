// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * S2's states that `EpisodeListScreenTest` never rendered: every pause cause, the offline and
 * loading states, each filter's empty state, the dialogs' confirm buttons, and the selection-mode
 * accessibility action.
 *
 * Table-driven through one `mutableStateOf`, because a compose rule takes one `setContent` per test
 * and the subject of each test here is a *set* of states that must each say something different.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeListScreenStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<EpisodeListEvent>()

    private val row =
        EpisodeUi(
            episodeKey = "e1",
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            title = TITLE,
            artworkUrl = null,
            publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
            duration = null,
            descriptionSnippet = "",
            ledgerState = null,
        )

    private val base =
        EpisodeListUiState(
            feedUrl = FEED_URL,
            feedTitle = "Der Podcast",
            content = EpisodeListUiState.Content.Episodes(listOf(row)),
        )

    private val state = mutableStateOf(base)

    private fun render(initial: EpisodeListUiState = base) {
        state.value = initial
        compose.setContent {
            EpisodeListScreen(state = state.value, onEvent = { events += it }, zone = ZoneOffset.UTC)
        }
    }

    private fun show(next: EpisodeListUiState) {
        state.value = next
        compose.waitForIdle()
    }

    /**
     * `UI.adoc` §12.11: one condition, three causes, and the wording differs because "choose a
     * folder", "the one you chose is gone" and "the disk is full" are three different problems.
     */
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
            show(base.copy(queueStatus = QueueStatus.Paused(cause, queuedCount = 1)))
            events.clear()

            compose.onNode(hasContentDescription(problem, substring = true)).assertIsDisplayed()
            compose.onNodeWithText(fix).performClick()

            assertEquals("cause=$cause", listOf(EpisodeListEvent.PausedBannerActionClicked), events)
        }
    }

    /** §5: the overflow item is disabled *with its reason*, and the reason follows the cause. */
    @Test
    fun `Download all names why it is disabled, per pause cause`() {
        val cases =
            mapOf(
                QueueStatus.PauseCause.FOLDER_NOT_CHOSEN to "no folder chosen",
                QueueStatus.PauseCause.FOLDER_REVOKED to "folder unavailable",
                QueueStatus.PauseCause.DISK_FULL to "no space left",
            )
        render(base.copy(downloadAllCount = 3))
        // Opened once: the menu stays expanded while the pause cause changes underneath it.
        compose.onNodeWithContentDescription("More actions").performClick()

        cases.forEach { (cause, reason) ->
            show(base.copy(downloadAllCount = 3, queueStatus = QueueStatus.Paused(cause, queuedCount = 0)))

            compose.onNodeWithText("Download all (3)").assertIsNotEnabled()
            compose.onNodeWithText(reason).assertIsDisplayed()
        }
        assertEquals("a disabled item must not request a preview", emptyList<EpisodeListEvent>(), events)
    }

    @Test
    fun `offline shows its banner above a list that stays usable`() {
        render(base.copy(isOffline = true))

        compose.onNodeWithText("No network connection").assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun `no offline banner while online`() {
        render()

        compose.onAllNodes(hasText("No network connection")).assertCountEquals(0)
    }

    /** "Not loaded yet" and "nothing to decide" are different states and must not look alike. */
    @Test
    fun `loading shows progress, never an empty-state sentence`() {
        render(base.copy(content = EpisodeListUiState.Content.Loading))

        compose.onNodeWithContentDescription("Loading episodes").assertIsDisplayed()
        compose.onAllNodes(hasText("Nothing", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `an empty filter offers the way to All, and All itself offers nothing further`() {
        render(
            base.copy(
                filter = EpisodeFilter.DOWNLOADED,
                content = EpisodeListUiState.Content.Empty(EpisodeFilter.DOWNLOADED),
            ),
        )

        compose.onNodeWithText("Nothing here.").assertIsDisplayed()
        compose.onNodeWithText("Show all episodes").performClick()
        assertEquals(listOf(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL)), events)

        show(base.copy(filter = EpisodeFilter.ALL, content = EpisodeListUiState.Content.Empty(EpisodeFilter.ALL)))
        compose.onNodeWithText("Nothing here.").assertIsDisplayed()
        compose.onAllNodes(hasText("Show all episodes")).assertCountEquals(0)
    }

    @Test
    fun `confirming Download all hands back exactly the previewed keys`() {
        render(
            base.copy(
                pendingBulk =
                    BulkPreview(
                        episodeKeys = listOf("a", "b"),
                        perFeed = listOf(FeedBreakdown(FEED_URL, 2)),
                        estimatedBytes = null,
                        freeBytes = null,
                    ),
            ),
        )

        compose.onNode(hasText("Download") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf(EpisodeListEvent.DownloadAllConfirmed(listOf("a", "b"))), events)
    }

    @Test
    fun `the Mark all dialog confirms and cancels through its own events`() {
        val downloaded = base.copy(filter = EpisodeFilter.DOWNLOADED, pendingMarkAll = listOf("e1"))
        render(downloaded)

        compose.onNode(hasText("Mark as played") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(EpisodeListEvent.MarkAllConfirmed), events)

        events.clear()
        show(downloaded.copy(pendingMarkAll = null))
        show(downloaded)
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(EpisodeListEvent.MarkAllDismissed), events)
    }

    /**
     * `UI.adoc` §12.12, inside selection mode: the custom action TalkBack reaches must toggle, not
     * start a second selection — the same event a tap emits there.
     */
    @Test
    fun `in selection mode the accessibility action toggles the row`() {
        render(base.copy(selection = Selection(setOf("other"), allInFilter = 2)))

        val actions =
            compose
                .onNodeWithText(TITLE)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Toggle selection"), actions.map { it.label })

        actions.single().action()

        assertEquals(listOf(EpisodeListEvent.SelectionToggled("e1")), events)
    }

    @Test
    fun `undated episodes are grouped under Date unknown, not under a fabricated month`() {
        render(
            base.copy(
                content = EpisodeListUiState.Content.Episodes(listOf(row.copy(publishedAt = null))),
                sections = listOf(MonthSection(label = null, firstIndex = 0, count = 1)),
            ),
        )

        compose.onNodeWithText("Date unknown").assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Warum Hamburg immer regnet"
    }
}
