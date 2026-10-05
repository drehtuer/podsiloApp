// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingTemplateEngine
import net.drehtuer.podsilo.core.model.port.ResolvedName
import java.time.ZoneId

/** Typical FAT32/exFAT/NTFS limit per path component -- the strictest plausible target (CLAUDE.md section 6). */
const val DEFAULT_MAX_COMPONENT_BYTES = 255

/**
 * Headroom reserved for a later collision suffix (` (2)`, ` (3)`, ...). [nextAvailableName] is
 * applied downstream by `:core:download` once it knows what already exists in the SAF folder; this
 * engine can't predict how many digits that suffix will need, so it reserves enough for up to
 * ` (99)` and accepts that a same-titled 100th collision in one folder is out of scope.
 */
const val COLLISION_SUFFIX_RESERVED_BYTES = 5

/**
 * Default [NamingTemplateEngine]: tokenizes the folder/file templates, resolves each variable,
 * sanitises and truncates the free-text ones (`podcast`, `title`, `description`) to fit the byte
 * budget, and applies the Windows-reserved-name check to the fully assembled component.
 *
 * @property zoneId Fixed at construction, not re-resolved per call -- see [formatDate]'s KDoc for
 *   why. Inject a fixed zone in tests for deterministic dates regardless of the test runner's
 *   local timezone.
 * @property titleCleanupRules Applied to the raw title before sanitising (CLAUDE.md section 6),
 *   default empty (feature is opt-in).
 * @property transliterate Default `false` -- non-ASCII survives by default (CLAUDE.md section 6).
 */
class DefaultNamingTemplateEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val titleCleanupRules: List<TitleCleanupRule> = emptyList(),
    private val transliterate: Boolean = false,
    private val maxComponentBytes: Int = DEFAULT_MAX_COMPONENT_BYTES,
) : NamingTemplateEngine {
    override fun resolve(
        feed: Feed,
        episode: Episode,
        folderTemplate: String,
        fileTemplate: String,
        contentType: String?,
    ): ResolvedName {
        // Resolved first, and inside the engine, because the file component's byte budget has to
        // account for the extension it will actually be given -- see resolveComponent's reservedBytes.
        val extension = resolveExtension(contentType, episode.enclosureUrl)
        val folder =
            resolveComponent(
                template = folderTemplate,
                feed = feed,
                episode = episode,
                reservedBytes = COLLISION_SUFFIX_RESERVED_BYTES,
            )
        val fileName =
            resolveComponent(
                template = fileTemplate,
                feed = feed,
                episode = episode,
                reservedBytes = COLLISION_SUFFIX_RESERVED_BYTES + extension.toByteArray(Charsets.UTF_8).size + 1,
            )
        return ResolvedName(folder = folder, fileNameWithoutExtension = fileName, extension = extension)
    }

    private fun resolveComponent(
        template: String,
        feed: Feed,
        episode: Episode,
        reservedBytes: Int,
    ): String {
        val resolved = tokenizeTemplate(template).map { token -> resolveToken(token, feed, episode) }

        val fixedBytes = resolved.filterNot { it.elastic }.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        val elasticCount = resolved.count { it.elastic }
        val perElasticBudget =
            if (elasticCount > 0) {
                ((maxComponentBytes - reservedBytes - fixedBytes) / elasticCount).coerceAtLeast(0)
            } else {
                0
            }

        val assembled =
            resolved.joinToString(separator = "") { token ->
                if (!token.elastic) {
                    token.text
                } else {
                    val truncated = stripTrailingDotsAndSpaces(truncateUtf8Safe(token.text, perElasticBudget))
                    truncated.ifEmpty { guidShort(episode.episodeKey) }
                }
            }

        return escapeReservedName(assembled.ifEmpty { guidShort(episode.episodeKey) })
    }

    private fun resolveToken(
        token: TemplateToken,
        feed: Feed,
        episode: Episode,
    ): ResolvedToken =
        when (token) {
            is TemplateToken.Literal -> ResolvedToken(token.text, elastic = false)
            is TemplateToken.Variable ->
                when (token.name) {
                    "podcast" -> ResolvedToken(sanitizeComponent(feed.title, transliterate), elastic = true)
                    "title" ->
                        ResolvedToken(
                            sanitizeComponent(applyCleanupRules(episode.title, titleCleanupRules), transliterate),
                            elastic = true,
                        )
                    "description" ->
                        ResolvedToken(
                            sanitizeComponent(episode.description.orEmpty(), transliterate),
                            elastic = true,
                        )
                    // Sanitised like any other value: a user's `{date:yyyy/MM/dd}` or `{date:HH:mm}` is
                    // an ordinary pattern whose output is illegal on FAT32/exFAT (CLAUDE.md §6), and an
                    // empty `{date:}` must not become the empty leading segment §6 forbids.
                    "date" ->
                        ResolvedToken(
                            sanitizeComponent(
                                formatDate(episode.pubDate, zoneId, token.pattern ?: DEFAULT_DATE_PATTERN),
                                transliterate = false,
                            ).ifEmpty { FALLBACK_DATE },
                            elastic = false,
                        )
                    "guid_short" -> ResolvedToken(guidShort(episode.episodeKey), elastic = false)
                    else -> error("tokenizeTemplate only emits Variable tokens for known names, got '${token.name}'")
                }
        }

    private data class ResolvedToken(
        val text: String,
        val elastic: Boolean,
    )
}
