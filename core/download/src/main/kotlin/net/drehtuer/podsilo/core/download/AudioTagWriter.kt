// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File

/**
 * The tag values CLAUDE.md section 6 specifies: title (cleaned episode title), artist/album (podcast
 * title), [year] and [trackNumber] derived from the episode's date, a fixed [genre], and the episode
 * description in [comment].
 */
data class AudioTagData(
    val title: String,
    val artist: String,
    val album: String,
    val year: String? = null,
    val genre: String = "Podcast",
    val trackNumber: String? = null,
    val comment: String? = null,
    /**
     * Embedded **only when the file carries none of its own**. A publisher who shipped per-episode
     * art in the file meant it; replacing it would be us overruling them, and the request was to
     * fill a gap rather than to normalise every file.
     */
    val artwork: EpisodeArtwork? = null,
)

sealed interface TagWriteOutcome {
    data object Success : TagWriteOutcome

    /**
     * One or more fields weren't representable in this file's tag format -- still a usable delivery.
     *
     * @property artworkSkipped the cover could not be embedded: the container does not accept
     *   artwork, or it already had its own. Reported rather than silent so "why has this episode no
     *   cover" is answerable — every other field's failure is visible here, and artwork's was not.
     */
    data class PartialSuccess(
        val skippedFields: List<FieldKey>,
        val artworkSkipped: Boolean = false,
    ) : TagWriteOutcome

    /** The file couldn't be read as a supported audio container, or the tag write itself failed. */
    data class Failure(
        val reason: String,
    ) : TagWriteOutcome
}

/**
 * Rewrites audio tags on [file] in place, best-effort -- CLAUDE.md section 6: a tag-write failure
 * must never lose a successful download. Uses jaudiotagger (the Android-compatible Adonai/Kaned1as
 * fork, not the stale upstream artifact -- see `architecture.adoc` §11).
 *
 * Needs a real [File]: tagging libraries can't write through a SAF `OutputStream`, which is why the
 * download pipeline tags in the app cache before copying into the user's SAF folder
 * (`architecture.adoc` section 11).
 */
class AudioTagWriter {
    fun writeTags(
        file: File,
        data: AudioTagData,
    ): TagWriteOutcome {
        val audioFile =
            try {
                AudioFileIO.read(file)
            } catch (
                @Suppress("TooGenericExceptionCaught") unreadable: Exception,
            ) {
                val reason = unreadable.message ?: "file is not a supported audio container"
                return TagWriteOutcome.Failure(reason)
            }

        val tag = audioFile.tagOrCreateAndSetDefault
        val skipped = writeFields(tag, data)
        val artworkEmbedded = data.artwork?.let { embedArtworkIfAbsent(tag, it) } ?: true

        return try {
            audioFile.commit()
            if (skipped.isEmpty() && artworkEmbedded) {
                TagWriteOutcome.Success
            } else {
                TagWriteOutcome.PartialSuccess(skipped, artworkSkipped = !artworkEmbedded)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") uncommittable: Exception,
        ) {
            TagWriteOutcome.Failure(uncommittable.message ?: "failed to write tags to file")
        }
    }
}

/** Returns the [FieldKey]s that this file's tag format wouldn't accept, best-effort per field. */
private fun writeFields(
    tag: Tag,
    data: AudioTagData,
): List<FieldKey> =
    buildList {
        val fields =
            listOf(
                FieldKey.TITLE to data.title,
                FieldKey.ARTIST to data.artist,
                FieldKey.ALBUM to data.album,
                FieldKey.GENRE to data.genre,
                FieldKey.YEAR to data.year,
                FieldKey.TRACK to data.trackNumber,
                FieldKey.COMMENT to data.comment,
            )
        fields.forEach { (key, value) ->
            if (value != null && !tag.trySetField(key, value)) add(key)
        }
    }

private fun Tag.trySetField(
    key: FieldKey,
    value: String,
): Boolean =
    try {
        setField(key, value)
        true
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") unsupportedField: Exception,
    ) {
        // Best-effort per field, by design (CLAUDE.md section 6): some tag formats don't support
        // every field. The caller surfaces the skipped keys via PartialSuccess rather than losing
        // the whole write over one field this container can't hold.
        false
    }

/**
 * Adds [artwork] only when the tag has none. Returns whether the cover ended up on the file.
 *
 * `getFirstArtwork()` throws on some containers rather than returning null, so the check is wrapped:
 * "I could not tell whether art exists" must behave like "art exists" — writing over a publisher's
 * cover because we failed to read it would be the worse mistake. That case reports `true`: nothing
 * was skipped, the file simply already had its own.
 *
 * A container that cannot hold artwork at all reports `false`, which the caller surfaces as a
 * [TagWriteOutcome.PartialSuccess]. It never fails the write — the audio is already correct, and
 * CLAUDE.md §6 is explicit that tagging must not cost a delivery.
 */
private fun embedArtworkIfAbsent(
    tag: Tag,
    artwork: EpisodeArtwork,
): Boolean {
    if (alreadyHasArtwork(tag)) return true

    return try {
        tag.setField(
            AndroidArtwork().apply {
                binaryData = artwork.bytes
                mimeType = artwork.mimeType
                description = ""
            },
        )
        true
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") unsupported: Exception,
    ) {
        // This container does not accept artwork (or rejected this image). Reported, not fatal.
        false
    }
}

/**
 * `true` also when the answer cannot be determined.
 *
 * `getFirstArtwork()` throws on some containers rather than returning null, and "I could not tell"
 * has to behave like "it has one": writing over a publisher's cover because we failed to read the
 * tag is the worse of the two mistakes.
 */
private fun alreadyHasArtwork(tag: Tag): Boolean =
    try {
        tag.firstArtwork != null
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") unreadable: Exception,
    ) {
        true
    }
