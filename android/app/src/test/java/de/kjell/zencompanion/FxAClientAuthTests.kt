package de.kjell.zencompanion

import de.kjell.zencompanion.sync.FxAClient
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpRequest
import de.kjell.zencompanion.sync.SyncHttpResponse
import de.kjell.zencompanion.sync.SyncHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Transport-driven tests for [FxAClient.syncCredentials] (SPEC §7.1): an
 * errno 103 failure on the primary OAuth/oldsync path must surface as
 * [SyncError.TotpRequired] and must not fall through to the BrowserID flow,
 * exactly like iOS. Error bodies come from the shared `auth-errno-103.json`
 * fixture via [FixtureLoader].
 */
class FxAClientAuthTests {
    private val sessionToken = "01".repeat(32)
    private val kB = ByteArray(32) { 0xAB.toByte() }

    /** `input.body` of a named case in the `auth-errno-103` fixture. */
    private fun errnoBody(caseId: String): ByteArray {
        val cases = FixtureLoader.cases("auth-errno-103")
        for (i in 0 until cases.length()) {
            val fixtureCase = cases.getJSONObject(i)
            if (fixtureCase.getString("id") == caseId) {
                return fixtureCase.getJSONObject("input").getJSONObject("body")
                    .toString().toByteArray(Charsets.UTF_8)
            }
        }
        error("fixture case $caseId missing")
    }

    private fun json(vararg entries: Pair<String, Any>): ByteArray {
        val obj = JSONObject()
        for ((key, value) in entries) obj.put(key, value)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * HTTP 401 + errno 103 from `scoped-key-data` — the OAuth token call
     * succeeds first, so the failure lands on the primary path — must throw
     * [SyncError.TotpRequired] and stop. The fallback stub is a sentinel that
     * is only consumed if the BrowserID flow runs.
     */
    @Test
    fun syncCredentialsRethrowsTotpRequiredWithoutBrowserIdFallback() {
        val fake = FakeHttpTransport()
        fake.enqueue(
            SyncHttpResponse(200, emptyMap(), json("access_token" to "access-token")),
            SyncHttpResponse(401, emptyMap(), errnoBody("errno-103-totp")),
            SyncHttpResponse(401, emptyMap(), errnoBody("errno-103-totp")),
        )

        try {
            runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }
            fail("expected SyncError.TotpRequired")
        } catch (expected: SyncError.TotpRequired) {
            // Expected.
        } catch (other: Exception) {
            fail("expected SyncError.TotpRequired, got $other")
        }

        assertEquals(2, fake.requests.size)
        assertEquals("oauth.accounts.firefox.com", fake.requests[0].url.host)
        assertEquals("api.accounts.firefox.com", fake.requests[1].url.host)
        assertEquals("/v1/account/scoped-key-data", fake.requests[1].url.path)
    }

    /**
     * A non-TOTP auth failure (errno 104, fixture case `errno-104-plain-auth`)
     * still falls back to BrowserID, mirroring Android's conditional rethrow.
     */
    @Test
    fun syncCredentialsFallsBackToBrowserIdForNonTotpFailure() {
        val fake = FakeHttpTransport()
        fake.enqueue(
            SyncHttpResponse(200, emptyMap(), json("access_token" to "access-token")),
            SyncHttpResponse(401, emptyMap(), errnoBody("errno-104-plain-auth")),
            SyncHttpResponse(200, emptyMap(), json("cert" to "cert")),
            SyncHttpResponse(
                200,
                emptyMap(),
                json(
                    "uid" to "uid-1",
                    "api_endpoint" to "https://sync.example.com/1.0/sync/1.5",
                    "id" to "hawk-id",
                    "key" to "hawk-key",
                    "duration" to 3600,
                ),
            ),
        )

        val creds = runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }

