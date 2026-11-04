package de.kjell.zencompanion

import android.content.Context
import android.content.ContextWrapper
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncCrypto
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpRequest
import de.kjell.zencompanion.sync.SyncHttpResponse
import de.kjell.zencompanion.sync.SyncHttpTransport
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
            assertTrue(expected.isSessionInvalid)
        }
        assertEquals(2, server.tokenServerCalls)
    }

    /**
     * A 401 caused by a device-environment problem is not a revoked session.
     * Clock skew, a stale credential generation and a node that disabled new
     * users must never sign the user out: a new sign-in would not fix them.
     */
    @Test
    fun storage401DeviceEnvironmentReasonsDoNotInvalidateSession() {
        for (reason in listOf("invalid-timestamp", "invalid-generation", "new-users-disabled", "error")) {
            server.storage401Remaining = 2
            server.storage401Reason = reason
            AccountStore.invalidateCreds()

            try {
                runBlocking { SpacesSyncService.loadSnapshot(context) }
                fail("$reason: expected SyncError.Unauthorized")
            } catch (expected: SyncError.Unauthorized) {
                assertFalse(reason, expected.sessionInvalid)
            }
        }
    }

    /** A bare 401 without the storage node's Hawk challenge (proxy, captive
     * portal) is not a verdict on the account either. */
    @Test
    fun bareStorage401IsNotASessionVerdict() {
        server.storage401Remaining = 2
        server.storage401IncludesHawk = false
        server.storage401Reason = ""
        AccountStore.invalidateCreds()

        try {
            runBlocking { SpacesSyncService.loadSnapshot(context) }
            fail("expected SyncError.Unauthorized")
        } catch (expected: SyncError.Unauthorized) {
            assertFalse(expected.sessionInvalid)
        }
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

    /** Reason the storage node reports on its 401 (`invalid-timestamp` = clock skew). */
    @Volatile var storage401Reason = "invalid-credentials"

    /** False simulates a bare 401 from a proxy/captive portal without Hawk material. */
    @Volatile var storage401IncludesHawk = true

    var onTokenServer: (() -> Unit)? = null

    @Synchronized
    override fun execute(request: SyncHttpRequest): SyncHttpResponse {
        val host = request.url.host
        val path = request.url.path
        fun json(body: Any, status: Int = 200) =
            SyncHttpResponse(status, mapOf("x-last-modified" to "1.00"), body.toString().toByteArray(Charsets.UTF_8))
        return when {
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
                val headers = mutableMapOf<String, String>()
                if (storage401IncludesHawk) {
                    headers["www-authenticate"] = "Hawk ts=\"1700000050.00\", ttl=\"60\", nonce=\"dGVzdG5vbmNl\""
                    headers["x-timestamp"] = "1700000050.00"
                }
                SyncHttpResponse(
                    401,
                    headers,
                    if (storage401Reason.isEmpty()) {
                        ByteArray(0)
                    } else {
                        JSONObject().put("status", storage401Reason).toString().toByteArray(Charsets.UTF_8)
                    },
                )
            }
            else -> json(JSONArray())
        }
    }
}
