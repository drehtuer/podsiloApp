// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val LOGIN_FLOW_PATH = "index.php/login/v2"
private const val APP_PASSWORD_PATH = "ocs/v2.php/core/apppassword"
private const val HTTP_NOT_FOUND = 404
private const val HTTP_OK = 200

private val loginJson = Json { ignoreUnknownKeys = true }

/**
 * Nextcloud **Login Flow v2** (`UI.adoc` §8). The only way this app ever obtains credentials:
 * the user authenticates in a browser against their own server, and what comes back is an **app
 * password**, never their account password (CLAUDE.md §5).
 *
 * Stays in the JVM module (`architecture.adoc` §2) — nothing here touches an Android API. Opening
 * the browser is the UI's job, delivered as a one-shot effect; this client only starts the flow,
 * polls it, and verifies the result.
 *
 * @property pollInterval how long to wait between polls. Injected so the tests do not sleep
 *   (CLAUDE.md §7 forbids `Thread.sleep` for synchronisation).
 */
class RetrofitNextcloudLoginFlowClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val pollInterval: Duration = 3.seconds,
    private val maxPollAttempts: Int = MAX_POLL_ATTEMPTS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NextcloudLoginFlowClient {
    override suspend fun start(baseUrl: String): Result<LoginFlow> =
        runCatchingRequest(ioDispatcher) {
            val root =
                baseUrl.normalisedRoot()
                    ?: throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "'$baseUrl' is not a usable address")

            val request =
                Request
                    .Builder()
                    .url(root.newBuilder().addPathSegments(LOGIN_FLOW_PATH).build())
                    // Nextcloud requires a POST with no body here; an empty one keeps OkHttp happy.
                    .post(ByteArray(0).toRequestBody())
                    .header("User-Agent", USER_AGENT)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // A Nextcloud always answers this path. Anything else — a 404, an HTML error
                    // page from a reverse proxy, someone's blog — means the address is wrong, which
                    // is a different message to the user than a refused authorization.
                    throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "HTTP ${response.code}")
                }
                val body: String = response.body.string()
                val dto = loginJson.decodeFromString(LoginFlowStartDto.serializer(), body)
                // Host and path come from the server rather than being rebuilt locally — a Nextcloud
                // behind a reverse proxy can legitimately answer on a different host. The *scheme*
                // does not: see [keepingSchemeAtLeastAsSecureAs].
                val secure = root.isHttps
                LoginFlow(
                    loginUrl = dto.login.keepingSchemeAtLeastAsSecureAs(secure),
                    pollEndpoint = dto.poll.endpoint.keepingSchemeAtLeastAsSecureAs(secure),
                    token = dto.poll.token,
                )
            }
        }

    override suspend fun poll(flow: LoginFlow): Result<LoginResult> =
        runCatchingRequest(ioDispatcher) {
            // The last network error seen, so an exhausted poll can say *why* rather than blaming
            // the user for not completing an authorization they may well have completed.
            var lastNetworkFailure: IOException? = null

            repeat(maxPollAttempts) {
                val request =
                    Request
                        .Builder()
                        .url(flow.pollEndpoint)
                        .post(FormBody.Builder().add("token", flow.token).build())
                        .header("User-Agent", USER_AGENT)
                        .build()

                // A NETWORK FAILURE ON ONE ATTEMPT IS NOT THE END OF THE FLOW.
                //
                // This whole loop used to sit inside `runCatchingRequest` with nothing catching per
                // attempt, so one `IOException` — a DNS blip, a Wi-Fi/mobile handover — abandoned all
                // 200 attempts and reported "can't reach that address" while the user was still
                // completing the grant in their browser. Since `decisions/0020` the poll only
                // runs in the foreground, which removes the cause that was actually biting; this
                // keeps a *foreground* blip from costing the flow too. `execute()` is the only thing
                // guarded — a malformed body or an unexpected status still fails immediately, because
                // those will not fix themselves by asking again.
                val response =
                    try {
                        httpClient.newCall(request).execute()
                    } catch (io: IOException) {
                        lastNetworkFailure = io
                        delay(pollInterval)
                        return@repeat
                    }

                response.use { response ->
                    when (response.code) {
                        // 404 is Nextcloud's "not granted yet" — the documented pending state, not
                        // an error. Treating it as one would abandon every flow on the first poll.
                        HTTP_NOT_FOUND -> Unit
                        HTTP_OK -> {
                            val body: String = response.body.string()
                            val dto = loginJson.decodeFromString(LoginPollDto.serializer(), body)
                            return@runCatchingRequest LoginResult(
                                // The scheme this poll was made over is the floor. `dto.server` is
                                // the URL every later request uses, and it is stored — a downgrade
                                // here would put the app password on the wire in plaintext forever
                                // after, not just once.
                                serverUrl =
                                    dto.server.keepingSchemeAtLeastAsSecureAs(
                                        flow.pollEndpoint.startsWith("https://"),
                                    ),
                                loginName = dto.loginName,
                                appPassword = dto.appPassword,
                            )
                        }
                        else -> throw LoginFlowException(LoginFlowFailure.ABANDONED, "HTTP ${response.code}")
                    }
                }
                delay(pollInterval)
            }
            // Exhausted. If the last thing that happened was a network failure, say that rather than
            // "authorization wasn't completed" — the user may well have completed it.
            lastNetworkFailure?.let { throw it }
            throw LoginFlowException(LoginFlowFailure.ABANDONED, "authorization was not completed in time")
        }

    override suspend fun verifyGpodderSync(credentials: NextcloudCredentials): Result<Unit> =
        runCatchingRequest(ioDispatcher) {
            val root =
                credentials.serverUrl.normalisedRoot()
                    ?: throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "unusable server URL")

            val request =
                Request
                    .Builder()
                    .url(root.newBuilder().addPathSegments("index.php/apps/gpoddersync/subscriptions").build())
                    .header("Authorization", Credentials.basic(credentials.username, credentials.appPassword))
                    .header("User-Agent", USER_AGENT)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Unit
                    // A completed login flow proves the server is a Nextcloud and the password
                    // works. It says nothing about gpoddersync being installed — and connecting
                    // without it would leave the user with an app that silently syncs nothing.
                    response.code == HTTP_NOT_FOUND ->
                        throw LoginFlowException(LoginFlowFailure.NO_GPODDERSYNC, "HTTP 404 at the gpoddersync path")
                    response.code == HTTP_UNAUTHORIZED ->
                        throw LoginFlowException(LoginFlowFailure.UNAUTHORIZED, "HTTP 401")
                    else -> throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "HTTP ${response.code}")
                }
            }
        }

    override suspend fun revokeAppPassword(credentials: NextcloudCredentials): Result<Unit> =
        runCatchingRequest(ioDispatcher) {
            val root =
                credentials.serverUrl.normalisedRoot()
                    ?: throw LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "unusable server URL")

            val request =
                Request
                    .Builder()
                    .url(root.newBuilder().addPathSegments(APP_PASSWORD_PATH).build())
                    .delete()
                    .header("Authorization", Credentials.basic(credentials.username, credentials.appPassword))
                    // OCS refuses any request without this header with a 401 that looks exactly like
                    // a wrong password. It is the header, not the credentials, and it is the single
                    // easiest thing to get wrong about this API.
                    .header("OCS-APIRequest", "true")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404 on an older Nextcloud without the endpoint, 401 if the password is already
                    // gone. Both are "nothing to clean up here", and neither is the caller's problem
                    // — see the port's KDoc on why this is best-effort.
                    throw LoginFlowException(LoginFlowFailure.UNAUTHORIZED, "HTTP ${response.code}")
                }
            }
        }

    companion object {
        const val MAX_POLL_ATTEMPTS: Int = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val USER_AGENT = "Podsilo"
    }
}

