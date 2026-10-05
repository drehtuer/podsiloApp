// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S5. The load-bearing test in this file is the last one: **the app password is never stored unless
 * gpoddersync answered 200**, because connecting to a Nextcloud without it leaves the user with an
 * app that silently syncs nothing (`UI.adoc` §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val settings = FakeSettingsRepository()
    private val client = FakeLoginFlowClient()
    private val syncTrigger = RecordingSyncTrigger()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val log = FakeLogRepository()

    /**
     * A view model whose dialog is **on screen**, which is the state every test here but the
     * lifecycle ones assumes: you cannot tap *Request authorization* on a dialog you cannot see.
     *
     * The foreground event is not a default on the view model, deliberately — it starts `false`, so
     * a host that forgets to wire the lifecycle polls never rather than polling in the background,
     * which is the failure `decisions/0020` exists to prevent.
     */
    private fun viewModel() =
        ConnectViewModel(client, settings, syncTrigger, log).also {
            it.onEvent(ConnectEvent.ForegroundChanged(inForeground = true))
        }

    @Test
    fun `a successful flow stores the credentials and kicks off the first sync`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)

                // The browser is opened by the host, not by :core:gpodder (`architecture.adoc` §2).
                assertEquals(
                    ConnectEffect.OpenBrowser("https://cloud.example.org/login/flow"),
                    awaitItem(),
                )
                // A granted flow no longer connects on its own: the account is confirmed first
                // (`UI.adoc` §8).
                assertNull(settings.storedCredentials)
                viewModel.onEvent(ConnectEvent.ConfirmAccount)
                assertEquals(ConnectEffect.Connected, awaitItem())
            }

            // The server's own canonical URL, not the typed one — a reverse proxy legitimately
            // returns a different host.
            assertEquals(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
                settings.storedCredentials,
            )
            assertEquals(1, syncTrigger.syncs)
            // Confirming stores the password — and must never revoke the one it just stored.
            assertTrue("the stored password must not be deleted from the server", client.revoked.isEmpty())
        }

    @Test
    fun `a typed scheme is stripped rather than rejected`() =
        runTest {
            // The field renders a fixed https:// prefix, so a pasted URL must not double it.
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("https://cloud.example.org/"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("https://cloud.example.org"), client.startedWith)
        }

    @Test
    fun `a deliberate http scheme is honoured, not silently upgraded`() =
        runTest {
            // A self-hosted instance on a LAN is a real setup; upgrading it would fail confusingly.
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("http://nextcloud.lan"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(listOf("http://nextcloud.lan"), client.startedWith)
        }

    /**
     * "Immediately visible" is the requirement, and it is a behavioural one: the fault has to show
     * while the field is being typed into, not after a Connect that never leaves the device.
     */
    @Test
    fun `a bad address reports itself while it is being typed, before any Connect`() {
        val viewModel = viewModel()

        viewModel.onEvent(ConnectEvent.HostChanged("cloud drehtuer.net"))

        assertEquals(ConnectError.ADDRESS_HAS_SPACE, viewModel.state.value.inlineError)
        assertTrue("nothing may be contacted on a keystroke", client.startedWith.isEmpty())
    }

    /** An untouched field is not a mistake, and must not be shouted at as one. */
    @Test
    fun `an empty field stays silent`() {
        val viewModel = viewModel()

        viewModel.onEvent(ConnectEvent.HostChanged(""))

        assertNull(viewModel.state.value.inlineError)
    }

    /** And it clears itself the moment the address becomes typeable again. */
    @Test
    fun `fixing the address clears the message`() {
        val viewModel = viewModel()

        viewModel.onEvent(ConnectEvent.HostChanged("cloud drehtuer.net"))
        viewModel.onEvent(ConnectEvent.HostChanged("cloud.drehtuer.net"))

        assertNull(viewModel.state.value.inlineError)
    }

    /**
     * The address a phone keyboard actually produces, and the one this check exists for: a space
     * after "cloud" cost a session of looking at a VPN's MTU before the error log was read
     * (`journal.adoc`, 2026-08-23). It used to report as `UNREACHABLE` — the same word a real
     * network failure gets — which is what sent the reader to the network in the first place.
     */
    @Test
    fun `a space in the address says so, rather than saying the address is unreachable`() {
        assertEquals(ConnectError.ADDRESS_HAS_SPACE, hostProblem("cloud drehtuer.net"))
        assertEquals(ConnectError.ADDRESS_HAS_SPACE, hostProblem("cloud example org"))
    }

    @Test
    fun `an address with nothing a host can be read out of is invalid, not unreachable`() {
        assertEquals(ConnectError.ADDRESS_INVALID, hostProblem(""))
        assertEquals(ConnectError.ADDRESS_INVALID, hostProblem("https://"))
        assertEquals(ConnectError.ADDRESS_INVALID, hostProblem("://"))
    }

    /**
     * The permissive half, and the more important one to pin: this check exists to make a typo
     * visible, not to have opinions about the author's network. A single-label LAN name, a port, an
     * IPv4 literal and a subdirectory install are all real Nextcloud setups.
     */
    @Test
    fun `real addresses are left alone`() {
        assertNull(hostProblem("cloud.example.org"))
        assertNull("a subdirectory install is legal", hostProblem("example.org/nextcloud"))
        assertNull("a single-label LAN name is legal", hostProblem("nextcloud"))
        assertNull("a port is legal", hostProblem("192.168.1.5:8080"))
        assertNull("a typed scheme is stripped, not rejected", hostProblem("https://cloud.example.org"))
    }

    @Test
    fun `an address that does not resolve says check the spelling, not 'not a Nextcloud'`() =
        runTest {
            // Found by running the manual probe against a host with no DNS record: every start
            // failure used to collapse into NOT_NEXTCLOUD, which sends the user to check their
            // server instead of their typing (UI.adoc §8's table exists to prevent exactly this).
            client.startResult =
                Result.failure(LoginFlowException(LoginFlowFailure.UNREACHABLE, "Name or service not known"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.UNREACHABLE, viewModel.state.value.inlineError)
        }

    /**
     * Every typed failure keeps its own sentence, whichever step it came from; only an *untyped* one
     * degrades to the step's most likely cause. Collapsing them is the bug `UI.adoc` §8's table
     * exists to prevent.
     */
    @Test
    fun `each typed failure maps to its own message and only an untyped one falls back`() {
        val expected =
            mapOf(
                LoginFlowFailure.UNREACHABLE to ConnectError.UNREACHABLE,
                LoginFlowFailure.TIMED_OUT to ConnectError.TIMED_OUT,
                LoginFlowFailure.CLEARTEXT_BLOCKED to ConnectError.CLEARTEXT_BLOCKED,
                LoginFlowFailure.TLS to ConnectError.TLS,
                LoginFlowFailure.NOT_NEXTCLOUD to ConnectError.NOT_NEXTCLOUD,
                LoginFlowFailure.NO_GPODDERSYNC to ConnectError.NO_GPODDERSYNC,
                LoginFlowFailure.UNAUTHORIZED to ConnectError.UNAUTHORIZED,
                LoginFlowFailure.ABANDONED to ConnectError.ABANDONED,
            )
        assertEquals("every failure kind is in the table", LoginFlowFailure.entries.toSet(), expected.keys)

        expected.forEach { (failure, error) ->
            // ADDRESS_INVALID is no failure's mapping, so a typed failure that silently fell through
            // to the fallback would show up here.
            val mapped = LoginFlowException(failure, "x").asConnectError(fallback = ConnectError.ADDRESS_INVALID)
            assertEquals("$failure", error, mapped)
        }
        assertEquals(ConnectError.NOT_NEXTCLOUD, IllegalStateException("?").asConnectError(ConnectError.NOT_NEXTCLOUD))
        assertEquals(ConnectError.ABANDONED, IllegalStateException("?").asConnectError(ConnectError.ABANDONED))
    }

    @Test
    fun `a server that is not a Nextcloud says so and stores nothing`() =
        runTest {
            client.startResult = Result.failure(IllegalStateException("404"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.NOT_NEXTCLOUD, viewModel.state.value.inlineError)
            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            assertNull(settings.storedCredentials)
        }

    @Test
    fun `an abandoned authorization returns to editing with the host intact`() =
        runTest {
            client.pollResult = Result.failure(IllegalStateException("timeout"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.ABANDONED, viewModel.state.value.inlineError)
            assertEquals("cloud.example.org", viewModel.state.value.host)
            assertNull(settings.storedCredentials)
        }

    @Test
    fun `a Nextcloud without gpoddersync never gets the app password stored`() =
        runTest {
            // The whole point of the third step: a completed login flow proves the server is a
            // Nextcloud and the password works, and says nothing about gpoddersync being installed.
            client.verifyResult = Result.failure(IllegalStateException("404"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertEquals(ConnectError.NO_GPODDERSYNC, viewModel.state.value.inlineError)
            assertNull("the app password must be discarded, not stored", settings.storedCredentials)
            assertEquals(0, syncTrigger.syncs)
        }

    @Test
    fun `the field is read-only while a request is in flight, and editable again after a failure`() =
        runTest {
            // Accepting an edit mid-flow would leave the poll running against a different host than
            // the one on screen.
            client.suspendPoll = true
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                assertEquals(ConnectUiState.Phase.AwaitingAuthorization, viewModel.state.value.phase)

                viewModel.onEvent(ConnectEvent.HostChanged("other.example.org"))
                assertEquals("cloud.example.org", viewModel.state.value.host)

                // The verify step fails, which returns the dialog to Editing.
                client.verifyResult = Result.failure(IllegalStateException("404"))
                client.grantAccess()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            viewModel.onEvent(ConnectEvent.HostChanged("other.example.org"))
            assertEquals("other.example.org", viewModel.state.value.host)
        }

    @Test
    fun `changing an existing instance pre-fills the host without its scheme`() =
        runTest {
            settings.setNextcloudCredentials(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
            )
            val viewModel = viewModel()

            viewModel.prefillFromCurrentAccount()

            assertEquals("cloud.example.org", viewModel.state.value.host)
            assertTrue(viewModel.state.value.isChangingExisting)
        }

    /**
     * `UI.adoc` §8 has always said the inline errors are "each also written to S8". They were
     * not, and the cost showed up the first time a connection failed against an unfamiliar server:
     * the dialog says "Can't reach that address", which is the same six words whether DNS failed,
     * the host is unroutable, or Android refused a cleartext URL the *server* asked for. Only the
     * underlying message tells those apart, and it was being dropped.
     */
    @Test
    fun `a failed connection records the underlying reason in the error log`() =
        runTest {
            client.startResult =
                Result.failure(
                    LoginFlowException(
                        LoginFlowFailure.CLEARTEXT_BLOCKED,
                        "CLEARTEXT communication to cloud.example.org not permitted",
                    ),
                )
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                cancelAndIgnoreRemainingEvents()
            }

            val entry = log.recorded.single()
            assertEquals(LogCategory.AUTH, entry.category)
            assertTrue("names the failure kind, got '${entry.message}'", entry.message.contains("CLEARTEXT_BLOCKED"))
            // The part that makes it diagnosable: the host and the actual refusal, not a category.
            assertEquals("CLEARTEXT communication to cloud.example.org not permitted", entry.detail)
        }

    /**
     * The Pixel 10a bug (`decisions/0020`).
     *
     * `start()` succeeds and the browser opens — which *backgrounds this app*. On Android 17 the
     * backgrounded process could not resolve the host, and one `UnknownHostException` killed the
     * whole poll while the browser still said "access granted". Nothing may poll while the user is
     * away, so the failure has no opportunity to happen.
     */
    @Test
    fun `backgrounding stops the poll instead of failing the flow`() =
        runTest {
            client.suspendPoll = true
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                assertEquals(ConnectEffect.OpenBrowser("https://cloud.example.org/login/flow"), awaitItem())

                // Opening the browser backgrounds us — this is not an edge case, it is every login.
                viewModel.onEvent(ConnectEvent.ForegroundChanged(inForeground = false))
                expectNoEvents()
            }

            assertEquals("the wait must be cancelled, not left running", 1, client.pollsCancelled)
            // Still waiting, NOT failed: the old code turned this into "Can't reach that address".
            assertEquals(ConnectUiState.Phase.AwaitingAuthorization, viewModel.state.value.phase)
            assertNull(viewModel.state.value.inlineError)
            assertNull(settings.storedCredentials)
        }

    @Test
    fun `returning to the foreground resumes the poll and completes the login`() =
        runTest {
            client.suspendPoll = true
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.ForegroundChanged(inForeground = false))

                // The user grants in the browser and comes back — what the design relies on.
                viewModel.onEvent(ConnectEvent.ForegroundChanged(inForeground = true))
                client.grantAccess()

                assertEquals(
                    ConnectUiState.Phase.ConfirmingAccount("author"),
                    viewModel.state.value.phase,
                )
                viewModel.onEvent(ConnectEvent.ConfirmAccount)
                assertEquals(ConnectEffect.Connected, awaitItem())
            }

            assertEquals(
                NextcloudCredentials("https://cloud.example.org", "author", "app-password"),
                settings.storedCredentials,
            )
        }

    /** Two `ON_START`s in a row (a config change, a shade pull) must not start a second poll. */
    @Test
    fun `a repeated foreground event does not start a second poll`() =
        runTest {
            client.suspendPoll = true
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.ForegroundChanged(inForeground = true))
                viewModel.onEvent(ConnectEvent.ForegroundChanged(inForeground = true))
                expectNoEvents()
            }

            assertEquals("one flow, one poll", 1, client.pollCount)
        }

    @Test
    fun `cancel from the editing state dismisses the dialog`() =
        runTest {
            val viewModel = viewModel()

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Cancel)

                assertEquals(ConnectEffect.Dismiss, awaitItem())
            }
        }

    /**
     * The reported bug, as a test (`UI.adoc` §8).
     *
     * Login Flow v2 returns whichever account the *browser* was signed into and offers no chooser,
     * so the app's only defence is to name it and stop. This asserts the stopping: the flow is fully
     * granted and verified, and still nothing is stored and no sync is triggered.
     */
    @Test
    fun `a granted flow names the account and stores nothing until it is confirmed`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                assertEquals(ConnectEffect.OpenBrowser("https://cloud.example.org/login/flow"), awaitItem())
                expectNoEvents()
            }

            // The server's own loginName, never anything the app guessed or defaulted to.
            assertEquals(
                ConnectUiState.Phase.ConfirmingAccount("author"),
                viewModel.state.value.phase,
            )
            assertNull(settings.storedCredentials)
            assertEquals(0, syncTrigger.syncs)
        }

    @Test
    fun `rejecting the account stores nothing and opens the server so the session can be ended`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                assertEquals(ConnectEffect.OpenBrowser("https://cloud.example.org/login/flow"), awaitItem())

                viewModel.onEvent(ConnectEvent.RejectAccount)

                // The server root, not the flow URL: retrying the flow against a live session
                // returns the same account, so the browser is where the fix has to happen.
                assertEquals(ConnectEffect.OpenBrowser("https://cloud.example.org"), awaitItem())
            }

            assertNull(settings.storedCredentials)
            assertEquals(0, syncTrigger.syncs)
            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            assertTrue(viewModel.state.value.showSwitchAccountHint)
            // The address survives, because it was never the wrong part.
            assertEquals("cloud.example.org", viewModel.state.value.host)
        }

    /**
     * Nextcloud issues the app password *before* the user is asked whether it is the right account,
     * so declining used to leave a live password listed under *Security* belonging to an account
     * they had just refused. Deleting it needs that same password, so this is the last moment the
     * app can do it at all.
     */
    @Test
    fun `rejecting the account revokes the app password Nextcloud already issued`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.RejectAccount)
                skipItems(1)
            }

            assertEquals(1, client.revoked.size)
            assertEquals("app-password", client.revoked.single().appPassword)
            assertNull("revoking is not storing", settings.storedCredentials)
        }

    /**
     * Best-effort, by contract: an old Nextcloud without the endpoint, or one that cannot be
     * reached, leaves exactly the harmless hand-revocable leftover that existed before this
     * feature — and must not change a single thing the user sees while declining.
     */
    @Test
    fun `a failed revoke is logged and changes nothing about the rejection`() =
        runTest {
            client.revokeResult = Result.failure(IllegalStateException("HTTP 404"))
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.RejectAccount)

                assertEquals(ConnectEffect.OpenBrowser("https://cloud.example.org"), awaitItem())
            }

            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            assertTrue(viewModel.state.value.showSwitchAccountHint)
            assertNull(settings.storedCredentials)
            assertTrue(
                "the user cannot act on this, so it belongs in the log and nowhere else",
                log.recorded.any { it.category == LogCategory.AUTH && it.message.contains("could not be deleted") },
            )
        }

    /**
     * Backing out of the confirmation abandons the same grant, so it is revoked on the same terms —
     * and the discarded password must not be left in memory for a later confirmation to store.
     */
    @Test
    fun `cancelling at the confirmation revokes the grant and discards it`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.Cancel)
                viewModel.onEvent(ConnectEvent.ConfirmAccount)

                expectNoEvents()
            }

            assertEquals(1, client.revoked.size)
            assertNull(settings.storedCredentials)
        }

    /**
     * The dangerous version of rejecting: the discarded password must not be lying around for a
     * later confirmation to pick up. Without clearing it, *Use a different account* followed by
     * `ConfirmAccount` would store exactly the account the user just refused.
     */
    @Test
    fun `a rejected account cannot be confirmed afterwards`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud.example.org"))

            viewModel.effect.test {
                viewModel.onEvent(ConnectEvent.Submit)
                skipItems(1)
                viewModel.onEvent(ConnectEvent.RejectAccount)
                skipItems(1)

                viewModel.onEvent(ConnectEvent.ConfirmAccount)

                expectNoEvents()
            }

            assertNull(settings.storedCredentials)
            assertEquals(0, syncTrigger.syncs)
        }

    /**
     * Submitting an address the field already rejected contacts nothing — a keyboard *Go* is the
     * one way to submit that does not pass the button — and still leaves the reason in the log.
     */
    @Test
    fun `submitting a rejected address contacts nothing and logs why`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(ConnectEvent.HostChanged("cloud drehtuer.net"))

            viewModel.onEvent(ConnectEvent.Submit)

            assertTrue("nothing may be contacted", client.startedWith.isEmpty())
            assertEquals(ConnectError.ADDRESS_HAS_SPACE, viewModel.state.value.inlineError)
            assertEquals(ConnectUiState.Phase.Editing, viewModel.state.value.phase)
            assertTrue(
                log.recorded
                    .single()
                    .message
                    .contains("ADDRESS_HAS_SPACE"),
            )
        }
}
