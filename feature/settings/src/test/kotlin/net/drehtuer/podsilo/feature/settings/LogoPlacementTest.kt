// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import net.drehtuer.podsilo.core.ui.PODSILO_MARK_TEST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Where the brand mark appears across S4, S5 and S6 (`UI.adoc` §C4 and §5).
 *
 * One placement in this module — the About lockup — and two screens that must stay clear of it. The
 * companion tests are `LogoPlacementTest` in `:feature:episodes` (S1–S3) and `:app` (S7–S8).
 */
@RunWith(RobolectricTestRunner::class)
class LogoPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun marks() = compose.onAllNodesWithTag(PODSILO_MARK_TEST_TAG)

    @Test
    fun `S4 carries the mark once, in About, and not in its app bar`() {
        // §4.3 is the placement; §5 is the constraint — S4's app bar carries a back arrow and a
        // context title, and a mark beside them competes with both.
        compose.setContent {
            SettingsScreen(state = SettingsUiState(version = "0.1.0"), onEvent = {}, onBack = {}, now = now)
        }

        marks().assertCountEquals(1)
    }

    @Test
    fun `S5 has no mark, in any phase of the login flow`() {
        // Deliberately not asserted by counting the word "podsilo": the account name Nextcloud hands
        // back can be anything, and `podsilo` is exactly what the confirmation test uses. The tag is
        // the only reliable way to ask this question.
        var phase: ConnectUiState.Phase by mutableStateOf(ConnectUiState.Phase.Editing)
        compose.setContent { ConnectDialog(state = ConnectUiState(phase = phase), onEvent = {}) }

        listOf(
            ConnectUiState.Phase.Editing,
            ConnectUiState.Phase.RequestingFlow,
            ConnectUiState.Phase.AwaitingAuthorization,
            ConnectUiState.Phase.VerifyingGpodderSync,
            ConnectUiState.Phase.ConfirmingAccount("podsilo"),
        ).forEach {
            phase = it
            compose.waitForIdle()
            marks().assertCountEquals(0)
        }
    }

    @Test
    fun `S6 has no mark`() {
        compose.setContent { NamingScreen(state = NamingUiState(), onEvent = {}, onBack = {}) }

        marks().assertCountEquals(0)
    }
}
