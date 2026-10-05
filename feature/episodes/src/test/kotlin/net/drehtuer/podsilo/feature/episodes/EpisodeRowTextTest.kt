// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.ZoneOffset

/**
 * What a row may do and how it reads, as the pure functions S2, S3 and S7 all share
 * (`UI.adoc` §12.6). Table-driven, because the tables are what the design specifies — a single
 * state answered differently here is the row and the sheet disagreeing about one episode.
 */
class EpisodeRowTextTest {
    private fun ui(
        state: LedgerState? = null,
        failure: FailureUi? = null,
        duration: Duration? = null,
    ) = EpisodeUi(
        episodeKey = "e1",
        feedUrl = FEED_URL,
        feedTitle = "Der Podcast",
        title = "Warum Hamburg immer regnet",
        artworkUrl = null,
        publishedAt = null,
        duration = duration,
        descriptionSnippet = "",
        ledgerState = state,
        lastError = failure,
    )

    /** `UI.adoc` §12.6's table, one row per ledger state, for an episode that has audio. */
    @Test
    fun `every ledger state offers exactly the actions the design table lists`() {
        val expected =
            mapOf(
                null to setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_PLAYED),
                // decisions/0024: withdrawn, so it offers exactly what an undecided episode does.
                LedgerState.UNPLAYED to setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_PLAYED),
                LedgerState.QUEUED to setOf(EpisodeUiAction.CANCEL),
                LedgerState.DOWNLOADING to setOf(EpisodeUiAction.CANCEL),
                LedgerState.DOWNLOADED to
                    setOf(
                        EpisodeUiAction.DOWNLOAD_AGAIN,
                        EpisodeUiAction.MARK_AS_PLAYED,
                        EpisodeUiAction.MARK_AS_UNPLAYED,
                    ),
                // "Download anyway" — the user may override a decision made here or elsewhere.
                LedgerState.SKIPPED to setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_UNPLAYED),
                LedgerState.HANDLED_REMOTELY to setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_UNPLAYED),
                LedgerState.ERROR to setOf(EpisodeUiAction.RETRY, EpisodeUiAction.MARK_AS_PLAYED),
            )
        assertEquals("the table must cover every state", LedgerState.entries.size + 1, expected.size)

        expected.forEach { (state, actions) ->
            assertEquals("state=$state", actions, actionsFor(state, hasEnclosure = true, hasPage = false))
        }
    }

    /** Browsing is orthogonal to triage: a feed-supplied link must never change what can be decided. */
    @Test
    fun `a page link adds browse actions to every state, and never a triage one`() {
        (LedgerState.entries + null).forEach { state ->
            val withPage = actionsFor(state, hasEnclosure = true, hasPage = true)
            val without = actionsFor(state, hasEnclosure = true, hasPage = false)

            assertEquals(
                "state=$state",
                setOf(EpisodeUiAction.OPEN_IN_BROWSER, EpisodeUiAction.COPY_LINK),
                withPage - without,
            )
        }
    }

    /** `UI.adoc` §B14.3: no audio means the download affordance is absent, not present-and-failing. */
    @Test
    fun `an episode with no enclosure offers only browsing, whatever its state`() {
        (LedgerState.entries + null).forEach { state ->
            assertEquals(
                "state=$state",
                emptySet<EpisodeUiAction>(),
                actionsFor(state, hasEnclosure = false, hasPage = false),
            )
            assertEquals(
                "state=$state",
                setOf(EpisodeUiAction.OPEN_IN_BROWSER, EpisodeUiAction.COPY_LINK),
                actionsFor(state, hasEnclosure = false, hasPage = true),
            )
        }
    }

    /** `UI.adoc` §12.11: Retry is replaced by the action that can actually clear the failure. */
    @Test
    fun `Retry is labelled by the failure's remedy`() {
        val cases =
            mapOf(
                ErrorCause.NETWORK to "Retry",
                ErrorCause.UNKNOWN to "Retry",
                ErrorCause.FOLDER_UNAVAILABLE to "Choose folder",
                ErrorCause.DISK_FULL to "Free up space",
            )
        cases.forEach { (cause, label) ->
            val episode = ui(LedgerState.ERROR, FailureUi(cause, "x", attempts = 1, retryable = true))

            assertEquals("cause=$cause", label, EpisodeUiAction.RETRY.labelFor(episode))
        }
        // An ERROR row with no recorded failure has no remedy to offer, so it is an ordinary Retry.
        assertEquals("Retry", EpisodeUiAction.RETRY.labelFor(ui(LedgerState.ERROR)))
    }

    @Test
    fun `the overflow-only actions have no primary-button label`() {
        assertNull(EpisodeUiAction.OPEN_IN_BROWSER.labelFor(ui()))
        assertNull(EpisodeUiAction.COPY_LINK.labelFor(ui()))
        assertEquals("Cancel", EpisodeUiAction.CANCEL.labelFor(ui(LedgerState.QUEUED)))
        assertEquals("Mark as unplayed", EpisodeUiAction.MARK_AS_UNPLAYED.labelFor(ui(LedgerState.SKIPPED)))
    }

    @Test
    fun `each state reads as its own word, and undecided-looking states read as nothing`() {
        val expected =
            mapOf(
                null to null,
                // decisions/0024: an unplayed row reads exactly as an undecided one.
                LedgerState.UNPLAYED to null,
                // Progress, not a word, carries a running download.
                LedgerState.DOWNLOADING to null,
                LedgerState.QUEUED to "queued",
                LedgerState.DOWNLOADED to "downloaded",
                LedgerState.SKIPPED to "played",
                LedgerState.HANDLED_REMOTELY to "handled elsewhere",
                LedgerState.ERROR to "failed",
            )
        expected.forEach { (state, line) -> assertEquals("state=$state", line, ui(state).statusLine()) }
    }

    @Test
    fun `a failure's status line carries its message verbatim and the attempt count`() {
        val failure = FailureUi(ErrorCause.NETWORK, "connection reset", attempts = 3, retryable = true)
        val failed = ui(LedgerState.ERROR, failure)

        assertEquals("failed — connection reset (attempt 3)", failed.statusLine())
    }

    /** The badge sits *beside* the status line, so one without the other is a half-rendered state. */
    @Test
    fun `a row has a status badge exactly when it has a status line`() {
        (LedgerState.entries + null).forEach { state ->
            val row = ui(state)
            assertEquals("state=$state", row.statusLine() == null, row.statusIcon() == null)
        }
    }

    /** §18: a decision made on another client must not wear the tick of a download made here. */
    @Test
    fun `handled elsewhere does not wear the downloaded tick`() {
        assertNotEquals(ui(LedgerState.DOWNLOADED).statusIcon(), ui(LedgerState.HANDLED_REMOTELY).statusIcon())
    }

    @Test
    fun `durations of an hour or more read in hours and minutes`() {
        assertEquals("1 h 0 min", ui(duration = Duration.ofMinutes(60)).metaLine(ZoneOffset.UTC))
        assertEquals("2 h 5 min", ui(duration = Duration.ofMinutes(125)).metaLine(ZoneOffset.UTC))
        assertEquals("59 min", ui(duration = Duration.ofMinutes(59)).metaLine(ZoneOffset.UTC))
    }

    /** No percentage is ever invented: an unknown or zero total is "no figure", not 0 % or a crash. */
    @Test
    fun `progress without a usable total has no percentage`() {
        assertNull(DownloadProgress(bytesDownloaded = 500, totalBytes = null).percent)
        assertNull(DownloadProgress(bytesDownloaded = 500, totalBytes = 0).percent)
        assertEquals(50, DownloadProgress(bytesDownloaded = 500, totalBytes = 1_000).percent)
    }
}
