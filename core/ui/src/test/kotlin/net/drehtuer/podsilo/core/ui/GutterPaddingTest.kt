// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two composables that turn the gutter arithmetic (`ListGutterTest`) into padding, read in a
 * real composition.
 *
 * Robolectric reports no system-gesture inset, so this is the "device that reserves nothing" case —
 * and it pins the promise `chromeGutterPadding`'s KDoc makes for it: the list sits on the 16 dp
 * grid and the chrome gains *nothing*, so the gutter changes no pixel on such a device. It is run in
 * both layout directions because each side is resolved through the layout direction separately.
 */
@RunWith(RobolectricTestRunner::class)
class GutterPaddingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `with no reserved inset the list sits on the grid and the chrome gains nothing, in either direction`() {
        val read = mutableMapOf<LayoutDirection, Pair<PaddingValues, PaddingValues>>()
        compose.setContent {
            LayoutDirection.entries.forEach { direction ->
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    read[direction] = listGutterPadding() to chromeGutterPadding()
                }
            }
        }
        compose.waitForIdle()

        LayoutDirection.entries.forEach { direction ->
            val (list, chrome) = checkNotNull(read[direction])
            assertEquals("list start, $direction", ListGutter, list.calculateStartPadding(direction))
            assertEquals("list end, $direction", ListGutter, list.calculateEndPadding(direction))
            assertEquals("chrome start, $direction", 0.dp, chrome.calculateStartPadding(direction))
            assertEquals("chrome end, $direction", 0.dp, chrome.calculateEndPadding(direction))
        }
    }
}
