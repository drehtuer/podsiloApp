// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.ResolvedName
import net.drehtuer.podsilo.core.naming.DefaultNamingTemplateEngine
import net.drehtuer.podsilo.core.naming.TitleCleanupRule
import net.drehtuer.podsilo.core.naming.applyCleanupRules
import net.drehtuer.podsilo.core.naming.guidShort
import net.drehtuer.podsilo.core.naming.nextAvailableName
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.regex.PatternSyntaxException

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/** What one run of the pipeline in `architecture.adoc` §11 produced. */
sealed interface DownloadOutcome {
    /**
     * The file is in the user's folder under [fileName] (extension included) — the name to persist
     * as `EpisodeLedgerRow.writtenFileName` so a retry reuses it. [tagOutcome] is informational:
     * a tag failure never downgrades a delivery (CLAUDE.md §6).
     */
    data class Delivered(
        val fileName: String,
        val tagOutcome: TagWriteOutcome,
    ) : DownloadOutcome

    /**
     * @property retryable `true` for transient conditions worth handing back to WorkManager's
     *   backoff (network, 5xx, 408/429); `false` where retrying cannot help until the user does
     *   something — a 404, a revoked folder grant, a full disk, an unparseable cleanup rule.
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
        /**
         * Classified here, where the failure is understood, and carried into the ledger so a screen
         * never has to parse [reason] to decide between *Retry* and *Choose folder*
         * (`UI.adoc` §12.11).
         */
        val cause: ErrorCause,
    ) : DownloadOutcome

    /**
     * The user asked to download this episode again, and **its own previously written file is still
     * in the folder** — so nothing was fetched (`decisions/0012` §4).
     *
     * Informational, not a failure: the ledger returns to `DOWNLOADED` unchanged, the UI says
     * *"Already in your folder — <name>"*, and nothing is written to the error log. Aborting rather
     * than overwriting is deliberate — the file may be mid-playback in the user's player, and a
     * suffixed second copy is exactly what `writtenFileName` exists to prevent.
     */
    data class AlreadyPresent(
        val fileName: String,
    ) : DownloadOutcome
}

/**
 * One download, as a value — the same grouping idiom the pipeline's internal `Delivery` uses, so
 * the entry point reads as "download this request" rather than as a six-item parameter list.
 *
 * @property previousFileName the name a previous successful delivery recorded in the ledger.
 *   Reused verbatim so a retry overwrites its own file instead of creating a second copy
 *   (CLAUDE.md §6). Never derived from what is on disk.
 * @property userRequested set **only** from a UI triage event (`decisions/0012`). It is what
 *   permits re-downloading a terminal row at all, and the only thing that enables the pre-flight
 *   duplicate check below.
 */
data class DownloadRequest(
    val feed: Feed,
    val episode: Episode,
    val naming: NamingSettings,
    val previousFileName: String? = null,
    val userRequested: Boolean = false,
)

/**
 * Sequences the whole download pipeline CLAUDE.md §6 mandates:
 * `download to app cache → verify → resolve name → rewrite tags → copy into the SAF tree → delete cache`.
 *
 * Nothing here touches an Android API: the SAF half sits behind [DownloadTarget]
 * (`architecture.adoc` §11) and the ledger writes belong to [DownloadWorker], which is what makes
 * this class — the part with all the branching — testable without an emulator.
 *
 * It contains **no** string-sanitisation logic of its own; every naming decision, including
 * collision suffixing, comes from `:core:naming` (`architecture.adoc` §11).
 *
 * @property cacheDir app-private scratch space. One in-flight download at a time is the budget
 *   CLAUDE.md §6 asks for; the partial file is deliberately left behind on a failed or cancelled
 *   *download* so the next attempt resumes, and cleaned up once the bytes are safely delivered.
 */
