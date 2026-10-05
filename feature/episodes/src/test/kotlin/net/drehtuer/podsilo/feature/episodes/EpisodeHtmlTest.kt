// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only place hostile feed HTML meets a renderer, so it is table-tested before the screen that
 * uses it exists (it is a pure function that hostile feed HTML meets). Feeds are third-party input the author does not
 * control; show notes routinely carry tracking pixels, and a podcast host that gets compromised
 * ships script tags to every subscriber at once.
 */
class EpisodeHtmlTest {
    private fun render(raw: String?): String = sanitizeEpisodeHtml(raw).text

    @Test
    fun `null and blank descriptions render as empty, not as the word null`() {
        assertEquals("", render(null))
        assertEquals("", render(""))
        assertEquals("", render("   "))
    }

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("Eine Folge über Regen.", render("Eine Folge über Regen."))
    }

    @Test
    fun `script content is dropped, not merely unwrapped`() {
        // Unwrapping would print the script body as text. Both are safe; only one is sane.
        val out = render("Before<script>alert('xss');</script>After")
        assertEquals("BeforeAfter", out)
        assertFalse(out.contains("alert"))
    }

    @Test
    fun `style and iframe bodies go the same way`() {
        assertEquals("AB", render("A<style>body{display:none}</style>B"))
        assertEquals("AB", render("""A<iframe src="https://evil.example"></iframe>B"""))
    }

    @Test
    fun `images are removed entirely — a remote one is a tracking pixel`() {
        // The sheet opening must not report back to anyone, and UI.adoc §6 strips them anyway.
        val out = render("""Notes<img src="https://tracker.example/pixel.gif" width="1"/>end""")
        assertEquals("Notesend", out)
        assertFalse(out.contains("tracker"))
    }

    @Test
    fun `uppercase tags do not slip past the filter`() {
        assertEquals("AB", render("A<SCRIPT>bad()</SCRIPT>B"))
    }

    @Test
    fun `emphasis is kept as styling, with its text intact`() {
        assertEquals("really important", render("<b>really</b> <i>important</i>"))
        assertTrue(sanitizeEpisodeHtml("<b>x</b>").spanStyles.isNotEmpty())
    }

    @Test
    fun `paragraphs and breaks become single line breaks, not a wall of blank space`() {
        // Feeds routinely wrap the same text in both <p> and <br/>; honouring both leaves the sheet
        // mostly whitespace.
        assertEquals("One\nTwo", render("<p>One</p><p>Two</p>"))
        assertEquals("One\nTwo", render("One<br/><br/>Two"))
    }

    @Test
    fun `list items get a bullet so they still read as a list`() {
        assertEquals("• First\n• Second", render("<ul><li>First</li><li>Second</li></ul>").trim())
    }

    @Test
    fun `link text survives and an http href becomes a link annotation`() {
        val annotated = sanitizeEpisodeHtml("""See <a href="https://example.org/notes">the notes</a>.""")

        assertEquals("See the notes.", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `a link without an href keeps its text and gains no annotation`() {
        val annotated = sanitizeEpisodeHtml("<a>bare</a>")

        assertEquals("bare", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun `a javascript href yields no navigable link`() {
        // Compose hands a LinkAnnotation.Url to an intent, so a javascript: or intent: URL must
        // never become one. Only http(s) is ever clickable.
        listOf("javascript:alert(1)", "intent://evil#Intent;end", "file:///etc/passwd").forEach { hostile ->
            val annotated = sanitizeEpisodeHtml("""<a href="$hostile">tap me</a>""")

            assertEquals("tap me", annotated.text)
            assertTrue(
                "'$hostile' must not be clickable",
                annotated.getLinkAnnotations(0, annotated.length).isEmpty(),
            )
        }
    }

    @Test
    fun `entities are decoded so the reader sees characters, not markup`() {
        assertEquals("Fish & Chips", render("Fish &amp; Chips"))
        assertEquals("<not a tag>", render("&lt;not a tag&gt;"))
        assertEquals("\"quoted\"", render("&quot;quoted&quot;"))
    }

    @Test
    fun `unknown tags degrade to their text rather than being printed`() {
        // The safe direction: an unrecognised tag can only lose formatting, never gain capability.
        assertEquals("content", render("<marquee><blink>content</blink></marquee>"))
    }

    @Test
    fun `unbalanced markup does not throw`() {
        assertEquals("open", render("<b>open"))
        assertEquals("close", render("close</b>"))
    }
}
