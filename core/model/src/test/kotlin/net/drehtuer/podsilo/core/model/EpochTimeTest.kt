// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * `architecture.adoc` §5. The arithmetic here is the JDK's; what is worth pinning is that the two
 * unit-specific entry points really do treat their input differently, since confusing them is the
 * failure mode the whole object exists to prevent.
 */
class EpochTimeTest {
    @Test
    fun `millis and server seconds are not interchangeable`() {
        // The same number read both ways: 1 752 480 000 millis is 1970, 1 752 480 000 seconds is 2025.
        val ambiguous = 1_752_480_000L

        assertEquals(Instant.parse("1970-01-21T06:48:00Z"), EpochTime.ofMillis(ambiguous))
        assertEquals(Instant.parse("2025-07-14T08:00:00Z"), EpochTime.ofServerSeconds(ambiguous))
    }

    @Test
    fun `millis round-trip through Instant unchanged`() {
        val millis = 1_752_480_000_000L
        assertEquals(millis, EpochTime.toMillis(EpochTime.ofMillis(millis)))
    }

    @Test
    fun `epoch zero is a real value, not an absent one`() {
        assertEquals(Instant.EPOCH, EpochTime.ofMillis(0L))
        assertEquals(Instant.EPOCH, EpochTime.ofMillisOrNull(0L))
    }

    @Test
    fun `pre-epoch timestamps survive`() {
        // A feed can carry a nonsensical or genuinely old pubDate; nothing may clamp it to zero.
        assertEquals(Instant.parse("1969-12-31T23:59:59Z"), EpochTime.ofMillis(-1_000L))
    }

    @Test
    fun `null timestamps stay null rather than becoming epoch`() {
        assertNull(EpochTime.ofMillisOrNull(null))
    }

    @Test
    fun `an unknown duration is null, never zero`() {
        // itunes:duration is unreliable; the UI renders no duration part at all rather than "0 min".
        assertNull(EpochTime.durationOfMillis(null))
        assertEquals(Duration.ZERO, EpochTime.durationOfMillis(0L))
    }

    @Test
    fun `durations convert whole`() {
        assertEquals(Duration.ofMinutes(48), EpochTime.durationOfMillis(48L * 60 * 1000))
    }
}
