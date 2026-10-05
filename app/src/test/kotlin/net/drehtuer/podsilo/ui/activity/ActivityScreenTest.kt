// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.feature.episodes.DownloadProgress
import net.drehtuer.podsilo.feature.episodes.EpisodeUi
import net.drehtuer.podsilo.feature.episodes.FailureUi
import net.drehtuer.podsilo.feature.episodes.FolderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * S7's rendering. Two rules get most of the attention: it is **not a file manager** (no delete, no
 * open-file, no existence check), and a failure the user must fix offers the fix, never a Retry.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ActivityEvent>()
    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun episode(
        key: String = "e1",
        ledgerState: LedgerState? = null,
        progress: DownloadProgress? = null,
        failure: FailureUi? = null,
    ) = EpisodeUi(
        episodeKey = key,
        feedUrl = "https://example.org/feed.xml",
        feedTitle = "Der Podcast",
        title = "Die Elbe von unten",
        artworkUrl = null,
        publishedAt = null,
        duration = null,
        descriptionSnippet = "",
        ledgerState = ledgerState,
        progress = progress,
        lastError = failure,
    )

    private fun render(state: ActivityUiState) {
        compose.setContent {
            ActivityScreen(state = state, onEvent = { events += it }, onBack = {}, now = now)
        }
    }

    @Test
    fun `an idle app says so instead of showing empty group headings`() {
        render(ActivityUiState())

        compose.onNodeWithText("Nothing downloading, nothing failed.").assertIsDisplayed()
        compose.onAllNodes(hasText("DOWNLOADING")).assertCountEquals(0)
        compose.onAllNodes(hasText("FAILED")).assertCountEquals(0)
    }

    @Test
    fun `a downloading row with live progress draws the percentage`() {
        render(
            ActivityUiState(
                downloading =
                    listOf(
                        episode(ledgerState = LedgerState.DOWNLOADING, progress = DownloadProgress(620, 1_000)),
                    ),
            ),
        )

        compose.onNodeWithContentDescription("downloading, 62 percent").assertIsDisplayed()
    }

    @Test
    fun `a downloading row with no live progress says resuming, not zero percent`() {
        // After process death WorkManager's progress is gone (UI.adoc §B7).
        render(ActivityUiState(downloading = listOf(episode(ledgerState = LedgerState.DOWNLOADING))))

        compose.onNodeWithContentDescription("resuming").assertIsDisplayed()
    }

    @Test
    fun `a queued row says what it is waiting for`() {
        render(
            ActivityUiState(
                queued = listOf(QueuedUi(episode(ledgerState = LedgerState.QUEUED), WaitReason.WIFI)),
            ),
        )

        compose.onNodeWithText("waiting for Wi-Fi").assertIsDisplayed()
    }

    @Test
    fun `a folder failure offers Choose folder, never Retry`() {
        // architecture §11 again, on a third screen — reusing FailureUi.remedy is what keeps them agreeing.
        render(
            ActivityUiState(
                failed =
                    listOf(
                        episode(
                            ledgerState = LedgerState.ERROR,
                            failure = FailureUi(ErrorCause.FOLDER_UNAVAILABLE, "folder gone", 1, retryable = false),
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onAllNodes(hasText("Retry")).assertCountEquals(0)
    }

    @Test
    fun `a network failure does offer Retry, with the message verbatim`() {
        render(
            ActivityUiState(
                failed =
                    listOf(
                        episode(
                            ledgerState = LedgerState.ERROR,
                            failure = FailureUi(ErrorCause.NETWORK, "connection reset", 2, retryable = true),
                        ),
                    ),
            ),
        )

        compose.onNode(hasText("connection reset", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertTrue(events.contains(ActivityEvent.RetryClicked("e1")))
    }

    @Test
    fun `Sync now is disabled offline, with the reason on screen`() {
        render(
            ActivityUiState(
                sync = SyncUi(canSyncNow = false, blockedReason = BlockedReason.OFFLINE),
            ),
        )

        compose.onNodeWithText("Sync now").assertIsNotEnabled()
        compose.onNode(hasText("No network connection", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the sync line distinguishes nothing-to-do from things-stuck`() {
        val synced = SyncUi(lastSyncAt = now.minusSeconds(600))

        assertEquals("last 10 min ago", synced.line(now))
        assertEquals("last 10 min ago · 3 actions pending", synced.copy(outboxDepth = 3).line(now))
        assertEquals("never synced", SyncUi().line(now))
    }

    @Test
    fun `the wait reason names the blocker the user can act on first`() {
        // Reporting "waiting for Wi-Fi" while the real blocker is a missing folder sends the user
        // to the wrong screen.
        assertEquals(WaitReason.FOLDER, waitReason(FolderState.NOT_CHOSEN, online = true))
        assertEquals(WaitReason.NETWORK, waitReason(FolderState.GRANTED, online = false))
        assertEquals(WaitReason.WIFI, waitReason(FolderState.GRANTED, online = true))
    }

    /**
     * Recently downloaded shows what was written and nothing that would make S7 a file manager
     * (README: Podsilo does not delete files, open them, or check whether they still exist).
     *
     * *Clear list* empties the delivered list and **must not read as deleting anything**: the list
     * is projected from `DOWNLOADED` ledger rows, which are the record that stops an episode being
     * fetched again (CLAUDE.md §11) — so the label says "list", and the word "Delete" must stay
     * absent from this screen for the same reason it always has.
     */
    @Test
    fun `recently downloaded shows the file, offers Clear list, and nothing that makes it a file manager`() {
        render(
            ActivityUiState(
                recent =
                    listOf(
                        DeliveredUi(
                            fileName = "20260630_Hafen-Kran-Kaffee.mp3",
                            folderLabel = "Der Podcast",
                            episodeKey = "e1",
                            feedUrl = "https://example.org/feed.xml",
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("20260630_Hafen-Kran-Kaffee.mp3").assertIsDisplayed()
        compose.onAllNodes(hasText("Delete", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Open", substring = true)).assertCountEquals(0)

        compose.onNodeWithText("Clear list").performClick()

        assertTrue(events.contains(ActivityEvent.ClearDeliveredClicked))
    }

    /** Nothing to clear, nothing to offer — an empty list must not show a button that does nothing. */
    @Test
    fun `clear list is absent when nothing has been delivered`() {
        render(ActivityUiState())

        compose.onAllNodes(hasText("Clear list")).assertCountEquals(0)
    }

    /**
     * Issue #90. The group names the episode and what was done to it in the user's words — "Played",
     * not `SKIPPED` — because it is read by someone trying to recognise a row they swiped by mistake.
     */
    @Test
    fun `a recent action names the episode, the decision and its way back`() {
        render(
            ActivityUiState(
                history =
                    listOf(
                        ActionUi(
                            episodeKey = "e1",
                            feedUrl = "https://example.org/feed.xml",
                            episodeTitle = "Warum Hamburg immer regnet",
                            feedTitle = "Der Podcast",
                            state = LedgerState.SKIPPED,
                            actionedAt = now.minusSeconds(120),
                            canMarkAsUnplayed = true,
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("RECENT ACTIONS").assertIsDisplayed()
        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onNode(hasText("Played", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Mark as unplayed").performClick()

        assertEquals(listOf(ActivityEvent.MarkAsUnplayedClicked("e1")), events)
    }

    /** `HANDLED_REMOTELY` was not this device's decision, so the row must not claim it was. */
    @Test
    fun `an action handled elsewhere says so rather than claiming the user played it`() {
        render(
            ActivityUiState(
                history =
                    listOf(
                        ActionUi(
                            episodeKey = "e2",
                            feedUrl = "https://example.org/feed.xml",
                            episodeTitle = "Regenradar",
                            feedTitle = "Der Podcast",
                            state = LedgerState.HANDLED_REMOTELY,
                            actionedAt = now.minusSeconds(60),
                            canMarkAsUnplayed = true,
                        ),
                    ),
            ),
        )

        compose.onNode(hasText("Handled elsewhere", substring = true)).assertIsDisplayed()
    }
}
