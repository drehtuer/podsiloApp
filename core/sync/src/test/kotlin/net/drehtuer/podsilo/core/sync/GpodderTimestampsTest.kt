// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GpodderTimestampsTest {
    @Test
    fun `formats an epoch millis instant as UTC iso-8601 without an offset`() {
        val epochMillis = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals("2026-07-14T09:00:00", epochMillis.toGpodderTimestamp())
    }

    @Test
    fun `round-trips through parse`() {
        val epochMillis = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(epochMillis, parseGpodderTimestamp(epochMillis.toGpodderTimestamp()))
    }

    @Test
    fun `parses the bare form the gpodder API README documents`() {
        val expected = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(expected, parseGpodderTimestamp("2026-07-14T09:00:00"))
    }

    @Test
    fun `parses the offset form nextcloud-gpodder actually emits`() {
        // PHP format("c") — verified against thrillfall/nextcloud-gpodder's EpisodeActionRepository.
        val expected = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(expected, parseGpodderTimestamp("2026-07-14T09:00:00+00:00"))
    }

    @Test
    fun `parses the trailing-Z form opodsync emits`() {
        val expected = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(expected, parseGpodderTimestamp("2026-07-14T09:00:00Z"))
    }

    @Test
    fun `a non-UTC offset shifts the instant rather than being silently discarded`() {
        // Regression guard: parsing to LocalDateTime instead of OffsetDateTime would drop the
        // "+02:00" and read this as 09:00 UTC — a two-hour error invisible to any test that only
        // ever uses UTC-equivalent timestamps.
        val expected = Instant.parse("2026-07-14T07:00:00Z").toEpochMilli()
        assertEquals(expected, parseGpodderTimestamp("2026-07-14T09:00:00+02:00"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val expected = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(expected, parseGpodderTimestamp("  2026-07-14T09:00:00Z  "))
    }

    @Test
    fun `malformed timestamps parse to null rather than throwing`() {
        // A date without a time component is not "midnight" -- it is not the documented shape.
        listOf("not a timestamp", "2026-07-14", "").forEach { raw ->
            assertNull("'$raw'", parseGpodderTimestamp(raw))
        }
    }
}
