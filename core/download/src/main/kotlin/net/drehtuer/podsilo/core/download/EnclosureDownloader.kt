// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.UnknownServiceException

private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val COPY_BUFFER_BYTES = 64 * 1024

/** Outcome of fetching one episode enclosure into the app cache (step A of `architecture.adoc` §11). */
sealed interface EnclosureDownloadResult {
    /**
     * The complete file is on disk. [contentType] is the response header verbatim (may be `null`);
     * extension resolution is `:core:naming`'s job, not this class's.
     */
    data class Completed(
        val file: File,
        val contentType: String?,
    ) : EnclosureDownloadResult

    /** Non-2xx. [code] lets the caller distinguish a permanent 404 from a retry-worthy 5xx. */
    data class HttpError(
        val code: Int,
        val message: String,
    ) : EnclosureDownloadResult

    /** DNS/connect/timeout/TLS, or a truncated body — transient, and the partial file is kept for resume. */
    data class NetworkError(
        val reason: String,
    ) : EnclosureDownloadResult

    /**
     * Android refused to open the connection because the enclosure is served over plain `http://`.
     *
     * Its own case rather than a [NetworkError] because it is **permanent and nothing to do with the
     * network**: the request never left the device, and every retry will be refused identically.
     * Reported as a network error it retried on a backoff for ever and read as "the server did not
     * respond", which is the failure `backlog.adoc` predicted would arrive with no obvious cause.
     *
     * Podsilo does **not** upgrade the URL to `https://` to get around this — see the note on
     * `ErrorCause.CLEARTEXT_BLOCKED`.
     */
    data class CleartextBlocked(
        val reason: String,
    ) : EnclosureDownloadResult

    /** The cache file couldn't be written: disk full, or the cache directory is gone. Not a network problem. */
    data class WriteError(
        val reason: String,
    ) : EnclosureDownloadResult
}

/** Thrown internally so a failed *write* can be told apart from a failed *read* — both are [IOException]s. */
private class DiskWriteException(
    cause: IOException,
) : IOException(cause.message, cause)

/**
 * Downloads an episode enclosure into a real [File] in the app cache — never straight into the
 * user's SAF folder, because the tagging step needs a `java.io.File` and because a partial or
 * untagged file must never appear where the user's player can see it (CLAUDE.md §6/§11).
 *
 * **Resumable.** A partial file left by a cancelled or failed attempt is continued with a `Range`
 * request. Servers that ignore `Range` (answering 200 with the whole body) and servers that reject
 * it (416, typically because the file changed underneath us) are both handled by restarting from
 * zero rather than corrupting the file by appending to a stale prefix.
 *
 * Failures are [EnclosureDownloadResult] values, not exceptions (CLAUDE.md §8) — a podcast CDN
 * being down is entirely expected. Cancellation is the one thing that does propagate: WorkManager
 * cancels the coroutine, and the half-written file is deliberately left behind for the next attempt
 * to resume from.
 */