/**
 * Upgrades a **server-supplied** URL to `https` when the conversation so far has been over `https`.
 * Never downgrades, and never touches a URL that is already `https`.
 *
 * Login Flow v2 hands back three URLs the client is obliged to use — the browser page, the poll
 * endpoint, and the `server` that every later request is built on — and Nextcloud derives them from
 * its own `overwriteprotocol` / `overwrite.cli.url` settings. Behind a reverse proxy that terminates
 * TLS, those are very often left as `http`, so a server reached perfectly well over `https` reports
 * itself as plaintext. Android then refuses the connection and the app fails **after** a successful
 * grant, on a URL the user never typed and cannot see.
 *
 * Following that scheme verbatim would be worse than failing: `serverUrl` is persisted, so one
 * misconfigured field would put the app password on the wire in cleartext on every sync from then
 * on. Upgrading is the only direction that is safe in both cases — if the host genuinely has no TLS
 * listener the request fails loudly, which is the correct outcome when the requirement is that this
 * conversation is encrypted.
 *
 * A user who *explicitly typed* `http://` gets [secure] = false and their choice is left alone; that
 * request is then Android's to refuse, and it reports [LoginFlowFailure.CLEARTEXT_BLOCKED].
 */
internal fun String.keepingSchemeAtLeastAsSecureAs(secure: Boolean): String =
    if (secure && startsWith("http://")) "https://" + removePrefix("http://") else this