class EpisodeDownloader(
    private val enclosureDownloader: EnclosureDownloader,
    private val audioTagWriter: AudioTagWriter,
    private val downloadTarget: DownloadTarget,
    private val cacheDir: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    /**
     * `null` disables artwork embedding entirely — which is what the JVM tests use, since fetching
     * a cover is network I/O and CLAUDE.md §7 requires tests to be offline.
     */
    private val artworkFetcher: ArtworkFetcher? = null,
) {
    /**
     * @param previousFileName the name a previous successful delivery recorded in the ledger.
     *   Reused verbatim so a retry overwrites its own file instead of creating a second copy
     *   (CLAUDE.md §6). Never derived from what is on disk — file presence is not an input to any
     *   decision here (§11).
     *
     * `@Suppress("ReturnCount")`: three exits, each a distinct outcome of a linear pipeline —
     * unusable settings, a fetch that didn't complete, and the delivery result. Collapsing them
     * would need nesting that reads worse than the sequence does.
     */
    @Suppress("ReturnCount")
    suspend fun download(
        request: DownloadRequest,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        // Named, not destructured: five positional bindings would silently change meaning if the
        // fields of DownloadRequest were ever reordered, and two of them are same-typed.
        val feed = request.feed
        val episode = request.episode
        val naming = request.naming
        val previousFileName = request.previousFileName
        // The one licensed existence check in the app (decisions/0012 §4). It runs *only*
        // because the user asked for this specific file again, and only when this episode already
        // wrote one. It never decides whether an episode is new or whether it was handled — that
        // stays the ledger, unconditionally (CLAUDE.md §11). A first-time download performs no
        // existence check at all.
        if (request.userRequested && previousFileName != null) {
            val folder = resolveFolder(feed, episode, naming)
            if (folder != null &&
                downloadTarget
                    .existingNames(folder)
                    .getOrDefault(emptySet())
                    .contains(previousFileName)
            ) {
                return DownloadOutcome.AlreadyPresent(previousFileName)
            }
        }
        // Compiled once, up front: a user-entered pattern that doesn't compile is a settings bug the
        // author has to fix, so it surfaces as a visible non-retryable error rather than being
        // silently dropped or blowing up halfway through the pipeline.
        val cleanupRules =
            try {
                naming.compiledCleanupRules()
            } catch (invalidRule: PatternSyntaxException) {
                val reason = invalidRule.message ?: "invalid title cleanup rule"
                return DownloadOutcome.Failed(
                    "naming settings contain an invalid rule: $reason",
                    retryable = false,
                    cause = ErrorCause.UNKNOWN,
                )
            }

        val cacheFile = File(cacheDir, "${guidShort(episode.episodeKey)}.partial")
        val result = enclosureDownloader.download(episode.enclosureUrl, cacheFile, onProgress)
        val fetched = result as? EnclosureDownloadResult.Completed ?: return result.toFailure()

        return try {
            deliver(
                Delivery(
                    feed = feed,
                    episode = episode,
                    naming = naming,
                    cleanupRules = cleanupRules,
                    previousFileName = previousFileName,
                    fetched = fetched,
                ),
            )
        } finally {
            // Past this point the bytes are either in the user's folder or unrecoverable by a
            // resume anyway, so the scratch copies have no further use. Prefix-matched because the
            // file gets renamed to carry its real extension before tagging (see renameForTagging).
            val prefix = guidShort(episode.episodeKey)
            cacheDir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    /**
     * The folder this episode would be delivered into, resolved from the naming templates alone —
     * no network, no bytes. Used by the duplicate guard, which has to know where to look *before*
     * deciding whether to fetch anything.
     *
     * A template the engine cannot resolve yields `null`, which the guard reads as "cannot tell" and
     * proceeds with the download: refusing to re-download because we could not check would be worse
     * than an extra fetch.
     */
    private fun resolveFolder(
        feed: Feed,
        episode: Episode,
        naming: NamingSettings,
    ): String? =
        runCatching {
            DefaultNamingTemplateEngine(
                zoneId = zoneId,
                titleCleanupRules = naming.compiledCleanupRules(),
                transliterate = naming.transliterate,
            ).resolve(
                feed = feed,
                episode = episode,
                folderTemplate = naming.folderTemplate,
                fileTemplate = naming.fileTemplate,
                contentType = null,
            ).folder
        }.getOrNull()

    /** Everything one delivery needs, grouped so the steps below read as a pipeline, not a parameter list. */
    private data class Delivery(
        val feed: Feed,
        val episode: Episode,
        val naming: NamingSettings,
        val cleanupRules: List<TitleCleanupRule>,
        val previousFileName: String?,
        val fetched: EnclosureDownloadResult.Completed,
    )

    private suspend fun deliver(delivery: Delivery): DownloadOutcome {
        val engine =
            DefaultNamingTemplateEngine(
                zoneId = zoneId,
                titleCleanupRules = delivery.cleanupRules,
                transliterate = delivery.naming.transliterate,
            )
        val resolved =
            engine.resolve(
                feed = delivery.feed,
                episode = delivery.episode,
                folderTemplate = delivery.naming.folderTemplate,
                fileTemplate = delivery.naming.fileTemplate,
                contentType = delivery.fetched.contentType,
            )

        // Best-effort, and deliberately before the copy: an untagged file must never reach the
        // user's folder, but a failed tag write must never lose the download (CLAUDE.md §6).
        val taggable = renameForTagging(delivery.fetched.file, delivery.episode, resolved.extension)
        val tagData = tagDataFor(delivery.feed, delivery.episode, delivery.cleanupRules)
        val tagOutcome = audioTagWriter.writeTags(taggable, tagData)

        val fileName =
            delivery.previousFileName ?: uniqueFileName(resolved).getOrElse { failure ->
                return DownloadOutcome.Failed(
                    failure.message ?: "download folder unavailable",
                    retryable = false,
                    cause = ErrorCause.FOLDER_UNAVAILABLE,
                )
            }

        return downloadTarget.deliver(resolved.folder, fileName, taggable).fold(
            onSuccess = { DownloadOutcome.Delivered(fileName, tagOutcome) },
            onFailure = { failure ->
                DownloadOutcome.Failed(
                    failure.message ?: "could not write to the download folder",
                    retryable = false,
                    cause = ErrorCause.FOLDER_UNAVAILABLE,
                )
            },
        )
    }

    /**
     * jaudiotagger chooses its reader from the **file extension**, so a `.partial` scratch file can
     * never be tagged — it reports "no reader associated with this extension" and every download
     * would silently arrive untagged. Renaming to the extension the file is about to be delivered
     * under is what makes the tagging step work at all. The download itself keeps the stable
     * `.partial` name so a resume always knows what to look for.
     *
     * A failed rename is not fatal: tagging will fail on the original file, and CLAUDE.md §6 says a
     * tag failure must still deliver the audio.
     */
    private fun renameForTagging(
        downloaded: File,
        episode: Episode,
        extension: String,
    ): File {
        val taggable = File(cacheDir, "${guidShort(episode.episodeKey)}.$extension")
        if (taggable == downloaded) return downloaded
        val renamed = (!taggable.exists() || taggable.delete()) && downloaded.renameTo(taggable)
        return if (renamed) taggable else downloaded
    }

    /**
     * Collisions are suffixed ` (2)`, ` (3)`, … *before* the extension — daily shows genuinely
     * reuse episode titles (CLAUDE.md §6). Only names sharing this file's extension can collide,
     * since a differing extension already makes a different file name.
     */
    private suspend fun uniqueFileName(resolved: ResolvedName): Result<String> =
        downloadTarget.existingNames(resolved.folder).map { existing ->
            val suffix = ".${resolved.extension}"
            val takenBases = existing.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.toSet()
            nextAvailableName(resolved.fileNameWithoutExtension, takenBases) + suffix
        }

    private fun tagDataFor(
        feed: Feed,
        episode: Episode,
        cleanupRules: List<TitleCleanupRule>,
    ): AudioTagData =
        AudioTagData(
            title = applyCleanupRules(episode.title, cleanupRules),
            artist = feed.title,
            album = feed.title,
            // No track number: the filename already carries a sortable date, and a fabricated
            // track would show up as "Track 20260714" in players that display it verbatim.
            year =
                episode.pubDate?.let {
                    Instant
                        .ofEpochMilli(it)
                        .atZone(zoneId)
                        .year
                        .toString()
                },
            comment = episode.description,
            // The episode's own cover if the feed named one, else the podcast's. The writer only
            // uses this when the file carries no artwork of its own.
            artwork = artworkFetcher?.fetch(episode.imageUrl, feed.imageUrl),
        )
}

private fun NamingSettings.compiledCleanupRules(): List<TitleCleanupRule> =
    titleCleanupRules.map { TitleCleanupRule(Regex(it.pattern), it.replacement) }

/**
 * Maps a non-[EnclosureDownloadResult.Completed] fetch to a pipeline failure, deciding what is
 * worth another attempt: 5xx, 408 and 429 are the server saying "later", and a lost connection is
 * transient by definition. Every other 4xx (404 for a pulled episode, 403 for an expired signed
 * URL) and every disk problem will fail identically on retry, and belongs in the ledger's ERROR
 * state for the user to act on.
 */
private fun EnclosureDownloadResult.toFailure(): DownloadOutcome.Failed =
    when (this) {
        is EnclosureDownloadResult.Completed -> error("a completed download is not a failure")
        is EnclosureDownloadResult.HttpError ->
            DownloadOutcome.Failed(
                "HTTP $code $message".trim(),
                retryable =
                    code >= HTTP_SERVER_ERROR_FLOOR ||
                        code == HTTP_REQUEST_TIMEOUT ||
                        code == HTTP_TOO_MANY_REQUESTS,
                // A signed enclosure URL that has expired comes back 401/403, and telling the user
                // "the server said no" is more useful than "server error" when the fix is a re-sync.
                cause = if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) ErrorCause.AUTH else ErrorCause.SERVER,
            )
        is EnclosureDownloadResult.NetworkError ->
            DownloadOutcome.Failed(reason, retryable = true, cause = ErrorCause.NETWORK)
        // Not retryable, and the one failure here that is neither the server's fault nor the disk's:
        // the connection was refused by this device before it was made.
        is EnclosureDownloadResult.CleartextBlocked ->
            DownloadOutcome.Failed(reason, retryable = false, cause = ErrorCause.CLEARTEXT_BLOCKED)
        // A lost folder grant is raised as an exception and classified at the call site, so a write
        // failure reaching here is the disk itself.
        is EnclosureDownloadResult.WriteError ->
            DownloadOutcome.Failed(reason, retryable = false, cause = ErrorCause.DISK_FULL)
    }
