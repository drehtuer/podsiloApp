// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * S4's two directional sync rows and their confirmation (`decisions/0025`), rendered.
 *
 * The view model's half — nothing runs until confirmed — is `DirectionalSyncViewModelTest`. This
 * half is what the user reads before agreeing: the push names its count and says it cannot be undone,
 * and the pull promises only what it can do.
 */
@RunWith(RobolectricTestRunner::class)
class DirectionalSyncScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<SettingsEvent>()
    private val now = Instant.parse("2026-08-14T12:00:00Z")
    private val connected = NextcloudUi(instanceUrl = "https://cloud.example.org", loginName = "author")

    private fun render(state: SettingsUiState) {
        compose.setContent {
            SettingsScreen(state = state, onEvent = { events += it }, onBack = {}, now = now)
        }
    }

    @Test
    fun `the rows are absent, not disabled, without an account`() {
        render(SettingsUiState(version = "0.1.0"))

        compose.onAllNodes(hasText("Apply Nextcloud's state here")).assertCountEquals(0)
        compose.onAllNodes(hasText("Send this device's state to Nextcloud")).assertCountEquals(0)
    }

    @Test
    fun `each row asks for its own direction`() {
        render(SettingsUiState(version = "0.1.0", nextcloud = connected))

        compose.onNodeWithText("Apply Nextcloud's state here").performScrollTo().performClick()
        compose.onNodeWithText("Send this device's state to Nextcloud").performScrollTo().performClick()

        assertEquals(
            listOf(
                SettingsEvent.DirectionalSyncRequested(SyncDirection.PULL),
                SettingsEvent.DirectionalSyncRequested(SyncDirection.PUSH),
            ),
            events,
        )
    }

    /** A second tap while a pass is running would queue a second full pass behind it. */
    @Test
    fun `both rows go dead while a directional pass is running`() {
        render(SettingsUiState(version = "0.1.0", nextcloud = connected, directionalSyncBusy = true))

        compose.onNodeWithText("Apply Nextcloud's state here").performScrollTo().performClick()
        compose.onNodeWithText("Send this device's state to Nextcloud").performScrollTo().performClick()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `the push names its count, warns it cannot be undone, and sends only on Send`() {
        render(
            SettingsUiState(
                version = "0.1.0",
                nextcloud = connected,
                pendingDirectionalSync = DirectionalSyncConfirmation(SyncDirection.PUSH, pushableCount = 37),
            ),
        )

        compose.onNodeWithText("Send 37 decisions to Nextcloud?").assertIsDisplayed()
        compose.onNode(hasText("cannot be undone", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Your other clients will see them", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Send").performClick()

        assertEquals(listOf(SettingsEvent.DirectionalSyncConfirmed), events)
    }

    @Test
    fun `the pull promises only to mark played, and Cancel runs nothing`() {
        render(
            SettingsUiState(
                version = "0.1.0",
                nextcloud = connected,
                pendingDirectionalSync = DirectionalSyncConfirmation(SyncDirection.PULL),
            ),
        )

        compose.onNodeWithText("Apply Nextcloud's state here?").assertIsDisplayed()
        compose.onNode(hasText("Nothing is unmarked, nothing is downloaded", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(SettingsEvent.DirectionalSyncCancelled), events)
    }
}
