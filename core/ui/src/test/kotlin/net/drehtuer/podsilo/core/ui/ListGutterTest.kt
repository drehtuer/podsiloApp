// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #92: the arithmetic behind the gutter that keeps a swipeable row off the system's own
 * gesture strips.
 *
 * The rule is "never narrower than the design grid, never narrower than the strip the device
 * actually reserves" — a fixed 16 dp would still leave a live swipe surface under the back gesture
 * on any device that reserves more, which is the collision the issue reported.
 */
class ListGutterTest {
    @Test
    fun `the gutter is the wider of the design grid and the system's own strip`() {
        // reserved inset -> gutter
        val cases =
            listOf(
                // A device that reserves nothing still gets the design grid.
                0.dp to ListGutter,
                // Less than the grid still gets the grid.
                8.dp to 16.dp,
                // Exactly the grid is unchanged.
                16.dp to 16.dp,
                // More than the grid wins — the case a fixed 16 dp got wrong.
                24.dp to 24.dp,
            )
        cases.forEach { (inset, expected) -> assertEquals("inset=$inset", expected, gutterFor(inset)) }
    }

    /**
     * The half the JVM could not see. Robolectric reports a zero gesture inset, so the gutter is
     * exactly the 16 dp grid there and the chrome needs nothing added — which is why the emulator
     * was the first place the chips and the rows were visibly on two different grids (issue #92).
     */
    @Test
    fun `chrome adds only what the gutter has beyond its own row padding`() {
        assertEquals(0.dp, chromeGutterFor(16.dp))
        assertEquals(8.dp, chromeGutterFor(24.dp))
        assertEquals(14.dp, chromeGutterFor(30.dp))
    }

    @Test
    fun `a gutter narrower than the grid never pulls the chrome inward`() {
        assertEquals(0.dp, chromeGutterFor(0.dp))
        assertEquals(0.dp, chromeGutterFor(8.dp))
    }

    @Test
    fun `the minimum is overridable for a caller with a wider grid`() {
        assertEquals(32.dp, gutterFor(systemGestureInset = 10.dp, minimum = 32.dp))
    }
}
