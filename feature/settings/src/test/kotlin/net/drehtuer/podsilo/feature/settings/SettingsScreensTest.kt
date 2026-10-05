// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * S4, S5 and S6 rendering, under Robolectric (Tier 1 — headless, no emulator).
 *
 * The assertions worth having here are the ones about what must **not** be on screen: no password
 * field anywhere in the connection dialog, and no `{ext}` chip in the naming editor.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val settingsEvents = mutableListOf<SettingsEvent>()
    private val connectEvents = mutableListOf<ConnectEvent>()
    private val namingEvents = mutableListOf<NamingEvent>()
    private val now = Instant.parse("2026-08-02T12:00:00Z")

    private fun renderSettings(state: SettingsUiState) {
        compose.setContent {
            SettingsScreen(state = state, onEvent = { settingsEvents += it }, onBack = {}, now = now)
        }
    }

    @Test
    fun `the About group carries the lockup beside the version and licence`() {
        // `UI.adoc` §C4.3: horizontal lockup, flush left, no card and no frame. GPL-3.0 and a
        // version string mean little without saying whose they are.
        renderSettings(SettingsUiState(version = "0.1.0"))

        compose.onNodeWithText("podsilo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Version 0.1.0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an unconnected instance row is empty rather than showing a placeholder`() {
        renderSettings(SettingsUiState(version = "0.1.0"))

        compose.onNodeWithText("Instance").assertIsDisplayed()
        compose.onNodeWithText("Connect Nextcloud").assertIsDisplayed()
        // The Account and Last sync rows are hidden entirely when nothing is connected (§7).
        compose.onAllNodes(hasText("Account")).assertCountEquals(0)
        compose.onAllNodes(hasText("Last sync", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `a connected instance shows the account and offers to change or disconnect`() {
        renderSettings(
            SettingsUiState(
                nextcloud =
                    NextcloudUi(
                        instanceUrl = "https://cloud.example.org",
                        loginName = "author",
                        lastSyncAt = now.minusSeconds(600),
                        outboxDepth = 3,
                    ),
                version = "0.1.0",
            ),
        )

        compose.onNodeWithText("https://cloud.example.org").assertIsDisplayed()
        compose.onNodeWithText("author").assertIsDisplayed()
        compose.onNode(hasText("3 actions pending", substring = true)).assertIsDisplayed()
        // Scrolled to rather than asserted in place: since `decisions/0025` the NEXTCLOUD group
        // also carries the two directional-sync rows, so these sit below the fold in the test's
        // viewport. The screen scrolls, so reachable is the property that matters.
        compose.onNodeWithText("Change Nextcloud instance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a revoked folder grant reads as a warning, not as unchosen`() {
        renderSettings(
            SettingsUiState(
                downloadFolder = FolderUi(label = "Podcasts", state = FolderState.REVOKED),
                version = "0.1.0",
            ),
        )

        compose.onNode(hasText("not available", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `Preview and apply is disabled while the older-than rule is off`() {
        // With no cutoff the button would have nothing to preview, and offering it invites a tap
        // that does nothing.
        renderSettings(SettingsUiState(markOldOlderThan = OlderThan.OFF, version = "0.1.0"))

        compose.onNodeWithText("Preview & apply").performClick()

        assertTrue(settingsEvents.none { it is SettingsEvent.BulkPreviewRequested })
    }

    @Test
    fun `the theme control commits on tap, with no Save button anywhere`() {
        renderSettings(SettingsUiState(theme = ThemePreference.SYSTEM, version = "0.1.0"))

        compose.onNodeWithText("Dark").performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.ThemeChanged(ThemePreference.DARK)), settingsEvents)
        compose.onAllNodes(hasText("Save")).assertCountEquals(0)
    }

    @Test
    fun `the bulk dialog names the count and says the state goes to Nextcloud`() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                pendingBulk =
                    BulkConfirmation(
                        scope =
                            net.drehtuer.podsilo.core.model.port.BulkScope(
                                net.drehtuer.podsilo.core.model.port.BulkScopeKind.ALL_UNDECIDED,
                            ),
                        perFeed = listOf(FeedCount("Der Podcast", 128), FeedCount("Lage der Nation", 94)),
                    ),
            ),
        )

        compose.onNode(hasText("Mark 222 episodes as played?", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("sent to Nextcloud", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(settingsEvents.contains(SettingsEvent.BulkCancelled))
    }

    /**
     * Tapping *Restore* must open a warning, never a file picker. The whole safeguard is that the
     * user reads what a restore does before choosing anything.
     */
    @Test
    fun `restore asks for confirmation before it asks for a file`() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                nextcloud = NextcloudUi(instanceUrl = "https://cloud.example.org", loginName = "podsilo"),
            ),
        )

        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()

        assertEquals(listOf(SettingsEvent.RestoreDatabaseClicked), settingsEvents)
    }

    @Test
    fun `the restore warning says it cannot be undone and that Nextcloud is untouched`() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                restoreConfirmationVisible = true,
                nextcloud = NextcloudUi(instanceUrl = "https://cloud.example.org", loginName = "podsilo"),
            ),
        )

        compose.onNode(hasText("cannot be undone", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Nextcloud account is not touched", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(settingsEvents.contains(SettingsEvent.RestoreCancelled))
    }

    /** The author's rule, on the row: an unconnected install may not open a backup file at all. */
    @Test
    fun `the restore row is dead and says why until Nextcloud is connected`() {
        renderSettings(SettingsUiState(version = "0.1.0"))

        compose.onNodeWithText("Connect Nextcloud first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()

        assertTrue(settingsEvents.none { it is SettingsEvent.RestoreDatabaseClicked })
    }

    /** A second tap mid-zip would start a second export over the same file. */
    @Test
    fun `both backup rows go dead while one is running`() {
        renderSettings(
            SettingsUiState(
                version = "0.1.0",
                archiveBusy = true,
                nextcloud = NextcloudUi(instanceUrl = "https://cloud.example.org", loginName = "podsilo"),
            ),
        )

        compose.onNodeWithText("Export database").performScrollTo().performClick()
        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()

        assertTrue(settingsEvents.isEmpty())
    }

    @Test
    fun `the remainder line keeps the listed numbers adding up to the title`() {
        val many = (1..8).map { FeedCount("Feed $it", 10) }
        val confirmation =
            BulkConfirmation(
                scope =
                    net.drehtuer.podsilo.core.model.port.BulkScope(
                        net.drehtuer.podsilo.core.model.port.BulkScopeKind.ALL_UNDECIDED,
                    ),
                perFeed = many,
            )

        assertEquals("… 3 more podcasts   30", remainderLine(confirmation))
        assertEquals(null, remainderLine(confirmation.copy(perFeed = many.take(3))))
    }

    @Test
    fun `the connection dialog has no password field, ever`() {
        // Login Flow v2 exclusively (CLAUDE.md §5). This assertion is the guarantee.
        compose.setContent {
            ConnectDialog(state = ConnectUiState(), onEvent = { connectEvents += it })
        }

        compose.onNodeWithText("Nextcloud address").assertIsDisplayed()
        compose.onAllNodes(hasText("Password", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Username", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `while awaiting authorization the dialog says what it is waiting for and keeps Cancel`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(phase = ConnectUiState.Phase.AwaitingAuthorization),
                onEvent = { connectEvents += it },
            )
        }

        compose.onNode(hasText("Waiting for authorization", substring = true)).assertIsDisplayed()
        // The primary button is gone; Cancel stays and aborts the poll (UI.adoc §8).
        compose.onAllNodes(hasText("Request authorization")).assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(connectEvents.contains(ConnectEvent.Cancel))
    }

    @Test
    fun `the confirmation names the account and offers a way out of it`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(phase = ConnectUiState.Phase.ConfirmingAccount("podsilo")),
                onEvent = { connectEvents += it },
            )
        }

        // The name is the question, not a detail buried in a sentence (`UI.adoc` §8).
        compose.onNodeWithText("Connect as podsilo?").assertIsDisplayed()
        compose.onNode(hasText("your browser was signed in to", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Use a different account").performClick()
        assertTrue(connectEvents.contains(ConnectEvent.RejectAccount))

        compose.onNodeWithText("Connect").performClick()
        assertTrue(connectEvents.contains(ConnectEvent.ConfirmAccount))
    }

    @Test
    fun `after rejecting, the dialog says the browser session is what has to change`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(host = "cloud.example.org", showSwitchAccountHint = true),
                onEvent = { connectEvents += it },
            )
        }

        // Retrying without logging out returns the same account, so the instruction has to say so.
        compose.onNode(hasText("Log out of Nextcloud", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Request authorization").assertIsDisplayed()
    }

    @Test
    fun `each connection failure has a plain sentence, never a stack trace`() {
        ConnectError.entries.forEach { error ->
            val message = error.message
            assertTrue("$error had an empty message", message.isNotBlank())
            assertTrue("$error read like a stack trace", !message.contains("Exception"))
        }
    }

    @Test
    fun `the naming editor offers only placeholders the engine resolves`() {
        compose.setContent {
            NamingScreen(
                state =
                    NamingUiState(
                        previews = listOf(NamingPreviewLine(PreviewCase.RECENT_EPISODE, "Der Podcast/x.mp3")),
                    ),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        // Twice: once as the folder template's value, once as the chip that inserts it.
        compose.onAllNodes(hasText("{podcast}")).assertCountEquals(2)
        // Absent on purpose: the extension is appended after resolution, not resolved (CLAUDE.md §6).
        compose.onAllNodes(hasText("{ext}")).assertCountEquals(0)
        compose.onNode(hasText("never renamed", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a chip inserts into the field that has focus, not always the file template`() {
        // Reported from the device: tapping a placeholder while editing the folder field appended
        // it to the *file* template instead.
        compose.setContent {
            NamingScreen(
                state = NamingUiState(folderTemplate = "{podcast}", fileTemplate = "{date}"),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        // Focus the *folder* field (matched by its label, not its contents), then tap a chip.
        compose.onNode(hasText("Folder template") and hasSetTextAction()).performClick()
        namingEvents.clear()
        compose.onNode(hasText("{title}") and hasClickAction() and !hasSetTextAction()).performClick()

        val folderEdits = namingEvents.filterIsInstance<NamingEvent.FolderTemplateChanged>()
        assertTrue("the chip did not reach the focused folder field: $namingEvents", folderEdits.isNotEmpty())
        assertTrue(
            "the chip was not inserted into the folder template",
            folderEdits.last().value.contains("{title}"),
        )
    }

    @Test
    fun `the screen says where the file extension comes from`() {
        // `{ext}` is deliberately not a chip (CLAUDE.md §6), but its absence read as an omission —
        // the preview grows a ".mp3" from nowhere.
        compose.setContent {
            NamingScreen(state = NamingUiState(), onEvent = { namingEvents += it }, onBack = {})
        }

        compose.onNode(hasText("added automatically", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an invalid template shows its reason under the field`() {
        compose.setContent {
            NamingScreen(
                state =
                    NamingUiState(
                        validation =
                            NamingUiState.Validation.Invalid(NamingField.FILE, "The file template can't be empty."),
                    ),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        compose.onNode(hasText("can't be empty", substring = true)).assertIsDisplayed()
    }

    /** Both bulk buttons open a preview, never a write — the dialog is the safeguard (`decisions/0013`). */
    @Test
    fun `each Preview button asks for a preview of its own scope`() {
        renderSettings(SettingsUiState(markOldOlderThan = OlderThan.MONTH_3, version = "0.1.0"))

        compose.onNodeWithText("Preview & apply").performScrollTo().performClick()
        compose.onNodeWithText("Preview & apply all").performScrollTo().performClick()

        assertEquals(
            listOf(
                SettingsEvent.BulkPreviewRequested(BulkScope(kind = BulkScopeKind.OLDER_THAN)),
                SettingsEvent.BulkPreviewRequested(BulkScope(kind = BulkScopeKind.ALL_UNDECIDED)),
            ),
            settingsEvents,
        )
    }

    /** "Immediately visible" (`UI.adoc` §8): the sentence is under the field, not only in S8. */
    @Test
    fun `an inline error shows its plain sentence under the field`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(host = "cloud example.org", inlineError = ConnectError.ADDRESS_HAS_SPACE),
                onEvent = { connectEvents += it },
            )
        }

        compose.onNodeWithText(ConnectError.ADDRESS_HAS_SPACE.message).assertIsDisplayed()
        // Still submittable: the error informs, it does not trap the user in the dialog.
        compose.onNodeWithText("Request authorization").performClick()
        assertTrue(connectEvents.contains(ConnectEvent.Submit))
    }

    @Test
    fun `changing an existing instance says the download history is kept`() {
        // The reassurance that matters before re-pointing the app: handled episodes stay handled.
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(host = "cloud.example.org", isChangingExisting = true),
                onEvent = { connectEvents += it },
            )
        }

        compose.onNodeWithText("Change Nextcloud instance").assertIsDisplayed()
        compose.onNode(hasText("download history is kept", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `while verifying gpoddersync the dialog says so rather than spinning silently`() {
        compose.setContent {
            ConnectDialog(
                state = ConnectUiState(phase = ConnectUiState.Phase.VerifyingGpodderSync),
                onEvent = { connectEvents += it },
            )
        }

        compose.onNodeWithText("Checking for GPodder Sync…").assertIsDisplayed()
        compose.onAllNodes(hasText("Request authorization")).assertCountEquals(0)
    }

    /** §6's live preview: every awkward case is labelled, so the author knows what each line proves. */
    @Test
    fun `every preview case is rendered under its own label`() {
        compose.setContent {
            NamingScreen(
                state =
                    NamingUiState(
                        previews = PreviewCase.entries.map { NamingPreviewLine(it, "Der Podcast/${it.name}.mp3") },
                    ),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        PreviewCase.entries.forEach { case ->
            compose.onNodeWithText(case.label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Der Podcast/${case.name}.mp3").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `with no field focused a chip goes into the file template`() {
        // The file template is the one people edit, so it is the default target.
        compose.setContent {
            NamingScreen(
                state = NamingUiState(folderTemplate = "{podcast}", fileTemplate = "{date}"),
                onEvent = { namingEvents += it },
                onBack = {},
            )
        }

        compose.onNode(hasText("{title}") and hasClickAction() and !hasSetTextAction()).performClick()

        assertTrue(namingEvents.none { it is NamingEvent.FolderTemplateChanged })
        val fileEdit = namingEvents.filterIsInstance<NamingEvent.FileTemplateChanged>().single()
        assertTrue(fileEdit.value, fileEdit.value.contains("{date}") && fileEdit.value.contains("{title}"))
    }
}
