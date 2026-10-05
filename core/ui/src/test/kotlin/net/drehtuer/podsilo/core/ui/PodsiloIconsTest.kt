// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The allow-list in `UI.adoc` §18, checked rather than trusted.
 *
 * Every icon resolves to a real drawable — the artifact ships `VectorDrawable` XML rather than
 * `ImageVector`s, so a typo'd name is a `0` at runtime and an invisible icon, not a compile error.
 * And the three distinctions §18 says "make the UI lie if used interchangeably" are asserted to be
 * genuinely different glyphs.
 */
@RunWith(RobolectricTestRunner::class)
class PodsiloIconsTest {
    @get:Rule
    val compose = createComposeRule()

    private val all =
        mapOf(
            "Back" to PodsiloIcons.Back,
            "Settings" to PodsiloIcons.Settings,
            "Activity" to PodsiloIcons.Activity,
            "Overflow" to PodsiloIcons.Overflow,
            "ChevronRight" to PodsiloIcons.ChevronRight,
            "ChevronDown" to PodsiloIcons.ChevronDown,
            "Download" to PodsiloIcons.Download,
            "Played" to PodsiloIcons.Played,
            "Check" to PodsiloIcons.Check,
            "AllDone" to PodsiloIcons.AllDone,
            "HandledRemotely" to PodsiloIcons.HandledRemotely,
            "Close" to PodsiloIcons.Close,
            "Unchecked" to PodsiloIcons.Unchecked,
            "Checked" to PodsiloIcons.Checked,
            "Warning" to PodsiloIcons.Warning,
            "InputError" to PodsiloIcons.InputError,
            "Syncing" to PodsiloIcons.Syncing,
            "Waiting" to PodsiloIcons.Waiting,
            "Offline" to PodsiloIcons.Offline,
            "Empty" to PodsiloIcons.Empty,
            "ErrorLog" to PodsiloIcons.ErrorLog,
            "Copy" to PodsiloIcons.Copy,
            "Share" to PodsiloIcons.Share,
            "Clear" to PodsiloIcons.Clear,
            "OpenInBrowser" to PodsiloIcons.OpenInBrowser,
            "NoEnclosure" to PodsiloIcons.NoEnclosure,
        )

    @Test
    fun `every icon in the allow-list resolves to a real drawable`() {
        all.forEach { (name, id) -> assertNotEquals("$name resolved to 0", 0, id) }
    }

    @Test
    fun `the allow-list has exactly the icons §18 names`() {
        // 26 rows in the table. A new affordance means adding a row there before adding a glyph.
        // Was 27 until `server` lost its only call site to the brand lockup (`UI.adoc` §C4.2).
        assertEquals(26, all.size)
    }

    /**
     * `UI.adoc` §18 names pairs that "make the UI lie if used interchangeably" — `HandledRemotely`
     * against `Check` (the user did not make that decision here, §12.6) and `Warning` against
     * `InputError` (a queue condition against input the user can fix). Distinctness across the
     * whole list covers both pairs and every pair nobody has named yet.
     */
    @Test
    fun `every icon is distinct`() {
        assertTrue("two names share one glyph", all.values.toSet().size == all.size)
    }

    /**
     * The one way an icon reaches the screen, and its contract is the nullable description: `null`
     * beside its own label must not be announced, an icon-only control must be (`UI.adoc` §12.12).
     */
    @Test
    fun `an icon is announced exactly when it is given a description`() {
        compose.setContent {
            Row {
                PodsiloIcon(PodsiloIcons.Settings, contentDescription = "Settings")
                PodsiloIcon(PodsiloIcons.Back, contentDescription = null)
            }
        }

        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assertCountEquals(1)
    }
}
