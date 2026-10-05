// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The half of *"the error log never records a credential"* that can be asserted on a pure function.
 * The other half — that the store applies it — is `LogRepositoryImplTest`.
 *
 * Table-driven, because every row here is a real shape a credential arrives in rather than an
 * invented one: OkHttp prints headers, `HttpUrl` prints userinfo, and private podcast feeds put
 * tokens in query strings.
 */
class LogRedactionTest {
    private val secret = "sup3r-s3cret-app-password"

    @Test
    fun redactsTheShapesCredentialsArriveIn() {
        val cases =
            listOf(
                "Authorization: Basic dXNlcjpwYXNzd29yZA==" to "Authorization: <redacted>",
                "authorization=Basic dXNlcjpwdw==" to "authorization=<redacted>",
                "header Basic dXNlcjpwYXNzd29yZA== sent" to "header Basic <redacted> sent",
                "Bearer eyJhbGciOiJIUzI1NiJ9" to "Bearer <redacted>",
                "https://user:hunter2@cloud.example.org/feed.xml" to "https://cloud.example.org/feed.xml",
                "GET https://cloud.example.org/f?token=abc123&x=1" to
                    "GET https://cloud.example.org/f?token=<redacted>&x=1",
                "https://host/e.mp3?app_password=abc123" to "https://host/e.mp3?app_password=<redacted>",
                // The scheme word is consumed with the token, whichever scheme and casing it is.
                "AUTHORIZATION: bearer eyJhbGciOiJIUzI1NiJ9" to "AUTHORIZATION: <redacted>",
                "Authorization: Digest username=podsilo" to "Authorization: <redacted>",
                "https://host/f.xml?apikey=k1&api_key=k2&passwd=p" to
                    "https://host/f.xml?apikey=<redacted>&api_key=<redacted>&passwd=<redacted>",
            )

        cases.forEach { (raw, expected) ->
            assertEquals("redacting: $raw", expected, redactSecrets(raw))
        }
    }

    @Test
    fun leavesOrdinaryFailureTextAlone() {
        val cases =
            listOf(
                // "Basic auth" is prose a user is meant to read: below the token floor, so it stays.
                "Basic auth failed",
                "Feed server did not respond.",
                "HTTP 401 Unauthorized",
                // An `@` in a path is not userinfo.
                "https://cloud.example.org/apps/gpoddersync/feed@2x.xml",
                "unable to resolve host \"cloud.example.org\": No address associated with hostname",
            )

        cases.forEach { raw -> assertEquals(raw, redactSecrets(raw)) }
    }

    @Test
    fun redactsBothFreeTextFieldsOfAnEntry() {
        val entry =
            NewLogEntry(
                category = LogCategory.SYNC,
                feedUrl = "https://cloud.example.org/feed.xml",
                episodeKey = "guid-1",
                message = "Sync failed for https://podsilo:$secret@cloud.example.org",
                detail = "Authorization: Basic ${secret.encodeLikeBasic()}",
            ).redacted()

        assertFalse(entry.message.contains(secret))
        assertFalse(entry.detail.orEmpty().contains(secret))
        assertEquals("Sync failed for https://cloud.example.org", entry.message)
        assertEquals("Authorization: <redacted>", entry.detail)
    }

    /**
     * Identifiers are deliberately **not** redacted — the UI navigates by them, and a feed URL is
     * already shown on S1 as the title fallback. Pinned so the choice is visible rather than assumed.
     */
    @Test
    fun leavesIdentifiersAlone() {
        val entry =
            NewLogEntry(
                category = LogCategory.FEED,
                feedUrl = "https://cloud.example.org/feed.xml",
                episodeKey = "https://cdn.example.org/ep1.mp3",
                message = "Feed server did not respond.",
            ).redacted()

        assertEquals("https://cloud.example.org/feed.xml", entry.feedUrl)
        assertEquals("https://cdn.example.org/ep1.mp3", entry.episodeKey)
    }

    private fun String.encodeLikeBasic() = replace("-", "")
}
