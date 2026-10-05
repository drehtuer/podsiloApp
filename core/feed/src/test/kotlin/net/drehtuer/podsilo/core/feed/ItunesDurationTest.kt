// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItunesDurationTest {
    @Test
    fun `the three shapes the podcast namespace allows are parsed to milliseconds`() {
        val cases =
            mapOf(
                "3600" to 3_600_000L,
                "32:15" to (32 * 60 + 15) * 1000L,
                "01:32:15" to ((1 * 60 + 32) * 60 + 15) * 1000L,
                // Whitespace around the value is trimmed, not treated as garbage.
                "  3600  " to 3_600_000L,
            )

        cases.forEach { (raw, expected) -> assertEquals("'$raw'", expected, parseItunesDuration(raw)) }
    }

    /** `itunes:duration` is notoriously unreliable; anything unusable is null, never an invented value. */
    @Test
    fun `anything unusable yields null rather than a made-up duration`() {
        val unusable =
            listOf(
                null,
                "",
                "   ",
                "not a duration",
                "12:ab",
                // Too many components.
                "1:02:03:04",
                // A negative value is not a duration.
                "-5",
                // Nor is a negative *component*: these used to come back as 55 s and 30 min — a
                // plausible-looking duration invented from garbage, which CLAUDE.md §5 forbids.
                "1:-5",
                "1:-30:00",
            )

        unusable.forEach { raw -> assertNull("'$raw'", parseItunesDuration(raw)) }
    }
}