/**
 * Accepts what a person actually types: a bare host, an explicit scheme, or a subdirectory install
 * — Nextcloud in a subdirectory is common enough that rejecting a path would be wrong. Returns
 * `null` for anything OkHttp cannot make a URL of.
 *
 * **A bare host defaults to `https://`; an explicitly typed scheme is honoured, not rewritten.**
 * Silently upgrading `http://192.168.1.10` to HTTPS would connect to a different endpoint than the
 * user named and fail with a confusing error, and silently *downgrading* would put an app password
 * on the wire in plaintext. Neither is ours to decide here: S5's field renders a fixed `https://`
 * prefix (`UI.adoc` §8), so the UI is where the default is made visible, and this stays
 * consistent with `RetrofitGpodderClient`, which honours the stored URL's scheme too.
 *
 * This applies to what the **user** typed. URLs the *server* hands back are a different question,
 * answered by [keepingSchemeAtLeastAsSecureAs].
 */
internal fun String.normalisedRoot(): HttpUrl? {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme =
        if (trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    return withScheme.trimEnd('/').plus("/").toHttpUrlOrNull()
}

/**
 * Runs [block] **off the calling thread**, then maps what it throws onto a [LoginFlowFailure].
 *
 * The `withContext` is not tidiness. `OkHttpClient.execute()` blocks, these are `suspend` functions,
 * and `viewModelScope.launch` runs on `Dispatchers.Main.immediate` — so without it, S5 tapping
 * *Request authorization* performs a DNS lookup on the main thread and Android's StrictMode kills the
 * app with `NetworkOnMainThreadException`. Every JVM test passed regardless, because a JVM has no
 * main-thread policy (CLAUDE.md §8: no blocking calls on the main dispatcher, inject the dispatcher).
 */
private suspend fun <T> runCatchingRequest(
    dispatcher: CoroutineDispatcher,
    block: suspend () -> T,
): Result<T> =
    withContext(dispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (typed: LoginFlowException) {
            Result.failure(typed)
        } catch (tls: javax.net.ssl.SSLException) {
            Result.failure(LoginFlowException(LoginFlowFailure.TLS, tls.message ?: "the certificate isn't trusted"))
        } catch (timeout: SocketTimeoutException) {
            // BEFORE the IOException branch, which it is a subclass of, and separate from it because
            // the two need different words. A timeout was once reported as "can't reach that address,
            // check the spelling" — advice that sends the user to fix a host name that was right all
            // along. Nextcloud's bruteforce protection delays repeated authorization attempts from
            // the same address, so a *correct* server answering slowly is a normal case here.
            val why = timeout.message ?: "the server did not answer"
            Result.failure(LoginFlowException(LoginFlowFailure.TIMED_OUT, why))
        } catch (cleartext: UnknownServiceException) {
            // Android's own refusal to open a plain http:// connection, which arrives as a plain
            // IOException and used to be indistinguishable from a wrong host name. It is nearly
            // always a *server* URL rather than the typed one — see LoginFlowFailure.CLEARTEXT_BLOCKED.
            val why = cleartext.message ?: "an unencrypted http:// connection was refused"
            Result.failure(LoginFlowException(LoginFlowFailure.CLEARTEXT_BLOCKED, why))
        } catch (io: IOException) {
            // DNS failure, connection refused, no route — genuinely "can't reach that address".
            // The message is kept: it names the host that actually failed, which is the one piece of
            // information the user needs when it is not the host they typed.
            Result.failure(LoginFlowException(LoginFlowFailure.UNREACHABLE, io.message ?: "could not reach the server"))
        } catch (
            @Suppress("SwallowedException") unreadable: SerializationException,
        ) {
            // A 2xx whose body is not a Login Flow answer: a catch-all web server, a captive portal,
            // a blog answering every POST with its front page. The same mistake as a 404 at `start`
            // — the address is not a Nextcloud. Before this branch it escaped the Result entirely
            // (`SerializationException` is an IllegalArgumentException, not an IOException) and
            // crashed the caller's coroutine.
            //
            // The message is fixed on purpose, and the exception's own is dropped: kotlinx.serialization
            // quotes the JSON input, and a poll body carries the app password.
            Result.failure(LoginFlowException(LoginFlowFailure.NOT_NEXTCLOUD, "the answer was not a Nextcloud login"))
        }
    }

@Serializable
private data class LoginFlowStartDto(
    val poll: LoginFlowPollDto,
    val login: String,
)

@Serializable
private data class LoginFlowPollDto(
    val token: String,
    val endpoint: String,
)

@Serializable
private data class LoginPollDto(
    val server: String,
    @SerialName("loginName") val loginName: String,
    @SerialName("appPassword") val appPassword: String,
)