        assertEquals("uid-1", creds.uid)
        assertEquals(4, fake.requests.size)
        assertEquals("/v1/account/scoped-key-data", fake.requests[1].url.path)
        assertEquals("/v1/certificate/sign", fake.requests[2].url.path)
        assertEquals("token.services.mozilla.com", fake.requests[3].url.host)
        assertTrue(fake.requests[3].headers.getValue("Authorization").startsWith("BrowserID "))
    }

    /**
     * A 401 from the auth server means the stored session token is dead
     * (e.g. the Mozilla account password changed elsewhere). The wrapped
     * message must keep that verdict so the app signs out instead of showing
     * a passive "try again" line.
     */
    @Test
    fun rejectedSessionTokenFlagsSessionInvalid() {
        val fake = FakeHttpTransport()
        val body = json("errno" to 110, "message" to "Invalid authentication token")
        fake.enqueue(
            SyncHttpResponse(401, emptyMap(), body),
            SyncHttpResponse(401, emptyMap(), body),
            SyncHttpResponse(401, emptyMap(), body),
        )

        try {
            runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }
            fail("expected SyncError.Auth")
        } catch (expected: SyncError.Auth) {
            assertTrue(expected.sessionInvalid)
        }
    }

    /**
     * A device clock that is far off makes FxA reject the Hawk timestamp
     * (401 errno 111). Signing in again would not fix that, so the user must
     * not be signed out.
     */
    @Test
    fun clockSkewDoesNotFlagSessionInvalid() {
        val fake = FakeHttpTransport()
        val body = json("errno" to 111, "message" to "Invalid timestamp in request signature")
        fake.enqueue(
            SyncHttpResponse(401, emptyMap(), body),
            SyncHttpResponse(401, emptyMap(), body),
            SyncHttpResponse(401, emptyMap(), body),
        )

        try {
            runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }
            fail("expected SyncError.Auth")
        } catch (expected: SyncError.Auth) {
            assertFalse(expected.sessionInvalid)
        }
    }

    /**
     * A token-server rejection after the auth server minted credentials is
     * not proof that the account session was revoked. It must not sign the
     * user out; the auth-server errno 110 is the authoritative signal.
     */
    @Test
    fun tokenServerRejectionDoesNotFlagSessionInvalid() {
        val fake = FakeHttpTransport()
        fake.enqueue(
            SyncHttpResponse(200, emptyMap(), json("access_token" to "access-token")),
            SyncHttpResponse(
                200,
                emptyMap(),
                json("https://identity.mozilla.com/apps/oldsync" to JSONObject().put("keyRotationTimestamp", 0)),
            ),
            SyncHttpResponse(401, emptyMap(), json("status" to "invalid-credentials")),
            SyncHttpResponse(200, emptyMap(), json("cert" to "cert")),
            SyncHttpResponse(401, emptyMap(), json("status" to "invalid-credentials")),
        )

        try {
            runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }
            fail("expected SyncError.Auth")
        } catch (expected: SyncError.Auth) {
            assertFalse(expected.sessionInvalid)
        }
    }

    /** A server outage (HTTP 5xx) is temporary: no sign-out. */
    @Test
    fun authServerOutageDoesNotFlagSessionInvalid() {
        val fake = FakeHttpTransport()
        val body = json("error" to "Service unavailable")
        fake.enqueue(
            SyncHttpResponse(503, emptyMap(), body),
            SyncHttpResponse(503, emptyMap(), body),
            SyncHttpResponse(503, emptyMap(), body),
        )

        try {
            runBlocking { FxAClient(fake).syncCredentials(sessionToken, kB) }
            fail("expected SyncError.Auth")
        } catch (expected: SyncError.Auth) {
            assertFalse(expected.sessionInvalid)
        }
    }

    /**
     * A cancelled request is not a failed sign-in. It must come out as the
     * cancellation it is, without a BrowserID attempt, so the caller can retry
     * instead of showing "Sync sign-in failed".
     */
    @Test
    fun syncCredentialsPassesACancelledRequestOnUnwrapped() {
        var calls = 0
        val cancelling = object : SyncHttpTransport {
            override fun execute(request: SyncHttpRequest): SyncHttpResponse {
                calls++
                throw CancellationException("cancelled")
            }
        }

        try {
            runBlocking { FxAClient(cancelling).syncCredentials(sessionToken, kB) }
            fail("expected the cancellation")
        } catch (expected: CancellationException) {
            // Expected.
        } catch (other: Exception) {
            fail("expected the cancellation, got $other")
        }
        assertEquals(1, calls)
    }
}
