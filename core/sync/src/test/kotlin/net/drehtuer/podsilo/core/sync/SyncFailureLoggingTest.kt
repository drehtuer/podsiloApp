// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.port.GpodderException
import net.drehtuer.podsilo.core.model.port.GpodderFailure
import net.drehtuer.podsilo.core.model.port.LogCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A failed sync pass has to leave a trace the user can read. Before this, the only record of a
 * failure was the [SyncOutcome] handed to WorkManager, which reaches no screen — so a pass failing
 * on every attempt for four hours was indistinguishable from one that never ran (issue #60).
 *
 * These assert the *contract* of the entries, not their wording: a category, a plain sentence that
 * is not the exception's own text, and the technical half kept separate (`UI.adoc` §11).
 */
class SyncFailureLoggingTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC)
    private val log = RecordingLogRepository()

    private fun orchestrator(
        gpodderClient: FakeGpodderClient,
        ledgerRepository: FakeEpisodeLedgerRepository = FakeEpisodeLedgerRepository(),
    ) = SyncOrchestrator(
        FakeFeedRepository(),
        ledgerRepository,
        FakeSyncStateRepository(),
        gpodderClient,
        log,
        fixedClock,
    )

    @Test
    fun aSuccessfulPassRecordsNothing() =
        runBlocking {
            val outcome = orchestrator(FakeGpodderClient()).sync()

            assertEquals(SyncOutcome.Success, outcome)
            assertTrue("a failure log is not a journal (`UI.adoc` §11)", log.recorded.isEmpty())
        }

    @Test
    fun anUnreachableServerIsRecordedAsSync() =
        runBlocking {
            val failure = IOException("Unable to resolve host \"cloud.example.org\"")

            val outcome = orchestrator(FakeGpodderClient(subscriptionsFailure = failure)).sync()

            assertTrue(outcome is SyncOutcome.Retry)
            val entry = log.recorded.single()
            assertEquals(LogCategory.SYNC, entry.category)
            assertEquals("Sync with Nextcloud failed: the server could not be reached.", entry.message)
            assertTrue("the exception text belongs in the detail", entry.detail.orEmpty().contains("resolve host"))
        }

    @Test
    fun anUnexpectedFailureIsRecordedToo() =
        runBlocking {
            val failure = IllegalStateException("malformed response body")

            val outcome = orchestrator(FakeGpodderClient(episodeActionsFailure = failure)).sync()

            assertTrue(outcome is SyncOutcome.Failure)
            val entry = log.recorded.single()
            assertEquals(LogCategory.SYNC, entry.category)
            assertEquals("Sync with Nextcloud failed.", entry.message)
            assertTrue(entry.detail.orEmpty().contains("IllegalStateException"))
        }

    /**
     * The push failure gets its own sentence because the reassurance is the useful part: the rows
     * are still in the outbox, so nothing was lost. A test pins that they really are.
     */
    @Test
    fun aFailedPushSaysNothingWasLostAndLeavesTheRowsUnsynced() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository()
            ledger.upsert(skippedRow("guid-1"))
            ledger.upsert(skippedRow("guid-2"))

            val outcome =
                orchestrator(
                    FakeGpodderClient(postResult = Result.failure(IOException("HTTP 503"))),
                    ledger,
                ).sync()

            assertTrue(outcome is SyncOutcome.Retry)
            val entry = log.recorded.single()
            assertEquals(LogCategory.SYNC, entry.category)
            assertTrue("the count is what tells the user how much is waiting", entry.message.startsWith("2 decision"))
            assertTrue(entry.message.contains("will be sent again"))
            assertEquals("nothing may be marked synced without a 2xx", 2, ledger.getUnsynced().size)
        }

    // --- typed failures ----------------------------------------------------------------------
    //
    // The GPodder port returns a GpodderException carrying a GpodderFailure, and these assert the
    // two things this class reads off it. Before it was typed, a failed GET arrived as Retrofit's
    // HttpException — not an IOException — so an expired app password was reported as an unexpected
    // error under the SYNC chip, which is the one category S8's filter exists to separate it from.

    /**
     * Every [GpodderFailure], through a failed pass: whether it is retried is the port's verdict
     * ([GpodderFailure.retryable]), only [GpodderFailure.UNAUTHORIZED] files under the *Account* chip
     * with its own instruction, and each of the rest gets a reason of its own rather than one generic
     * "sync failed".
     */
    @Test
    fun everyTypedFailureIsClassifiedAndWorded() =
        runBlocking {
            val expectedMessages =
                mapOf(
                    GpodderFailure.UNAUTHORIZED to
                        "Nextcloud rejected the stored app password. Connect the account again in Settings.",
                    GpodderFailure.SERVER_ERROR to "Sync with Nextcloud failed: the server reported an error.",
                    GpodderFailure.REJECTED to "Sync with Nextcloud failed: the server refused the request.",
                    GpodderFailure.UNREACHABLE to "Sync with Nextcloud failed: the server could not be reached.",
                    GpodderFailure.TIMED_OUT to "Sync with Nextcloud failed: the server did not answer in time.",
                    GpodderFailure.MALFORMED to "Sync with Nextcloud failed: the server's answer could not be read.",
                )
            assertEquals("a new failure needs a row here", GpodderFailure.entries.toSet(), expectedMessages.keys)

            for ((failure, message) in expectedMessages) {
                log.clear()
                val exception = GpodderException(failure, "transport text for $failure")

                val outcome = orchestrator(FakeGpodderClient(subscriptionsFailure = exception)).sync()

                val expectedOutcome =
                    if (failure.retryable) {
                        SyncOutcome.Retry("transport text for $failure")
                    } else {
                        SyncOutcome.Failure("transport text for $failure")
                    }
                assertEquals("$failure", expectedOutcome, outcome)
                val entry = log.recorded.single()
                val category = if (failure == GpodderFailure.UNAUTHORIZED) LogCategory.AUTH else LogCategory.SYNC
                assertEquals("$failure", category, entry.category)
                assertEquals("$failure", message, entry.message)
                assertTrue("$failure", entry.detail.orEmpty().contains("transport text for $failure"))
            }
        }

    /**
     * Cancellation is not a failure. WorkManager stopping the worker must unwind the coroutine, not
     * be filed in S8 as a sync error and reported as a Failure that WorkManager would then record.
     */
    @Test
    fun cancellationPropagatesAndIsNotLogged() {
        val client = FakeGpodderClient(subscriptionsFailure = CancellationException("worker stopped"))

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator(client).sync() }
        }
        assertTrue(log.recorded.isEmpty())
    }

    /**
     * A push failure that is not a [GpodderException] -- the port's contract says it never happens,
     * which is why it is assumed transient: the rows are safe in the outbox, so asking again is
     * cheap, and the sentence names no cause it does not know.
     */
    @Test
    fun anUntypedPushFailureIsRetriedWithoutInventingACause() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository()
            ledger.upsert(skippedRow("guid-1"))

            val outcome =
                orchestrator(
                    FakeGpodderClient(postResult = Result.failure(IllegalStateException("boom"))),
                    ledger,
                ).sync()

            assertTrue(outcome is SyncOutcome.Retry)
            assertEquals(
                "1 decision(s) could not be sent to Nextcloud. They are kept here and will be sent again.",
                log.recorded.single().message,
            )
            assertEquals(1, ledger.getUnsynced().size)
        }

    /**
     * The push path reads the same failure, and has to: it is the one that runs on every triage
     * decision, so it is where a revoked password is most likely to be noticed first.
     */
    @Test
    fun aPushRefusedForAuthNamesTheCauseAndStopsRetrying() =
        runBlocking {
            val ledger = FakeEpisodeLedgerRepository()
            ledger.upsert(skippedRow("guid-1"))
            val failure = GpodderException(GpodderFailure.UNAUTHORIZED, "HTTP 401 Unauthorized", 401)

            val outcome = orchestrator(FakeGpodderClient(postResult = Result.failure(failure)), ledger).sync()

            assertTrue(outcome is SyncOutcome.Failure)
            val entry = log.recorded.single()
            assertEquals(LogCategory.AUTH, entry.category)
            assertTrue(
                "the cause is named, not just the count",
                entry.message.contains("rejected the stored app password"),
            )
            assertTrue("the reassurance survives", entry.message.contains("will be sent again"))
            assertEquals("nothing may be marked synced without a 2xx", 1, ledger.getUnsynced().size)
        }

    private fun skippedRow(episodeKey: String) =
        EpisodeLedgerRow(
            episodeKey = episodeKey,
            feedUrl = "https://example.com/feed.xml",
            enclosureUrl = "https://example.com/$episodeKey.mp3",
            state = LedgerState.SKIPPED,
            actionedAt = fixedClock.millis(),
            syncedToServer = false,
            attempts = 0,
            lastError = null,
            writtenFileName = null,
            durationSeconds = 1_800,
        )
}
