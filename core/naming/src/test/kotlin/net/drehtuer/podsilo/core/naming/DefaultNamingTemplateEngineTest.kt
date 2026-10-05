// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DefaultNamingTemplateEngineTest {
    private val utc = ZoneOffset.UTC

    private fun feed(title: String = "Der Podcast") =
        Feed(
            url = "https://example.com/feed.xml",
            title = title,
            imageUrl = null,
            firstSeenAt = 0L,
            lastRefreshedAt = null,
            httpEtag = null,
            httpLastModified = null,
        )

    private fun episode(
        title: String = "Warum Hamburg immer regnet",
        enclosureUrl: String = "https://example.com/ep1.mp3",
        guid: String? = "guid-123",
        pubDate: Long? = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli(),
        description: String? = null,
    ) = Episode(
        episodeKey = guid ?: enclosureUrl,
        feedUrl = "https://example.com/feed.xml",
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title,
        description = description,
        pubDate = pubDate,
        durationMs = null,
    )

    private fun engine(
        titleCleanupRules: List<TitleCleanupRule> = emptyList(),
        transliterate: Boolean = false,
        maxComponentBytes: Int = DEFAULT_MAX_COMPONENT_BYTES,
    ) = DefaultNamingTemplateEngine(
        zoneId = utc,
        titleCleanupRules = titleCleanupRules,
        transliterate = transliterate,
        maxComponentBytes = maxComponentBytes,
    )

    private fun DefaultNamingTemplateEngine.file(
        fileTemplate: String,
        episode: Episode = episode(),
        feed: Feed = feed(),
        contentType: String? = null,
    ) = resolve(feed, episode, folderTemplate = "{podcast}", fileTemplate = fileTemplate, contentType = contentType)

    private fun String.utf8Bytes() = toByteArray(Charsets.UTF_8).size

    @Test
    fun `default templates match the CLAUDE md example`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        assertEquals("Der Podcast", result.folder)
        assertEquals("20260714_Warum Hamburg immer regnet", result.fileNameWithoutExtension)
        assertEquals("mp3", result.extension)
    }

    @Test
    fun `explicit date pattern is honoured`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{date:yyyy-MM-dd}_{title}",
            )

        assertEquals("2026-07-14_Warum Hamburg immer regnet", result.fileNameWithoutExtension)
    }

    @Test
    fun `missing pubDate degrades to the sortable placeholder, never an empty leading segment`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(pubDate = null),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        assertTrue(result.fileNameWithoutExtension.startsWith("00000000_"))
    }

    @Test
    fun `extension is resolved from the enclosure url`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(enclosureUrl = "https://example.com/ep1.opus?token=abc"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("opus", result.extension)
    }

    @Test
    fun `a 400-character title is truncated to a valid utf-8 byte budget, room reserved for date and extension`() {
        val hugeTitle = "日".repeat(400) // 3 bytes/char, 1200 bytes raw
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(title = hugeTitle),
                folderTemplate = "{podcast}",
                fileTemplate = "{date}_{title}",
            )

        val fileNameBytes = result.fileNameWithoutExtension.toByteArray(Charsets.UTF_8)
        val extensionOverhead = result.extension.toByteArray(Charsets.UTF_8).size + 1 // dot
        assertTrue(
            "expected room for extension + collision suffix headroom, was ${fileNameBytes.size} bytes",
            fileNameBytes.size + extensionOverhead + COLLISION_SUFFIX_RESERVED_BYTES <= DEFAULT_MAX_COMPONENT_BYTES,
        )
        // Round-trip proves no UTF-8 sequence was split.
        assertEquals(result.fileNameWithoutExtension, String(fileNameBytes, Charsets.UTF_8))
    }

    @Test
    fun `a podcast title that sanitises to a reserved device name is escaped`() {
        val result =
            engine().resolve(
                feed = feed(title = "con"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("con_", result.folder)
    }

    @Test
    fun `an episode title that sanitises entirely away falls back to guid_short`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(title = "...", guid = "guid-fallback-test"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals(guidShort("guid-fallback-test"), result.fileNameWithoutExtension)
    }

    @Test
    fun `title cleanup rules run before sanitising`() {
        val rules = listOf(TitleCleanupRule(Regex("""^Ep\.? ?\d+ *[-–—] *"""), ""))
        val result =
            engine(titleCleanupRules = rules).resolve(
                feed = feed(),
                episode = episode(title = "Ep. 142 - Something Interesting"),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Something Interesting", result.fileNameWithoutExtension)
    }

    @Test
    fun `transliteration is off by default -- umlauts survive in the resolved name`() {
        val result =
            engine().resolve(
                feed = feed(title = "Über den Wolken"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Über den Wolken", result.folder)
    }

    @Test
    fun `transliteration is applied when explicitly enabled`() {
        val result =
            engine(transliterate = true).resolve(
                feed = feed(title = "Über den Wolken"),
                episode = episode(),
                folderTemplate = "{podcast}",
                fileTemplate = "{title}",
            )

        assertEquals("Ueber den Wolken", result.folder)
    }

    @Test
    fun `guid_short variable resolves to the same hash as the standalone helper`() {
        val result =
            engine().resolve(
                feed = feed(),
                episode = episode(guid = "guid-123"),
                folderTemplate = "{guid_short}",
                fileTemplate = "{title}",
            )

        assertEquals(guidShort("guid-123"), result.folder)
    }

    /** CLAUDE.md §6's optional `{description}`, subject to the same sanitising as `{title}`. */
    @Test
    fun `description variable is sanitised like the title`() {
        val result = engine().file("{date}_{description}", episode(description = "Show notes: part 1/2"))

        assertEquals("20260714_Show notes_ part 1_2", result.fileNameWithoutExtension)
    }

    @Test
    fun `a missing description falls back to guid_short rather than an empty segment`() {
        val result = engine().file("{date}_{description}", episode(guid = "guid-no-notes", description = null))

        assertEquals("20260714_${guidShort("guid-no-notes")}", result.fileNameWithoutExtension)
    }

    @Test
    fun `an empty template resolves to guid_short, never to an empty component`() {
        val result = engine().resolve(feed(), episode(guid = "guid-empty"), folderTemplate = "", fileTemplate = "")

        assertEquals(guidShort("guid-empty"), result.folder)
        assertEquals(guidShort("guid-empty"), result.fileNameWithoutExtension)
    }

    @Test
    fun `the response Content-Type decides the extension over the enclosure url`() {
        val result = engine().file("{title}", contentType = "audio/mp4")

        assertEquals("m4a", result.extension)
    }

    /**
     * Two free-text variables share one component's budget. Each gets half, so the pair -- plus the
     * literal between them, the extension and the collision headroom -- still fits 255 bytes.
     */
    @Test
    fun `two elastic variables share the byte budget and the component still fits`() {
        val result =
            engine().file(
                "{podcast} - {title}",
                feed = feed(title = "P".repeat(300)),
                episode = episode(title = "\u00e4".repeat(300)),
            )

        val name = result.fileNameWithoutExtension
        assertTrue(name.startsWith("P"))
        assertTrue(name.contains(" - \u00e4"))
        val total = name.utf8Bytes() + 1 + result.extension.utf8Bytes() + COLLISION_SUFFIX_RESERVED_BYTES
        assertTrue("was $total bytes", total <= DEFAULT_MAX_COMPONENT_BYTES)
    }

    @Test
    fun `an over-long podcast title is truncated in the folder, leaving collision headroom`() {
        val result = engine().resolve(feed(title = "x".repeat(400)), episode(), "{podcast}", "{title}")

        assertEquals(DEFAULT_MAX_COMPONENT_BYTES - COLLISION_SUFFIX_RESERVED_BYTES, result.folder.utf8Bytes())
    }

    /**
     * Truncation can land just after a space or dot, which is invalid at the end of a FAT/Windows
     * name. A 20-byte limit leaves the title 11 bytes (20 - 5 headroom - ".mp3"), cutting
     * "abcdefghij klm" right after the space.
     */
    @Test
    fun `a trailing space exposed by truncation is stripped`() {
        val result = engine(maxComponentBytes = 20).file("{title}", episode(title = "abcdefghij klm"))

        assertEquals("abcdefghij", result.fileNameWithoutExtension)
    }

    /**
     * When the fixed parts eat the whole budget, the title is truncated to nothing -- and must then
     * fall back to guid_short rather than leaving a name that ends in a bare separator.
     */
    @Test
    fun `a title squeezed to nothing falls back to guid_short`() {
        val sixBytes = episode(title = "\u65e5\u672c", guid = "guid-squeezed")
        val result = engine(maxComponentBytes = 20).file("{date}_{title}", sixBytes)

        assertEquals("20260714_${guidShort("guid-squeezed")}", result.fileNameWithoutExtension)
    }

    /**
     * Regression: `{date:pattern}` output was never sanitised, so an ordinary user pattern put a path
     * separator or a colon -- illegal on FAT32/exFAT (CLAUDE.md §6) -- straight into the filename.
     */
    @Test
    fun `a date pattern producing illegal characters is sanitised`() {
        val pubDate = Instant.parse("2026-07-14T09:30:00Z").toEpochMilli()
        listOf(
            "{date:yyyy/MM/dd}_{title}" to "2026_07_14_Warum Hamburg immer regnet",
            "{date:yyyyMMdd HH:mm}_{title}" to "20260714 09_30_Warum Hamburg immer regnet",
        ).forEach { (template, expected) ->
            val resolved = engine().file(template, episode(pubDate = pubDate))
            assertEquals(template, expected, resolved.fileNameWithoutExtension)
        }
    }

    /** Regression: `{date:}` formatted to "", producing exactly the `_Title.mp3` CLAUDE.md §6 forbids. */
    @Test
    fun `an empty date pattern degrades to the sortable placeholder, not an empty leading segment`() {
        val result = engine().file("{date:}_{title}")

        assertEquals("${FALLBACK_DATE}_Warum Hamburg immer regnet", result.fileNameWithoutExtension)
    }
}
