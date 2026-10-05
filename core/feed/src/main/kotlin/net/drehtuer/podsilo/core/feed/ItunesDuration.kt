// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MAX_COMPONENTS = 3

/**
 * Parses `itunes:duration`, which the podcast namespace allows as plain seconds (`3600`),
 * `MM:SS` (`32:15`), or `HH:MM:SS` (`01:32:15`). Returns `null` for anything else -- CLAUDE.md
 * section 6/7: `itunes:duration` is notoriously unreliable, never invent a value for it.
 */
fun parseItunesDuration(raw: String?): Long? {
    val trimmed = raw?.trim().orEmpty()
    return parseComponents(trimmed)
        ?.let(::totalSeconds)
        ?.takeIf { it >= 0 }
        ?.times(MILLIS_PER_SECOND)
}

private fun parseComponents(trimmed: String): List<Long>? {
    val components = trimmed.split(":")
    if (components.size > MAX_COMPONENTS || components.any(String::isBlank)) return null
    // Every component non-negative, not just the total: `1:-30:00` would otherwise sum to a tidy
    // 30 minutes — a plausible-looking duration invented from garbage.
    val numbers = components.map { component -> component.toLongOrNull()?.takeIf { it >= 0 } }
    return if (numbers.contains(null)) null else numbers.filterNotNull()
}

private fun totalSeconds(numbers: List<Long>): Long =
    when (numbers.size) {
        1 -> numbers[0]
        2 -> numbers[0] * SECONDS_PER_MINUTE + numbers[1]
        else -> (numbers[0] * MINUTES_PER_HOUR + numbers[1]) * SECONDS_PER_MINUTE + numbers[2]
    }
