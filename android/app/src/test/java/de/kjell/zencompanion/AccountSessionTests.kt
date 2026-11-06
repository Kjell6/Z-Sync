package de.kjell.zencompanion

import android.content.Context
import android.content.ContextWrapper
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.AppEvents
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncCrypto
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpRequest
import de.kjell.zencompanion.sync.SyncHttpResponse
import de.kjell.zencompanion.sync.SyncHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Expired credentials and sign-out races, driven through the same transport
 * seams `AccountStore.connect` uses in production. Mirrors the iOS cases in
 * `SyncTransportTests`.
 */
class AccountSessionTests {
    private val context: Context = ContextWrapper(null)
    private val kB = ByteArray(32) { 0xAB.toByte() }
    private val storageKeys = SyncCrypto.KeyBundle(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
    private val account = AccountStore.AccountSnapshot(
        email = "a@b.c",
        uid = "u1",
        sessionTokenHex = "01".repeat(32),
        kBHex = "ab".repeat(32),
    )
    private lateinit var server: FxAAndSyncServer
    private var previousAuth: SyncHttpTransport? = null
    private var previousSync: SyncHttpTransport? = null

    @Before
    fun setUp() {
        server = FxAAndSyncServer(kB, storageKeys)
        AccountStore.resetForTests()
        val store = MemoryPrefsStore()
        AccountStore.secureStoreFactory = { store }
        AccountStore.legacyStoreFactory = { NoLegacyStore }
        previousAuth = AccountStore.authTransport
        previousSync = AccountStore.syncTransport
        AccountStore.authTransport = server
        AccountStore.syncTransport = server
        AccountStore.save(context, account)
    }

    @After
    fun tearDown() {
        previousAuth?.let { AccountStore.authTransport = it }
        previousSync?.let { AccountStore.syncTransport = it }
        AccountStore.resetForTests()
        AccountStore.restoreDefaultFactoriesForTests()
    }

    @Test
    fun storage401RefreshesCredentialsAndRetriesOnce() {
        server.storage401Remaining = 1

        runBlocking { SpacesSyncService.loadSnapshot(context) }

        assertEquals("the 401 must force a fresh token-server exchange", 2, server.tokenServerCalls)
        assertEquals(0, server.storage401Remaining)
    }

    @Test
    fun renameMoveAndPinRetryAfterStorage401() {
        val ops: List<suspend () -> Unit> = listOf(
            { SpacesSyncService.renameTab(context, id = "t1", label = "Work") },
            { SpacesSyncService.moveTab(context, id = "t1", spaceId = "space-1", folderId = null) },
            { SpacesSyncService.pinTab(context, id = "t1", spaceId = "space-1") },
        )
        for (op in ops) {
            AccountStore.invalidateCreds()
            val before = server.tokenServerCalls
            server.storage401Remaining = 1

            // The empty collection has no t1, so the retried write ends in a conflict.
            assertTrue(runCatching { runBlocking { op() } }.exceptionOrNull() is SyncError.Conflict)

            assertEquals("the 401 must force a fresh token-server exchange", before + 2, server.tokenServerCalls)
            assertEquals(0, server.storage401Remaining)
        }
    }

    @Test
    fun persistentStorage401SurfacesAfterOneRetry() {
        server.storage401Remaining = 10

        try {
            runBlocking { SpacesSyncService.loadSnapshot(context) }
            fail("expected SyncError.Unauthorized")
        } catch (expected: SyncError.Unauthorized) {
            // Expected: one retry, then the error surfaces.
        }
        assertEquals(2, server.tokenServerCalls)
    }

    /**
     * A persistent storage 401 must not sign the user out: the retry fetched
     * new credentials through the session, which proves the session is alive.
     */
    @Test
    fun persistentStorage401DoesNotReportSessionExpiry() {
        server.storage401Remaining = 10

        val (error, events) = runAndCountExpiryEvents { SpacesSyncService.loadSnapshot(context) }

        assertTrue("got $error", error is SyncError.Unauthorized)
        assertFalse((error as SyncError).isSessionInvalid)
        assertEquals(0, events)
    }

    /**
     * App launch after a password change: no cached credentials, so the auth
     * server's errno 110 comes straight out of `connect()`.
     */
    @Test
    fun rejectedSessionOnConnectReportsSessionExpiry() {
        server.sessionRejected = true

        val (error, events) = runAndCountExpiryEvents { SpacesSyncService.loadSnapshot(context) }

        assertTrue("got $error", (error as? SyncError)?.isSessionInvalid == true)
        assertEquals(1, events)
    }

    /**
     * Password changed while the app runs: the cached credentials hit a
     * storage 401 and the refresh finds the session dead.
     */
    @Test
    fun rejectedSessionDuringStorage401RetryReportsSessionExpiry() {
        runBlocking { AccountStore.connect(context) }
        server.sessionRejected = true
        server.storage401Remaining = 1

        val (error, events) = runAndCountExpiryEvents { SpacesSyncService.loadSnapshot(context) }

        assertTrue("got $error", (error as? SyncError)?.isSessionInvalid == true)
        assertEquals(1, events)
    }

    /**
     * Signing out revokes the session, so a request still in flight comes back
     * rejected. That verdict is about the old sign-in and must not reach
     * whatever the user is doing now.
     */
    @Test
    fun rejectionFromAnEarlierSignInIsIgnored() {
        server.sessionRejected = true
        server.onSessionRejected = { AccountStore.clear(context) }

        val (error, events) = runAndCountExpiryEvents { AccountStore.withClient(context) { it } }

        assertTrue("got $error", (error as? SyncError)?.isSessionInvalid == true)
        assertEquals(0, events)
    }

    private fun runAndCountExpiryEvents(operation: suspend () -> Any?): Pair<Throwable?, Int> = runBlocking {
        val events = AtomicInteger()
        val collector = launch(Dispatchers.Unconfined) {
            AppEvents.sessionExpired.collect { events.incrementAndGet() }
        }
        val error = runCatching { operation() }.exceptionOrNull()
        delay(50)
        collector.cancel()
        error to events.get()
    }

    @Test
    fun signOutDuringCredentialFetchDropsTheResult() {
        server.onTokenServer = { AccountStore.clear(context) }

        try {
            runBlocking { AccountStore.connect(context) }
            fail("expected SyncError.NotSignedIn")
        } catch (expected: SyncError.NotSignedIn) {
            // Expected: the credentials fetched for the old session are dropped.
        }

        server.onTokenServer = null
        AccountStore.save(context, account)
        runBlocking { AccountStore.connect(context) }
        assertEquals("the dropped credentials must not be reused", 2, server.tokenServerCalls)
    }

    private class MemoryPrefsStore : AccountStore.AccountPrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String): Boolean {
            values[key] = value
            return true
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private object NoLegacyStore : AccountStore.LegacyPlainStore {
        override fun read(key: String): String? = null
        override fun delete() {}
    }
}

/**
 * Answers the FxA OAuth, scoped-key and token-server calls plus the Sync
 * storage reads one `connect()` + snapshot load makes, routed by URL.
 */
private class FxAAndSyncServer(
    private val kB: ByteArray,
    private val storageKeys: SyncCrypto.KeyBundle,
) : SyncHttpTransport {
    @Volatile var tokenServerCalls = 0
        private set

    @Volatile var storage401Remaining = 0

    /** True once the session token is dead: every FxA call answers 401 errno 110. */
    @Volatile var sessionRejected = false

    var onSessionRejected: (() -> Unit)? = null

    var onTokenServer: (() -> Unit)? = null

    @Synchronized
    override fun execute(request: SyncHttpRequest): SyncHttpResponse {
        val host = request.url.host
        val path = request.url.path
        fun json(body: Any, status: Int = 200) =
            SyncHttpResponse(status, mapOf("x-last-modified" to "1.00"), body.toString().toByteArray(Charsets.UTF_8))
        return when {
            sessionRejected && host.endsWith("accounts.firefox.com") -> {
                onSessionRejected?.invoke()
                json(JSONObject().put("errno", 110).put("message", "Invalid authentication token"), status = 401)
            }
            host == "oauth.accounts.firefox.com" || path.endsWith("/oauth/token") ->
                json(JSONObject().put("access_token", "access"))
            path.endsWith("/account/scoped-key-data") ->
                json(
                    JSONObject().put(
                        "https://identity.mozilla.com/apps/oldsync",
                        JSONObject().put("keyRotationTimestamp", 0),
                    ),
                )
            host == "token.services.mozilla.com" -> {
                tokenServerCalls++
                onTokenServer?.invoke()
                json(
                    JSONObject()
                        .put("uid", "1")
                        .put("api_endpoint", "https://sync.example.com/1.0/sync/1.5")
                        .put("id", "hawk-$tokenServerCalls")
                        .put("key", "key-$tokenServerCalls")
                        .put("duration", 3600),
                )
            }
            host != "sync.example.com" -> SyncHttpResponse(500, emptyMap(), ByteArray(0))
            path.endsWith("/storage/crypto/keys") -> {
                val keys = JSONObject()
                    .put(
                        "default",
                        JSONArray()
                            .put(Base64.getEncoder().encodeToString(storageKeys.encryptionKey))
                            .put(Base64.getEncoder().encodeToString(storageKeys.hmacKey)),
                    )
                    .put("collections", JSONObject())
                val payload = SyncCrypto.encryptBSO(
                    keys.toString().toByteArray(Charsets.UTF_8),
                    SyncCrypto.syncKeyBundle(kB),
                )
                json(JSONObject().put("id", "keys").put("payload", payload))
            }
            path.endsWith("/storage/spaces") && storage401Remaining > 0 -> {
                storage401Remaining--
                SyncHttpResponse(401, emptyMap(), ByteArray(0))
            }
            else -> json(JSONArray())
        }
    }
}
