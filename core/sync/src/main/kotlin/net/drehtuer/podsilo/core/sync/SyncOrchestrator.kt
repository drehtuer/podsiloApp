// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.SyncOutcome
import net.drehtuer.podsilo.core.model.SyncState
import net.drehtuer.podsilo.core.model.episodeKey
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.GpodderException
import net.drehtuer.podsilo.core.model.port.GpodderFailure
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import java.io.IOException
import java.time.Clock

/**
 * How far back the `since` cursor is rewound on every pull, in seconds — **one day**.
 *
 * The cursor compares two different clocks and nothing can make them the same one. The server
 * selects `WHERE timestamp_epoch > :since` on the **client-authored** timestamp inside each action,
 * while the `timestamp` it hands back — the value stored as the next `since` — is the **server's own
 * wall clock**. An action authored before our last pass is therefore invisible to us permanently,
 * with no error and no gap anyone could notice.
 *
 * That is measured, not theorised. Against the author's instance on 2026-08-13, actions written by
 * the Nextcloud web client came back **6 980 seconds ahead** of the server's clock, because it emits
 * local time with no offset and the server parses it as UTC. Ahead is the survivable direction —
 * those arrive, repeatedly, for two hours. A client whose clock runs *behind* the server's, or one
 * in a timezone west of UTC, lands in the invisible half.
 *
 * A day of overlap costs one re-read of at most a day's actions per pass; reconciliation is
 * idempotent, so re-delivered actions produce no writes. A missed action costs a re-download of an
 * episode the user already handled, which is the one failure CLAUDE.md §11 calls the app's central
 * job to prevent. The asymmetry is the whole argument.
 *
 * **Not** computed from local device time: CLAUDE.md §11 forbids that outright, and it would make
 * clock skew the cure for clock skew.
 */
private const val CURSOR_OVERLAP_SECONDS = 24L * 60 * 60

/** Never below zero — `since = 0` already means "everything", and a negative would be nonsense. */
private fun Long.rewound(): Long = (this - CURSOR_OVERLAP_SECONDS).coerceAtLeast(0)

/** `since = 0` — every action the server has ever stored. Only ever a button press. */
private const val FULL_HISTORY = 0L

/**
 * Actions per POST. Deliberately well under anything the server is likely to refuse: the body is a
 * few hundred bytes per action, so this is tens of kilobytes rather than the megabytes an unchunked
 * push of a full ledger would send.
 */
private const val MAX_ACTIONS_PER_REQUEST = 200

/**
 * Groups rows so that no request carries more than [limit] actions, without splitting a row across
 * two requests — a row is marked synced as a unit, so its actions have to succeed as one.
 */
