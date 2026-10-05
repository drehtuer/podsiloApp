// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * S3's rendering. The sheet reuses the row's action labels deliberately (`UI.adoc` §12.6), so
 * these assert the parts that are the *sheet's* own: the description arrives sanitised, the
 * browser row appears only when the feed supplied a link, and the delivered-file line is shown.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeDetailSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<EpisodeDetailEvent>()

    private fun ui(
        ledgerState: LedgerState? = null,
        failure: FailureUi? = null,
        pageUrl: String? = null,
    ) = EpisodeUi(
        episodeKey = "e1",
        feedUrl = FEED_URL,
        feedTitle = "Der Podcast",
        title = "Warum Hamburg immer regnet",
        artworkUrl = null,
        publishedAt = Instant.parse("2026-07-14T09:00:00Z"),
        duration = Duration.ofMinutes(48),
        descriptionSnippet = "Eine Folge über Regen",
        ledgerState = ledgerState,
        lastError = failure,
        episodePageUrl = pageUrl,
    )

    private fun render(
        episode: EpisodeUi = ui(),
        descriptionHtml: String = "<p>Eine Folge über <b>Regen</b></p>",
        deliveredTo: String? = null,
    ) {
        compose.setContent {
            EpisodeDetailSheet(
                state =
                    EpisodeDetailUiState(
                        episode = episode,
                        descriptionHtml = descriptionHtml,
                        deliveredTo = deliveredTo,
                    ),
                onEvent = { events += it },
                zone = ZoneOffset.UTC,
            )
        }
    }

    @Test
    fun `the header names the episode, its podcast, date and duration`() {
        render()

        compose.onNodeWithText("Warum Hamburg immer regnet").assertIsDisplayed()
        compose.onNode(hasText("Der Podcast", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("48 min", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `the description is rendered as text, with its markup stripped`() {
        // Sanitised at render, never at write (architecture §4). The tags must not reach the screen.
        render(descriptionHtml = "<p>Eine Folge über <b>Regen</b></p><script>alert(1)</script>")

        compose.onNode(hasText("Eine Folge über Regen", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("<b>", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("alert", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a feed with no item link simply has no browser row`() {
        // Never synthesised from the enclosure URL, which points at an audio file rather than a
        // page — so the absent row beats a dead tap (UI.adoc §6).
        render(episode = ui(pageUrl = null))

        compose.onAllNodes(hasText("Open episode page", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `the browser row appears when the feed supplied a link, and does not decide anything`() {
        render(episode = ui(pageUrl = "https://example.org/episodes/1"))

        compose.onNode(hasText("Open episode page in browser")).performClick()

        assertEquals(listOf(EpisodeDetailEvent.OpenInBrowserClicked), events)
    }

    @Test
    fun `a downloaded episode says where the file went`() {
        render(
            episode = ui(ledgerState = LedgerState.DOWNLOADED),
            deliveredTo = "Podcasts/20260714_Warum-Hamburg-immer-regnet.mp3",
        )

        compose.onNode(hasText("20260714_Warum", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Download again").assertIsDisplayed()
    }

    @Test
    fun `the sheet opens for a skipped episode and offers download anyway`() {
        render(episode = ui(ledgerState = LedgerState.SKIPPED))

        compose.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun `a lost folder grant offers Choose folder here too, never Retry`() {
        // The same guarantee as the row (`architecture.adoc` §11) — reusing `labelFor` is what makes the
        // two impossible to drift apart, and this test is what proves the reuse is wired up.
        render(
            episode =
                ui(
                    ledgerState = LedgerState.ERROR,
                    failure =
                        FailureUi(
                            cause = ErrorCause.FOLDER_UNAVAILABLE,
                            message = "the download folder is no longer accessible",
                            attempts = 1,
                            retryable = false,
                        ),
                ),
        )

        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onAllNodes(hasText("Retry", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a failed episode offers a way to the error log`() {
        render(
            episode =
                ui(
                    ledgerState = LedgerState.ERROR,
                    failure =
                        FailureUi(ErrorCause.NETWORK, "connection reset", attempts = 2, retryable = true),
                ),
        )

        compose.onNodeWithText("Error details").performClick()

        assertTrue(events.contains(EpisodeDetailEvent.ErrorDetailsClicked))
    }

    @Test
    fun `deciding emits a triage event for the sheet's own episode`() {
        render()

        compose.onNodeWithText("Mark as played").performClick()

        assertEquals(
            listOf(EpisodeDetailEvent.Triage(EpisodeUiAction.MARK_AS_PLAYED)),
            events,
        )
    }

    /**
     * S3 is a **full screen**, not a bottom sheet.
     *
     * It was a `ModalBottomSheet` inside a full-screen navigation destination — so a downward drag
     * dismissed the sheet and revealed the empty destination behind it, which is the white screen the
     * author reported. A back affordance is the only way out now, and there is nothing to pull.
     */
    @Test
    fun `the detail screen's way out is a back affordance that emits Dismissed`() {
        render()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()

        // The host pops the backstack on this; there is no sheet left to drag away.
        assertEquals(listOf(EpisodeDetailEvent.Dismissed), events)
    }
}
