// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Tagging every container Podsilo can actually be handed, not just MP3.
 *
 * CLAUDE.md §6 is explicit that "podcasts are not always MP3 — expect `m4a`, `aac`, `ogg`, `opus`",
 * and `:core:naming` resolves those extensions, but until now `audio/silence.mp3` was the only
 * fixture in the repository. Everything this file asserts was previously **supported by jaudiotagger
 * and unproven here** — including which fields each container refuses, which is precisely what
 * [TagWriteOutcome.PartialSuccess] exists to report and what no MP3 test can produce.
 *
 * ### How the fixtures were made
 *
 * With ffmpeg, once, on 2026-08-14; the results are committed so nothing in the dev container has to
 * grow an encoder to run these (`backlog.adoc` said "a few tiny committed fixtures would close it
 * permanently", and this is that). Each is 0.3 s of digital silence and under 4 KB:
 *
 * ```
 * ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 0.3 -c:a aac      -b:a 32k silence.m4a
 * ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 0.3 -c:a libvorbis -q:a 0  silence.ogg
 * ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 0.3 -c:a libopus  -b:a 16k silence.opus
 * ```
 *
 * Regenerate them the same way if they ever need to change — do not hand-edit an audio container.
 *
 * ### Why Robolectric, when nothing here touches an Android API
 *
 * **Because the plain JVM runner reports the wrong answer, convincingly.** Writing artwork into a
 * Vorbis comment (`.ogg` and `.opus` both use one) goes through `AndroidArtwork`, which calls
 * `BitmapFactory.decodeByteArray` to fill in the width/height/colour-depth that a
 * `METADATA_BLOCK_PICTURE` requires. On the unit-test classpath that method throws
 * *"not mocked"*, `AudioTagWriter` catches it as "this container cannot hold artwork", and the test
 * passes while asserting the opposite of the truth: it would have pinned "Ogg cannot carry a cover"
 * as app behaviour, when in fact all four containers take one. Robolectric supplies a real enough
 * `BitmapFactory` and the answer flips to `Success` everywhere.
 *
 * That is a Tier-1-vs-reality trap of exactly the kind `decisions/0017` was written about, and
 * it was found by probing what each container actually reported rather than by assuming the green
 * run meant what it looked like.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NonMp3TaggingTest(
    private val fixture: String,
) {
    companion object {
        /**
         * MP3 is in the list deliberately: it is the baseline the other three are compared against,
         * which is what makes a failure readable as "this container is different" rather than "this
         * test is different". It is also the *only* place the artwork rules are asserted for MP3 —
         * [AudioTagWriterTest] covers the field-level behaviour and does not repeat them.
         */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun fixtures(): List<String> = listOf("silence.mp3", "silence.m4a", "silence.ogg", "silence.opus")
    }

    private val writer = AudioTagWriter()
    private val cover = EpisodeArtwork(byteArrayOf(1, 2, 3, 4, 5), "image/jpeg", EpisodeArtwork.Source.PODCAST)

    private fun copyOfFixture(): File {
        val bytes =
            javaClass.classLoader
                ?.getResourceAsStream("audio/$fixture")
                ?.use { it.readBytes() }
        requireNotNull(bytes) { "missing fixture: audio/$fixture" }

        val tempFile = Files.createTempFile("podsilo-tag-test", "." + fixture.substringAfterLast('.')).toFile()
        tempFile.deleteOnExit()
        tempFile.writeBytes(bytes)
        return tempFile
    }

    private fun tagData() =
        AudioTagData(
            title = "Warum Hamburg immer regnet",
            artist = "Der Podcast",
            album = "Der Podcast",
            year = "2026",
            trackNumber = "20260714",
            comment = "Eine Folge über das Wetter.",
        )

    /**
     * **Every container Podsilo accepts takes every tag it writes, artwork included** — measured,
     * not assumed. `Success` rather than "did not fail" because that is what all four actually
     * report, and an assertion that tolerates `PartialSuccess` would not notice the day one of them
     * stopped taking a field.
     *
     * The weaker invariant still holds underneath and is worth stating: a tag problem must never
     * cost a delivery (CLAUDE.md §6), which is why `writeTags` returns an outcome instead of
     * throwing at all.
     */
    @Test
    fun `every container takes every field and the artwork`() {
        val file = copyOfFixture()

        val outcome = writer.writeTags(file, tagData().copy(artwork = cover))

        assertEquals("$fixture no longer accepts everything", TagWriteOutcome.Success, outcome)
        // Success with artwork must not have come at the cost of the text fields.
        assertEquals("Warum Hamburg immer regnet", AudioFileIO.read(file).tag.getFirst(FieldKey.TITLE))
    }

    /**
     * The four fields the author actually browses by in someone else's player (CLAUDE.md §6:
     * "the names and tags we write are the entire user experience of the download"). Every
     * container we accept must hold these, and this is the assertion that would catch one that
     * silently did not.
     */
    @Test
    fun `title, artist, album and genre survive in every container`() {
        val file = copyOfFixture()

        writer.writeTags(file, tagData())

        val readBack = AudioFileIO.read(file).tag
        assertEquals("Warum Hamburg immer regnet", readBack.getFirst(FieldKey.TITLE))
        assertEquals("Der Podcast", readBack.getFirst(FieldKey.ARTIST))
        assertEquals("Der Podcast", readBack.getFirst(FieldKey.ALBUM))
        assertEquals("Podcast", readBack.getFirst(FieldKey.GENRE))
    }

    @Test
    fun `artwork is embedded and readable back in every container`() {
        val file = copyOfFixture()
        assertNull("fixture should start with no artwork", AudioFileIO.read(file).tag?.firstArtwork)

        writer.writeTags(file, tagData().copy(artwork = cover))

        assertArrayEquals(
            cover.bytes,
            AudioFileIO
                .read(file)
                .tag
                ?.firstArtwork
                ?.binaryData,
        )
    }

    /**
     * The rule that must not vary by container: a cover the publisher shipped is never replaced
     * (`architecture.adoc` §11).
     */
    @Test
    fun `artwork the publisher already embedded is never replaced`() {
        val file = copyOfFixture()
        val original = EpisodeArtwork(byteArrayOf(9, 9, 9), "image/png", EpisodeArtwork.Source.EPISODE)
        writer.writeTags(file, tagData().copy(artwork = original))

        writer.writeTags(file, tagData().copy(artwork = cover))

        assertArrayEquals(
            "$fixture overwrote the publisher's cover",
            original.bytes,
            AudioFileIO
                .read(file)
                .tag
                ?.firstArtwork
                ?.binaryData,
        )
    }
}
