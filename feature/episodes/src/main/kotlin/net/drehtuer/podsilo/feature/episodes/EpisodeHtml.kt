// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Renders an episode's show notes.
 *
 * `Episode.description` is stored **raw**, exactly as the feed supplied it (`architecture.adoc`
 * §4), so this is the single point where hostile third-party HTML meets a renderer — which is why
 * it is a pure function with a table test rather than something the screen does inline.
 *
 * Allowed through: paragraphs, line breaks, bold/italic, lists, and link *text*. Dropped entirely,
 * content and all: `<script>`, `<style>`, `<iframe>`, and every `<img>` — a remote image in show
 * notes is a tracking pixel that would report back when the sheet is opened, and no episode
 * description needs one.
 *
 * Deliberately not a general HTML renderer. Anything it does not recognise degrades to its text
 * content, which is the safe direction: an unrecognised tag can only ever lose formatting, never
 * gain capability.
 */
fun sanitizeEpisodeHtml(raw: String?): AnnotatedString {
    if (raw.isNullOrBlank()) return AnnotatedString("")
    val tokens = tokenize(stripDangerousElements(raw))
    val built = tokens.render()

    // A trailing </p> or <br/> would otherwise leave the sheet ending in blank lines. Trimming the
    // built value rather than guessing at emit time keeps the render loop simple and the styles and
    // link annotations intact — subSequence carries both.
    val end = built.text.indexOfLast { !it.isWhitespace() } + 1
    return if (end == built.length) built else built.subSequence(0, end)
}

private fun List<HtmlToken>.render(): AnnotatedString =
    buildAnnotatedString {
        val openSpans = ArrayDeque<Pair<String, Int>>()
        // Tracked explicitly rather than read back off the builder: `Builder.toString()` is not the
        // accumulated text, so a check against it silently never matches and every <p></p><br/> pair
        // doubles up. Found by the test, not by reading the code.
        var pendingBreak = false
        this@render.forEach { token ->
            when (token) {
                is HtmlToken.Text -> {
                    if (pendingBreak && length > 0) append("\n")
                    pendingBreak = false
                    append(token.value)
                }
                // Collapsed: feeds routinely wrap the same text in both <p> and <br/>, and honouring
                // both leaves the sheet mostly whitespace.
                is HtmlToken.LineBreak -> pendingBreak = true
                is HtmlToken.ListItem -> {
                    if (length > 0) append("\n")
                    pendingBreak = false
                    append("• ")
                }
                is HtmlToken.Open -> openSpans.addLast(token.tag to length)
                is HtmlToken.Close -> closeSpan(token, openSpans)
            }
        }
    }

/** A close tag with no matching open tag is dropped: feeds do this, and it only loses formatting. */
private fun AnnotatedString.Builder.closeSpan(
    token: HtmlToken.Close,
    openSpans: ArrayDeque<Pair<String, Int>>,
) {
    val opened = openSpans.removeLastOrNull { it.first == token.tag } ?: return
    val href = token.href?.takeIf { it.isNavigable() }
    styleFor(token.tag, href)?.let { addStyle(it, opened.second, length) }
    if (token.tag == "a" && href != null) {
        addLink(LinkAnnotation.Url(href), opened.second, length)
    }
}

/**
 * Compose hands a `LinkAnnotation.Url` straight to an `Intent`, so an **allow-list** is the only
 * safe shape here: `javascript:`, `intent:`, `file:` and every other scheme a feed might carry are
 * things the OS would happily act on. A non-navigable href keeps its text and simply isn't
 * clickable — which is also why the underline is applied from the *filtered* href, so nothing ever
 * looks like a link it isn't.
 */
private fun String.isNavigable(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun styleFor(
    tag: String,
    href: String?,
): SpanStyle? =
    when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
        "a" -> href?.let { SpanStyle(textDecoration = TextDecoration.Underline) }
        else -> null
    }

private sealed interface HtmlToken {
    data class Text(
        val value: String,
    ) : HtmlToken

    data object LineBreak : HtmlToken

    data object ListItem : HtmlToken

    data class Open(
        val tag: String,
    ) : HtmlToken

    data class Close(
        val tag: String,
        val href: String?,
    ) : HtmlToken
}

private val DANGEROUS =
    Regex(
        "<\\s*(script|style|iframe|object|embed)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private val DANGEROUS_UNCLOSED =
    Regex(
        "<\\s*(script|style|iframe|object|embed|img)\\b[^>]*/?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private const val ATTRIBUTES_GROUP = 3

private val TAG = Regex("<\\s*(/?)\\s*([a-zA-Z0-9]+)([^>]*)>")
private val HREF = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

/**
 * Removes the elements whose *content* must go too. Applied before tokenising, because a
 * `<script>` body is not text to be shown with the tags stripped — it is code to be discarded.
 */
private fun stripDangerousElements(raw: String): String = raw.replace(DANGEROUS, "").replace(DANGEROUS_UNCLOSED, "")

private fun tokenize(html: String): List<HtmlToken> {
    val tokens = mutableListOf<HtmlToken>()
    var cursor = 0
    TAG.findAll(html).forEach { match ->
        if (match.range.first > cursor) {
            tokens += HtmlToken.Text(html.substring(cursor, match.range.first).decodeEntities())
        }
        cursor = match.range.last + 1

        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        val attributes = match.groupValues[ATTRIBUTES_GROUP]
        when {
            tag == "br" -> tokens += HtmlToken.LineBreak
            tag == "li" && !closing -> tokens += HtmlToken.ListItem
            tag == "p" && closing -> tokens += HtmlToken.LineBreak
            closing -> tokens += HtmlToken.Close(tag, null)
            else -> tokens += HtmlToken.Open(tag)
        }
        // The href belongs to the opening tag but is needed when the span closes, so it rides along
        // on the close token the opener will be matched with.
        if (tag == "a" && !closing) {
            val href = HREF.find(attributes)?.groupValues?.get(1)
            tokens += HtmlToken.Open(HREF_MARKER + href.orEmpty())
        }
    }
    if (cursor < html.length) tokens += HtmlToken.Text(html.substring(cursor).decodeEntities())
    return tokens.resolveLinks()
}

private const val HREF_MARKER = "\u0000href:"

/** Folds the href side-channel back onto the matching close token, and drops the marker. */
private fun List<HtmlToken>.resolveLinks(): List<HtmlToken> {
    var pendingHref: String? = null
    val out = mutableListOf<HtmlToken>()
    forEach { token ->
        when {
            token is HtmlToken.Open && token.tag.startsWith(HREF_MARKER) -> {
                pendingHref = token.tag.removePrefix(HREF_MARKER).takeIf { it.isNotEmpty() }
            }
            token is HtmlToken.Close && token.tag == "a" -> {
                out += HtmlToken.Close("a", pendingHref)
                pendingHref = null
            }
            else -> out += token
        }
    }
    return out
}

private fun <T> ArrayDeque<T>.removeLastOrNull(predicate: (T) -> Boolean): T? {
    val index = indexOfLast(predicate)
    if (index < 0) return null
    val value = this[index]
    removeAt(index)
    return value
}

private val ENTITIES =
    mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " ",
        "&hellip;" to "…",
        "&mdash;" to "—",
        "&ndash;" to "–",
    )

private fun String.decodeEntities(): String = ENTITIES.entries.fold(this) { text, (from, to) -> text.replace(from, to) }