private fun <T> List<Pair<T, List<EpisodeAction>>>.chunkedByActionCount(
    limit: Int,
): List<List<Pair<T, List<EpisodeAction>>>> {
    val chunks = mutableListOf<List<Pair<T, List<EpisodeAction>>>>()
    var current = mutableListOf<Pair<T, List<EpisodeAction>>>()
    var count = 0
    for (entry in this) {
        if (current.isNotEmpty() && count + entry.second.size > limit) {
            chunks += current
            current = mutableListOf()
            count = 0
        }
        current += entry
        count += entry.second.size
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

/** `actionedAt` is millis; every timestamp the GPodder API compares is seconds. */
private const val MILLIS_PER_SECOND = 1000L

/**
 * The outcome of one outbox drain: the failure that stopped it, if any, and **the rows the server
 * answered 2xx for**.
 *
 * The second half exists because a 2xx is not proof the server kept anything — see
 * [SyncOrchestrator.reportActionsTheServerDidNotKeep], which is the only caller that needs it.
 */
private data class PushResult(
    val failure: SyncOutcome?,
    val accepted: List<EpisodeLedgerRow>,
)

/**
 * The one place a [GpodderFailure] becomes words a person reads. Short noun phrases rather than
 * sentences, so the two contexts that need them — a failed pass and a failed push — can each supply
 * their own frame instead of duplicating a message per failure per caller.
 *
 * Neither half ever contains a URL or a header: `UI.adoc` §11 puts the technical detail in the
 * entry's collapsed half, and that half is the exception's own message.
 */
private fun GpodderFailure.reason(): String =
    when (this) {
        GpodderFailure.UNAUTHORIZED -> "Nextcloud rejected the stored app password"
        GpodderFailure.SERVER_ERROR -> "the server reported an error"
        GpodderFailure.REJECTED -> "the server refused the request"
        GpodderFailure.UNREACHABLE -> "the server could not be reached"
        GpodderFailure.TIMED_OUT -> "the server did not answer in time"
        GpodderFailure.MALFORMED -> "the server's answer could not be read"
    }

/** The [reason] of a failure that may not be a [GpodderException] at all — `null` when it is not one. */
private fun Throwable.reasonOrNull(): String? = (this as? GpodderException)?.failure?.reason()

/** An untyped failure is assumed transient, which is what this class assumed about everything before. */
private fun Throwable.retryable(): Boolean = (this as? GpodderException)?.failure?.retryable ?: true

/**
 * S8's headline for a whole failed pass. [GpodderFailure.UNAUTHORIZED] gets its own sentence rather
 * than the shared frame, because it is the only failure here the user can do something about, and
 * naming the *something* is the difference between an error and an instruction.
 */
private fun GpodderException.plainMessage(): String =
    if (failure == GpodderFailure.UNAUTHORIZED) {
        "Nextcloud rejected the stored app password. Connect the account again in Settings."
    } else {
        "Sync with Nextcloud failed: ${failure.reason()}."
    }

/**
 * Runs one full sync pass in the exact order CLAUDE.md section 5 mandates: pull subscriptions
 * (full) -> push unsynced ledger rows -> pull episode actions since last timestamp -> reconcile ->
 * persist new timestamps. See `architecture.adoc` section 6 for the sequence diagram this
 * mirrors.
 *
 * Depends only on `:core:model` ports (never Room or Retrofit directly -- `architecture.adoc`
 * section 2's ports-and-adapters rule), so it's constructed here with plain interfaces and tested
 * with hand-written in-memory fakes, not a real database or `MockWebServer`.
 *
 * **Failure classification comes from the port, not from this class guessing.** Every
 * [GpodderClient] method returns a `Result` whose failure is a [GpodderException], so
 * [GpodderFailure.retryable] decides `Retry` vs `Failure` and [GpodderFailure.UNAUTHORIZED] is what
 * files an entry under [LogCategory.AUTH]. The remaining `IOException` / `RuntimeException` branches
 * in [guarded] are the net for everything that is *not* the GPodder client -- a repository write, a
 * bug -- and no longer carry the whole classification on their own.
 */
class SyncOrchestrator(
    private val feedRepository: FeedRepository,
    private val episodeLedgerRepository: EpisodeLedgerRepository,
    private val syncStateRepository: SyncStateRepository,
    private val gpodderClient: GpodderClient,
    private val logRepository: LogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun sync(): SyncOutcome =
        guarded {
            pullSubscriptions()
            val push = pushUnsyncedLedgerRows()
            if (push.failure != null) {
                push.failure
            } else {
                // The pull is handed what the push just sent, because the pull's own answer is what
                // proves the server kept it -- see [reportActionsTheServerDidNotKeep].
                pullAndReconcileEpisodeActions(pushed = push.accepted)
                SyncOutcome.Success
            }
        }

    /**
     * *Apply Nextcloud's state here* — the ordinary pull, run over the **whole** action log rather
     * than the delta since the cursor.
     *
     * That is the entire difference: `since = 0` and nothing else. It overrides no rule, because the
     * two the user might expect it to override are exactly the two they said it must not
     * (`decisions/0025`) — a decided episode is never re-opened, and a `DOWNLOADED` row is never
     * replaced by a remote action the server structurally cannot carry. So this can only ever
     * *shorten* the To-decide list: it decides episodes this device has not decided, and leaves
     * everything else alone.
     *
     * CLAUDE.md §5 warns that `since = 0` is unbounded and must not be the normal path. It is fine
     * once, on a button press, which is why it lives here and not in [sync].
     */
    suspend fun forcePull(): SyncOutcome =
        guarded {
            pullSubscriptions()
            pullAndReconcileEpisodeActions(since = FULL_HISTORY)
            SyncOutcome.Success
        }

    /**
     * *Send this device's state to Nextcloud* — re-posts **every** ledger row that maps to an action,
     * including rows already marked synced.
     *
     * The one operation in the app that deliberately writes to a shared, append-only log on purpose
     * rather than as a consequence, so the confirmation naming the count is not decoration
     * (`decisions/0025`). It is also the only way to repair state the server never received —
     * a download recorded before `decisions/0023` sent `DOWNLOAD` alone, which Nextcloud
     * discards, and its row is already `syncedToServer = true` so no ordinary pass will retry it.
     */
    suspend fun forcePush(): SyncOutcome =
        guarded {
            val rows = episodeLedgerRepository.observe(LedgerFilter(state = LedgerFilterState.ALL)).first()
            pushRows(rows).failure ?: SyncOutcome.Success
        }

    /**
     * The failure classification every pass shares.
     *
     * A [GpodderException] is caught **first and separately**: it is the only failure here that
     * already knows what it is, and the two things this method has to decide — retry or not, and
     * which S8 chip the entry files under — are both read off it rather than inferred. The
     * `IOException` and `RuntimeException` branches below stay as the net for everything that is not
     * the GPodder client.
     *
     * Every port method returns its failure rather than throwing it, so the `getOrThrow()` calls in
     * the private steps below are what turn that back into control flow. The unwrapping is
     * deliberate and local: one classification boundary for a sequence of steps that must stop at
     * the first failure, instead of threading a `Result` through each of them.
     */
    private suspend inline fun guarded(block: () -> SyncOutcome): SyncOutcome =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (gpodder: GpodderException) {
            record(message = gpodder.plainMessage(), failure = gpodder)
            val why = gpodder.message ?: "GPodder request failed"
            if (gpodder.failure.retryable) SyncOutcome.Retry(why) else SyncOutcome.Failure(why)
        } catch (network: IOException) {
            record(
                message = "Sync with Nextcloud failed: the server could not be reached.",
                failure = network,
            )
            SyncOutcome.Retry(network.message ?: "network error during sync")
        } catch (
            @Suppress("TooGenericExceptionCaught") unexpected: RuntimeException,
        ) {
            // Deliberately broad: this is the outermost boundary between "worth retrying" and "not"
            // for the failures no port typed for us -- a repository write, a bug -- not a swallowed
            // exception. Nothing that reaches here is retried, because nothing that reaches here is
            // known to be transient.
            record(
                message = "Sync with Nextcloud failed.",
                failure = unexpected,
            )
            SyncOutcome.Failure(unexpected.message ?: "unexpected error during sync")
        }

    /**
     * Every failure ends up in the error log (S8) as well as in the returned [SyncOutcome], because
     * the outcome reaches WorkManager and no further: a pass that fails on every attempt for four
     * hours used to leave no trace a user could see, which is why issue #60 had to be diagnosed by
     * reading source instead of by reading the app.
     *
     * Plain sentence first, technical half separate (`UI.adoc` §11). The exception's own message
     * is [detail] and never the headline — "unable to resolve host" is not a sentence the user asked
     * for. Credentials are stripped by the store, not here ([LogRepository.record]).
     *
     * The category is [failure]'s to determine: a [GpodderFailure.UNAUTHORIZED] is an
     * [LogCategory.AUTH] entry and everything else is [LogCategory.SYNC]. That separation is why the
     * port carries a typed failure at all — it is the difference between S8's *Account* chip telling
     * the user to sign in again and a wall of indistinguishable sync errors.
     */
    private suspend fun record(
        message: String,
        failure: Throwable,
    ) {
        logRepository.record(
            NewLogEntry(
                category =
                    if ((failure as? GpodderException)?.failure == GpodderFailure.UNAUTHORIZED) {
                        LogCategory.AUTH
                    } else {
                        LogCategory.SYNC
                    },
                message = message,
                detail = "${failure::class.simpleName}: ${failure.message}",
            ),
        )
    }

    /**
     * Full current set, not a delta -- CLAUDE.md section 5: a read-only follower doesn't need to
     * know what changed, only what currently is. Existing [Feed] rows are preserved as-is (keeping
     * their real title/`firstSeenAt` once known); only URLs new to this device get a placeholder
     * [Feed] with `title = url` and `firstSeenAt = now`.
     */
    private suspend fun pullSubscriptions() {
        val delta = gpodderClient.fetchSubscriptions(since = null).getOrThrow()
        val currentUrls = delta.add.toSet() - delta.remove.toSet()
        val existingByUrl = feedRepository.observeAll().first().associateBy { it.url }
        val now = clock.millis()

        val feeds =
            currentUrls.map { url ->
                existingByUrl[url] ?: Feed(
                    url = url,
                    title = url,
                    imageUrl = null,
                    firstSeenAt = now,
                    lastRefreshedAt = null,
                    httpEtag = null,
                    httpLastModified = null,
                )
            }
        feedRepository.replaceAll(feeds)
    }

    /** Drains the outbox; [PushResult.failure] is `null` when there was nothing to push or it succeeded. */
    private suspend fun pushUnsyncedLedgerRows(): PushResult = pushRows(episodeLedgerRepository.getUnsynced())

    /**
     * Posts [rows] in chunks, marking each chunk synced only on its own confirmed 2xx.
     *
     * **Chunked because a single POST is not a safe assumption about size.** A *mark all as played*
     * over the author's ~9,500 episodes, or a force push over every decided row, is thousands of
     * actions in one body — against a PHP endpoint whose `post_max_size` defaults to 8 MB and which
     * loops per action with an insert-then-update-on-conflict. The normal outbox drain had the same
     * latent problem and now shares the fix, which is the reason this lives here rather than in
     * [forcePush].
     *
     * A failure stops the run rather than continuing: the rows in earlier chunks stay marked (they
     * really were accepted) and everything after stays unsynced for the next pass. Partial progress
     * is the correct outcome of a partial success, and the outbox is what makes it safe to resume.
     */
    private suspend fun pushRows(rows: List<EpisodeLedgerRow>): PushResult {
        // One row can produce more than one action -- a completed download emits both `DOWNLOAD` and
        // `PLAY` (`decisions/0023`) -- so the row and its actions are kept paired: the actions
        // are what gets posted, the rows are what gets marked synced.
        val outbox =
            rows
                .map { row -> row to row.toOutboundActions() }
                .filter { (_, actions) -> actions.isNotEmpty() }
        var remaining = outbox.size
        var failure: Throwable? = null
        val accepted = mutableListOf<EpisodeLedgerRow>()
        for (chunk in outbox.chunkedByActionCount(MAX_ACTIONS_PER_REQUEST)) {
            failure = gpodderClient.postEpisodeActions(chunk.flatMap { it.second }).exceptionOrNull()
            if (failure != null) break
            episodeLedgerRepository.markSynced(chunk.map { it.first.episodeKey })
            accepted += chunk.map { it.first }
            remaining -= chunk.size
        }

        val outcome =
            failure?.let {
                // Named separately from the generic sync failure because the reassurance is the
                // point: nothing was lost, the remaining rows are still unsynced, and the next pass
                // sends them. The cause is still named, so an expired app password does not read as
                // a network blip.
                record(
                    message =
                        buildString {
                            append("$remaining decision(s) could not be sent to Nextcloud")
                            it.reasonOrNull()?.let { cause -> append(": ").append(cause) }
                            append(". They are kept here and will be sent again.")
                        },
                    failure = it,
                )
                val why = it.message ?: "failed to push episode actions"
                // The rows survive either way -- `syncedToServer` is still false. What differs is
                // whether WorkManager should keep asking: a revoked app password will keep being
                // revoked, and backing off against it just delays the log entry that explains it.
                if (it.retryable()) SyncOutcome.Retry(why) else SyncOutcome.Failure(why)
            }
        return PushResult(failure = outcome, accepted = accepted)
    }

    private suspend fun pullAndReconcileEpisodeActions(
        since: Long? = null,
        pushed: List<EpisodeLedgerRow> = emptyList(),
    ) {
        val syncState = syncStateRepository.get()
        val askedSince = since ?: syncState.lastEpisodeActionSyncTs.rewound()
        val page = gpodderClient.fetchEpisodeActions(since = askedSince).getOrThrow()
        val localLedger =
            episodeLedgerRepository
                .observe(LedgerFilter(state = LedgerFilterState.ALL))
                .first()
                .associateBy { it.episodeKey }

        reconcile(localLedger, page.actions, clock).forEach { row -> episodeLedgerRepository.upsert(row) }
        syncStateRepository.save(SyncState(page.timestamp, syncState.deviceId))
        // Last, so a write here cannot cost the pass state that is already correct.
        reportActionsTheServerDidNotKeep(pushed, page.actions, askedSince)
    }

    /**
     * **A 2xx from `episode_action/create` is not proof the server kept anything**, and this is what
     * notices when it did not.
     *
     * `nextcloud-gpodder` stores an action's `podcast`, `episode` and `guid` in fixed-width columns
     * — 500 characters as of 3.17.0 — and an action whose values do not fit is *silently
     * discarded*: `EpisodeActionSaver::saveEpisodeActions` catches the DbalException the oversized
     * insert raises, asks whether it was a unique-constraint violation, and falls out of the catch
     * with no rethrow and no log when it was not. `EpisodeActionController::create` then answers
     * `200 {"timestamp": ...}` whatever happened. So this device marks the row synced, the server
     * holds nothing, and the episode stays new in RePod and AntennaPod for ever — which is the one
     * failure CLAUDE.md section 11 calls the app's central job to prevent, arriving with no error
     * anywhere to read. Found against the author's own instance on 2026-09-13, on Supercast feeds
     * whose per-subscriber signed enclosure URLs run 516–517 characters
     * (`thrillfall/nextcloud-gpodder#81`, accepted upstream in 2022 and never fixed).
     *
     * **The proof costs no extra request, and that is the reason it is here rather than beside the
     * push.** The pull that follows in the same pass selects `WHERE timestamp_epoch > :since` on the
     * *client-authored* timestamp inside each action — the very value this device put there. So
     * for our own rows the comparison is between two numbers in the same clock, and any row we sent
     * whose `actionedAt` is later than the `since` we asked for **must** come back if the server
     * kept it. One that does not come back was dropped.
     *
     * The asymmetry is deliberate in the safe direction: a device whose clock runs behind the
     * server's simply fails the `askedSince` test and is not checked, so skew can only ever silence
     * this, never make it cry wolf. Rows are **not** un-marked — re-sending the same oversized URL
     * fails identically for ever — so the entry names the repair instead.
     */
    private suspend fun reportActionsTheServerDidNotKeep(
        pushed: List<EpisodeLedgerRow>,
        returned: List<EpisodeAction>,
        askedSince: Long,
    ) {
        if (pushed.isEmpty()) return
        val returnedKeys = returned.mapTo(mutableSetOf()) { episodeKey(it.guid, it.episode) }
        val dropped =
            pushed.filter { row ->
                row.actionedAt / MILLIS_PER_SECOND > askedSince && row.episodeKey !in returnedKeys
            }

        // Grouped by feed, because the cause is a property of the feed rather than of the episode:
        // one podcast's URLs are all too long or none of them are, and 22 identical rows would bury
        // the log the entry is meant to explain.
        dropped.groupBy { it.feedUrl }.forEach { (feedUrl, rows) ->
            logRepository.record(
                NewLogEntry(
                    category = LogCategory.SYNC,
                    feedUrl = feedUrl,
                    message =
                        "Nextcloud accepted ${rows.size} decision(s) for this podcast but did not " +
                            "keep them. They hold on this device; the episodes stay new in your " +
                            "other clients.",
                    // No URL, per `UI.adoc` section 11 -- and these are the feeds whose enclosure
                    // URLs carry a subscriber token, which is not a thing to write into a log.
                    detail =
                        "Not returned by the pull that followed the push. The known cause is " +
                            "nextcloud-gpodder discarding an episode action whose URLs exceed its " +
                            "fixed-width columns while still answering 200 " +
                            "(thrillfall/nextcloud-gpodder#81); longest enclosure URL here is " +
                            "${rows.maxOf { it.enclosureUrl.length }} characters. Once the server " +
                            "can store them, re-send with Settings > Send this device's state to " +
                            "Nextcloud.",
                ),
            )
        }
    }
}
