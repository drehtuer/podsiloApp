// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The per-episode cover a feed may supply, which is the first choice when embedding artwork on
 * download. The podcast's own image is the fallback and is resolved later, in `:core:download` —
 * parsing deliberately reports only what the item actually said.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeImageParsingTest {
    private val parser = FeedXmlParser()

    private fun parse(fixture: String) =
        runBlocking {
            val bytes =
                requireNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$fixture")?.use { it.readBytes() }) {
                    "missing fixture: $fixture"
                }
            parser.parse("https://example.org/feed.xml", bytes)
        }

    @Test
    fun `itunes image on an item is the episode's artwork`() {
        val episodes = parse("episode_images.xml").episodes

        assertEquals("https://example.org/ep-itunes.jpg", episodes.single { it.guid == "ep-itunes" }.imageUrl)
    }

    @Test
    fun `a bare image element on an item is accepted too`() {
        val episodes = parse("episode_images.xml").episodes

        assertEquals("https://example.org/ep-bare.png", episodes.single { it.guid == "ep-bare" }.imageUrl)
    }

    @Test
    fun `an item with no artwork reports none rather than borrowing the channel's`() {
        // The fallback is the downloader's decision, not the parser's: recording the podcast cover
        // here would make it impossible to tell "the episode has its own" from "it inherited one".
        val episodes = parse("episode_images.xml").episodes

        assertNull(episodes.single { it.guid == "ep-none" }.imageUrl)
    }

    @Test
    fun `the channel image is still the feed's, unchanged`() {
        assertEquals("https://example.org/podcast-cover.jpg", parse("episode_images.xml").metadata.imageUrl)
    }

    // --- cleartext artwork ---------------------------------------------------------------------
    //
    // Android blocks http:// at targetSdk 28+, and feeds still publish artwork that way. Upgrading
    // the request to TLS either works or fails exactly as the blocked request did, with the same
    // monogram fallback, so there is no case where it is worse than leaving it — which is what makes
    // it preferable to a network-security config that would weaken every request in the app.

    @Test
    fun `a cleartext channel image is requested over TLS instead`() {
        assertEquals(
            "https://example.org/podcast-cover.jpg",
            parse("cleartext_artwork.xml").metadata.imageUrl,
        )
    }

    @Test
    fun `episode artwork is upgraded to TLS only when it was cleartext, whatever the scheme's case`() {
        val byGuid = parse("cleartext_artwork.xml").episodes.associateBy { it.guid }
        val expected =
            mapOf(
                "ep-cleartext" to "https://example.org/ep-cleartext.jpg",
                "ep-uppercase" to "https://example.org/ep-uppercase.jpg",
                // Already https: left exactly as published.
                "ep-secure" to "https://example.org/ep-secure.jpg",
            )

        expected.forEach { (guid, url) -> assertEquals(guid, url, byGuid.getValue(guid).imageUrl) }
    }

    /**
     * **The invariant the artwork upgrade must not break.** An enclosure URL is `episodeKey`'s
     * fallback when a feed omits `<guid>`, and it is the `episode` field of every action posted to
     * the shared GPodder log — so an upgraded one is a *different episode* to AntennaPod and to
     * Nextcloud. A cleartext enclosure is reported as `ErrorCause.CLEARTEXT_BLOCKED` at download
     * time instead; it is never rewritten to make it work.
     */
    @Test
    fun `a cleartext enclosure URL is left untouched, because it is episode identity`() {
        val episodes = parse("cleartext_artwork.xml").episodes

        assertEquals(
            "http://example.org/ep-cleartext.mp3",
            episodes.single { it.guid == "ep-cleartext" }.enclosureUrl,
        )
    }

    /**
     * `<enclosure length>` — advisory, and only when positive.
     *
     * Feeds write `length="0"` when they mean "no idea", and a row reading "0 MB" is worse than a row
     * with no size at all, so zero is dropped rather than stored.
     */
    @Test
    fun `the enclosure length is parsed as the advertised size`() {
        val episodes = parse("valid_minimal.xml").episodes

        assertEquals(12_345_678L, episodes.first { it.enclosureUrl.endsWith("ep2.mp3") }.sizeBytes)
        assertEquals(10_000_000L, episodes.first { it.enclosureUrl.endsWith("ep1.mp3") }.sizeBytes)
    }
}