class EnclosureDownloader(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    /**
     * @param onProgress called with (bytes on disk, total bytes if the server disclosed one) as the
     *   copy proceeds — drives the foreground notification. Never persisted: byte-level progress
     *   lives in the worker and the partial file, not the database (`architecture.adoc` §4).
     */
    suspend fun download(
        enclosureUrl: String,
        destination: File,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): EnclosureDownloadResult =
        try {
            attempt(enclosureUrl, destination, onProgress, allowRestart = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (write: DiskWriteException) {
            EnclosureDownloadResult.WriteError(write.message ?: "failed to write to the download cache")
        } catch (cleartext: UnknownServiceException) {
            // BEFORE the IOException branch it is a subclass of. This is Android's own refusal of a
            // plain http:// connection ("CLEARTEXT communication to host not permitted by network
            // security policy"), and it is the only IOException here that no retry can ever change.
            // Only the connect can raise it; a body read cannot, which is why the inner catch in
            // `attempt` needs no equivalent.
            EnclosureDownloadResult.CleartextBlocked(
                cleartext.message ?: "an unencrypted http:// connection was refused",
            )
        } catch (network: IOException) {
            EnclosureDownloadResult.NetworkError(network.message ?: "network error downloading enclosure")
        }

    private suspend fun attempt(
        enclosureUrl: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
        allowRestart: Boolean,
    ): EnclosureDownloadResult {
        val alreadyOnDisk = if (destination.isFile) destination.length() else 0L
        val request =
            Request
                .Builder()
                .url(enclosureUrl)
                .get()
                .apply { if (alreadyOnDisk > 0) header("Range", "bytes=$alreadyOnDisk-") }
                .build()

        okHttpClient.newCall(request).execute().use { response ->
            return when {
                // 416 means our partial no longer prefixes what the server has (the episode file was
                // replaced). Appending would silently produce a corrupt file, so start over — once.
                // A partial that cannot be deleted falls through to HttpError: asking again would
                // send the same Range and get the same 416.
                response.code == HTTP_RANGE_NOT_SATISFIABLE && allowRestart && destination.delete() ->
                    attempt(enclosureUrl, destination, onProgress, allowRestart = false)
                !response.isSuccessful -> EnclosureDownloadResult.HttpError(response.code, response.message)
                // 200 in reply to a Range request = the server ignored it (many podcast hosts do).
                // The body is the whole file, so anything already on disk must be discarded.
                else ->
                    copyBody(
                        response,
                        destination,
                        resuming =
                            alreadyOnDisk > 0 && response.code == HTTP_PARTIAL_CONTENT,
                        onProgress = onProgress,
                    )
            }
        }
    }

    private suspend fun copyBody(
        response: Response,
        destination: File,
        resuming: Boolean,
        onProgress: (Long, Long?) -> Unit,
    ): EnclosureDownloadResult {
        val alreadyOnDisk = if (resuming) destination.length() else 0L
        val expectedTotal =
            response.body
                .contentLength()
                .takeIf { it >= 0 }
                ?.let { alreadyOnDisk + it }

        destination.parentFile?.mkdirs()
        val written =
            try {
                copyStream(response.body.byteStream(), destination, resuming, alreadyOnDisk, expectedTotal, onProgress)
            } catch (disk: DiskWriteException) {
                throw disk
            } catch (io: IOException) {
                return EnclosureDownloadResult.NetworkError(io.message ?: "connection lost mid-download")
            }

        return when {
            // A body that stops short of its own Content-Length is a truncated transfer, not a
            // finished file — retryable, and what arrived is a valid prefix to resume from.
            expectedTotal != null && written != expectedTotal ->
                EnclosureDownloadResult.NetworkError("download truncated at $written of $expectedTotal bytes")
            written == 0L -> EnclosureDownloadResult.NetworkError("server returned an empty body")
            else -> EnclosureDownloadResult.Completed(destination, response.header("Content-Type"))
        }
    }

    /** @return total bytes on disk when the copy finished. */
    @Suppress("LongParameterList")
    private suspend fun copyStream(
        source: InputStream,
        destination: File,
        append: Boolean,
        alreadyOnDisk: Long,
        expectedTotal: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        var written = alreadyOnDisk
        source.use { input ->
            openSink(destination, append).use { sink ->
                written = pump(input, sink, written, expectedTotal, onProgress)
            }
        }
        return written
    }

    /** The copy loop itself, split out so the stream-closing scopes above stay one level deep. */
    private suspend fun pump(
        input: InputStream,
        sink: OutputStream,
        alreadyWritten: Long,
        expectedTotal: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        var written = alreadyWritten
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            // Cooperative cancellation: WorkManager stopping this worker must not take a whole
            // 100 MB episode to notice. The partial file survives for the resume.
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            sink.writeChunk(buffer, read)
            written += read
            onProgress(written, expectedTotal)
        }
        sink.flushOrThrow()
        return written
    }

    private fun openSink(
        destination: File,
        append: Boolean,
    ): OutputStream =
        try {
            FileOutputStream(destination, append).buffered()
        } catch (io: IOException) {
            throw DiskWriteException(io)
        }
}

private fun OutputStream.writeChunk(
    buffer: ByteArray,
    length: Int,
) {
    try {
        write(buffer, 0, length)
    } catch (io: IOException) {
        // Disk full / cache directory removed. Distinguished from a read failure so the caller can
        // report "no space" rather than blaming the network and retrying forever.
        throw DiskWriteException(io)
    }
}

private fun OutputStream.flushOrThrow() {
    try {
        flush()
    } catch (io: IOException) {
        throw DiskWriteException(io)
    }
}
