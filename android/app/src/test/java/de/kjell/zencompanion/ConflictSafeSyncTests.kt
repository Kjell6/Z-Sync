package de.kjell.zencompanion

import android.content.Context
import android.content.ContextWrapper
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SnapshotCache
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.SyncClient
import de.kjell.zencompanion.sync.SyncCrypto
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpRequest
import de.kjell.zencompanion.sync.SyncHttpResponse
import de.kjell.zencompanion.sync.SyncHttpTransport
import de.kjell.zencompanion.sync.SyncSafety
import de.kjell.zencompanion.sync.TokenServerCreds
import de.kjell.zencompanion.sync.ZenSpaces
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * Conflict-safe writes (SPEC §7.2) driven through [SpacesSyncService] against
 * an in-memory server whose records can be mutated by a simulated desktop
 * writer between the consistent read and the conditional POST. The legacy
 * switch-off path is pinned to its exact request sequence.
 */
class ConflictSafeSyncTests {
    private val creds = TokenServerCreds(
        uid = "uid-1",
        apiEndpoint = "https://sync.example.com",
        hawkID = "hawk-id",
        hawkKey = "hawk-key".toByteArray(Charsets.UTF_8),
        expiresAtMillis = Long.MAX_VALUE,
    )

    private val keys = SyncCrypto.KeyBundle(ByteArray(32) { 0x07 }, ByteArray(32) { 0x09 })
    private val syncKeys = SyncCrypto.syncKeyBundle(ByteArray(32))

    @Before
    fun setUp() {
        SyncSafety.overrideForTests = true
        SnapshotCache.invalidateMemory()
    }

    @After
    fun tearDown() {
        SyncSafety.overrideForTests = null
        SnapshotCache.invalidateMemory()
        AccountStore.resetForTests()
    }

    private fun client(server: ConflictServer): SyncClient =
        SyncClient(creds, keys, emptyMap(), server)

    // MARK: addTab

    @Test
    fun addTabRootRetriesWithUnionAfterConcurrentAppend() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("existing-1"))
        server.seedTab("existing-1", "space-1")
        var desktopWrote = false
        server.onBeforeRequest = { request ->
            if (!desktopWrote && request.method == "POST") {
                desktopWrote = true
                server.desktopAppendTab("space-1", "desktop-1")
            }
        }

        val newId = runBlocking {
            SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1")
        }.recordId

        val posts = server.requests.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        assertEquals("100.00", posts[0].header("X-If-Unmodified-Since"))
        assertEquals("101.00", posts[1].header("X-If-Unmodified-Since"))

        // The first conditional POST already carries tab + rewritten space.
        val first = server.batch(posts[0])
        assertEquals(listOf(newId, "space-1"), first.map { it.getString("id") })
        assertEquals(listOf("existing-1", newId), server.children(first, "space-1"))

        // The retry merges the concurrent append instead of dropping it.
        val second = server.batch(posts[1])
        assertEquals(listOf(newId, "space-1"), second.map { it.getString("id") })
        assertEquals(listOf("existing-1", "desktop-1", newId), server.children(second, "space-1"))
        assertEquals(
            listOf("existing-1", "desktop-1", newId),
            server.cleartext("space-1")!!.getJSONObject("data").getJSONArray("children").let(::strings),
        )
        assertTrue(server.cleartext(newId) != null)
    }

    @Test
    fun addTabFolderTargetPostsTabAndRewrittenFolder() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("folder-1"))
        server.seedFolder("folder-1", workspaceUuid = "space-1", children = listOf("inside"))

        val newId = runBlocking {
            SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1", folderId = "folder-1")
        }.recordId

        val post = server.requests.single { it.method == "POST" }
        val batch = server.batch(post)
        assertEquals(listOf(newId, "folder-1"), batch.map { it.getString("id") })
        assertEquals(listOf("inside", newId), server.children(batch, "folder-1"))
        val tabData = batch.first { it.getString("id") == newId }.getJSONObject("data")
        assertEquals("folder-1", tabData.getString("folderId"))
    }

    @Test
    fun addTabFallsBackToRootForFolderInAnotherSpace() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = emptyList())
        server.seedFolder("folder-1", workspaceUuid = "other-space", children = emptyList())

        val newId = runBlocking {
            SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1", folderId = "folder-1")
        }.recordId

        val batch = server.batch(server.requests.single { it.method == "POST" })
        assertEquals(listOf(newId, "space-1"), batch.map { it.getString("id") })
        val tabData = batch.first { it.getString("id") == newId }.getJSONObject("data")
        assertTrue(tabData.isNull("folderId"))
    }

    @Test
    fun addTabFallsBackToCachedSnapshotWhenSpaceIsNotOnTheServer() {
        val server = ConflictServer(keys, syncKeys)
        val cachedSpace = ZenSpaces.ZenSpace(
            id = "space-1",
            name = "Cached Space",
            icon = null,
            containerGuid = null,
            theme = null,
            pinned = listOf(
                ZenSpaces.ZenItem.Tab(
                    ZenSpaces.ZenTab(id = "cached-tab", url = "https://cached.example", title = "Cached"),
                ),
            ),
            tabs = emptyList(),
        )
        SnapshotCache.cache(ZenSpaces.ZenSnapshot(listOf(cachedSpace), emptyMap(), 0L))

        val newId = runBlocking {
            SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1")
        }.recordId

        val batch = server.batch(server.requests.single { it.method == "POST" })
        assertEquals(listOf(newId, "space-1"), batch.map { it.getString("id") })
        assertEquals(listOf("cached-tab", newId), server.children(batch, "space-1"))
    }

    @Test
    fun addTabNormalWritesPinnedFalseAndIgnoresFolder() {
        val server = ConflictServer(keys, syncKeys)
        server.seedPrefs(JSONObject().put(SpacesSyncService.NORMAL_TABS_PREF_KEY, true))
        server.seedSpace("space-1", children = listOf("folder-1"))
        server.seedFolder("folder-1", workspaceUuid = "space-1", children = emptyList())

        val outcome = runBlocking {
            SpacesSyncService.addTab(
                client(server),
                "https://normal.example",
                "Normal",
                "space-1",
                folderId = "folder-1",
                kind = SaveKind.NORMAL,
            )
        }

        assertEquals(SaveKind.NORMAL, outcome.kind)
        assertFalse(outcome.fellBackToPinned)
        val batch = server.batch(server.requests.single { it.method == "POST" })
        // A normal tab attaches to the space, never the folder.
        assertEquals(listOf(outcome.recordId, "space-1"), batch.map { it.getString("id") })
        val tabData = batch.first { it.getString("id") == outcome.recordId }.getJSONObject("data")
        assertEquals(false, tabData.getBoolean("pinned"))
        assertTrue(tabData.isNull("folderId"))
    }

    @Test
    fun addTabNormalFallsBackToPinnedWhenPrefDisabled() {
        val server = ConflictServer(keys, syncKeys)
        server.seedPrefs(JSONObject().put(SpacesSyncService.NORMAL_TABS_PREF_KEY, false))
        server.seedSpace("space-1", children = emptyList())

        val outcome = runBlocking {
            SpacesSyncService.addTab(
                client(server),
                "https://normal.example",
                "Normal",
                "space-1",
                kind = SaveKind.NORMAL,
            )
        }

        assertTrue(outcome.fellBackToPinned)
        assertEquals(SaveKind.PINNED, outcome.kind)
        val batch = server.batch(server.requests.single { it.method == "POST" })
        val tabData = batch.first { it.getString("id") == outcome.recordId }.getJSONObject("data")
        assertEquals(true, tabData.getBoolean("pinned"))
    }

    @Test
    fun addTabNormalFallsBackToPinnedWhenPrefsUnreadable() {
        val server = ConflictServer(keys, syncKeys)
        server.failPrefsRead = true
        server.seedSpace("space-1", children = emptyList())

        val outcome = runBlocking {
            SpacesSyncService.addTab(
                client(server),
                "https://normal.example",
                "Normal",
                "space-1",
                kind = SaveKind.NORMAL,
            )
        }

        // A transient read is not proof of support: pin instead of losing it.
        assertTrue(outcome.fellBackToPinned)
        assertEquals(SaveKind.PINNED, outcome.kind)
        val batch = server.batch(server.requests.single { it.method == "POST" })
        val tabData = batch.first { it.getString("id") == outcome.recordId }.getJSONObject("data")
        assertEquals(true, tabData.getBoolean("pinned"))
    }

    @Test
    fun loadSnapshotSecondarySignalUpgradesAbsentToDisabled() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("normal-1"))
        server.seedNormalTab("normal-1", "space-1")

        val snapshot = runBlocking { SpacesSyncService.loadSnapshot(client(server)) }

        // No prefs record, but a lingering pinned:false record proves support.
        assertEquals(ZenSpaces.NormalTabsCapability.DISABLED, snapshot.normalTabsCapability)
        // Display default still shows the normal tab (pref absent => on).
        assertEquals(listOf("normal-1"), snapshot.spaces.first().tabs.map { it.id })
    }

    @Test
    fun loadSnapshotReadsEnabledCapabilityFromPrefs() {
        val server = ConflictServer(keys, syncKeys)
        server.seedPrefs(JSONObject().put(SpacesSyncService.NORMAL_TABS_PREF_KEY, "1"))
        server.seedSpace("space-1", children = listOf("normal-1"))
        server.seedNormalTab("normal-1", "space-1")

        val snapshot = runBlocking { SpacesSyncService.loadSnapshot(client(server)) }

        assertEquals(ZenSpaces.NormalTabsCapability.ENABLED, snapshot.normalTabsCapability)
        assertEquals(listOf("normal-1"), snapshot.spaces.first().tabs.map { it.id })
    }

    @Test
    fun addTabSecondConflictThrowsAndLeavesCacheUntouched() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("existing-1"))
        server.seedTab("existing-1", "space-1")
        val cached = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "space-1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = emptyList(),
                    tabs = emptyList(),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 1L,
        )
        SnapshotCache.cache(cached)
        var desktopWrites = 0
        server.onBeforeRequest = { request ->
            if (request.method == "POST") {
                desktopWrites++
                server.desktopAppendTab("space-1", "desktop-$desktopWrites")
            }
        }

        try {
            runBlocking { SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1") }
            fail("expected SyncError.Conflict")
        } catch (expected: SyncError.Conflict) {
            // Expected: both conditional POSTs lost the race.
        }

        assertEquals(2, desktopWrites)
        assertEquals(listOf("GET", "POST", "GET", "POST"), server.requests.map { it.method })
        assertEquals(cached, SnapshotCache.cachedSnapshotShared)
    }

    @Test
    fun addTabPartialPostFailureThrowsAndLeavesCacheUntouched() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("existing-1"))
        server.seedTab("existing-1", "space-1")
        val cached = ZenSpaces.ZenSnapshot(
            spaces = listOf(
                ZenSpaces.ZenSpace(
                    id = "space-1",
                    name = "Space",
                    icon = null,
                    containerGuid = null,
                    theme = null,
                    pinned = emptyList(),
                    tabs = emptyList(),
                ),
            ),
            essentials = emptyMap(),
            fetchedAtMillis = 1L,
        )
        SnapshotCache.cache(cached)
        // The server answers 200 but omits the space rewrite from `success`
        // and reports it in `failed` instead.
        server.forcedFailed = mapOf("space-1" to "conflict")

        try {
            runBlocking { SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1") }
            fail("expected SyncError.Conflict")
        } catch (expected: SyncError.Conflict) {
            // Expected: a partial failure is not retried and must not update the cache.
        }

        assertEquals(1, server.requests.count { it.method == "POST" })
        assertEquals(cached, SnapshotCache.cachedSnapshotShared)
    }

    // MARK: deleteTab / unsplit / essentials

    @Test
    fun deleteInSpacePostsTombstoneAndRewrittenParentInOneRequest() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("t1", "t2"))
        server.seedTab("t1", "space-1")
        server.seedTab("t2", "space-1")

        runBlocking { SpacesSyncService.deleteTab(client(server), id = "t1") }

        val posts = server.requests.filter { it.method == "POST" }
        assertEquals(1, posts.size)
        val batch = server.batch(posts.single())
        assertEquals(listOf("t1", "space-1"), batch.map { it.getString("id") })
        assertTrue(batch.first { it.getString("id") == "t1" }.getBoolean("deleted"))
        assertEquals(listOf("t2"), server.children(batch, "space-1"))
        assertEquals(listOf("t2"), server.children("space-1"))
    }

    @Test
    fun deleteCollapsingTwoMemberSplitSendsOneBatchAndKeepsTheMember() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("split-1", "keep-1"))
        server.seedSplit("split-1", listOf("m1", "m2"))
        server.seedTab("m1", "space-1")
        server.seedTab("m2", "space-1")

        runBlocking { SpacesSyncService.deleteTab(client(server), id = "m1") }

        val posts = server.requests.filter { it.method == "POST" }
        assertEquals(1, posts.size)
        val batch = server.batch(posts.single())
        assertEquals(listOf("m1", "split-1", "space-1"), batch.map { it.getString("id") })
        assertTrue(batch.first { it.getString("id") == "m1" }.getBoolean("deleted"))
        assertTrue(batch.first { it.getString("id") == "split-1" }.getBoolean("deleted"))
        assertEquals(listOf("m2", "keep-1"), server.children(batch, "space-1"))
        assertEquals(listOf("m2", "keep-1"), server.children("space-1"))
        assertNull(server.cleartext("m2")!!.opt("deleted"))
    }

    @Test
    fun deleteInEssentialsFiltersOnlyTheContainingBucket() {
        val server = ConflictServer(keys, syncKeys)
        server.seedLayout(
            JSONObject()
                .put("default", JSONArray().put("e1").put("e2"))
                .put("other", JSONArray().put("e3").put("e10")),
        )

        runBlocking { SpacesSyncService.deleteTab(client(server), id = "e1") }

        val posts = server.requests.filter { it.method == "POST" }
        assertEquals(1, posts.size)
        val batch = server.batch(posts.single())
        assertEquals(listOf("e1", "layout-1"), batch.map { it.getString("id") })
        val layout = server.cleartext("layout-1")!!.getJSONObject("data").getJSONObject("essentials")
        assertEquals(listOf("e2"), strings(layout.getJSONArray("default")))
        assertEquals(listOf("e3", "e10"), strings(layout.getJSONArray("other")))
    }

    @Test
    fun unsplitSplicePreservesConcurrentExternalChildAfter412() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("split-1", "external"))
        server.seedSplit("split-1", listOf("m1", "m2"))
        server.seedTab("m1", "space-1")
        server.seedTab("m2", "space-1")
        var desktopWrote = false
        server.onBeforeRequest = { request ->
            if (!desktopWrote && request.method == "POST") {
                desktopWrote = true
                server.desktopAppendTab("space-1", "external-2")
            }
        }

        runBlocking { SpacesSyncService.deleteTab(client(server), id = "split-1") }

        val posts = server.requests.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        val retry = server.batch(posts[1])
        assertEquals(listOf("split-1", "space-1"), retry.map { it.getString("id") })
        assertTrue(retry.first().getBoolean("deleted"))
        assertEquals(listOf("m1", "m2", "external", "external-2"), server.children(retry, "space-1"))
        assertEquals(listOf("m1", "m2", "external", "external-2"), server.children("space-1"))
    }

    // MARK: Crypto/keys bootstrap

    @Test
    fun keysBootstrapRaceDoesNotOverwriteWinnerKeys() {
        val winnerKeys = SyncCrypto.KeyBundle(ByteArray(32) { 0x21 }, ByteArray(32) { 0x42 })
        val server = ConflictServer(keys, syncKeys)
        var raced = false
        server.onBeforeRequest = { request ->
            if (!raced && request.method == "PUT" && request.url.file == "/storage/crypto/keys") {
                raced = true
                server.seedCryptoKeys(
                    JSONObject()
                        .put("default", keyArray(winnerKeys))
                        .put("collections", JSONObject()),
                    "60.00",
                )
            }
        }

        val client = SyncClient(creds, ByteArray(32), server)

        assertEquals(listOf("GET", "PUT", "GET"), server.requests.map { it.method })
        assertEquals("0", server.requests[1].header("X-If-Unmodified-Since"))
        assertTrue(raced)
        // The winner's keys are used; the loser's generated keys were dropped.
        assertEquals(
            winnerKeys.encryptionKey.toList(),
            client.keys("spaces").encryptionKey.toList(),
        )
        assertEquals("60.00", server.cryptoKeysLastModified)
    }

    // MARK: Legacy switch-off path

    @Test
    fun safeSyncOffPinsLegacyAddTabSequence() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("existing-1"))
        SyncSafety.overrideForTests = false

        val newId = runBlocking {
            SpacesSyncService.addTab(client(server), "https://new.example", "New", "space-1")
        }.recordId

        assertEquals(
            listOf(
                "GET /storage/spaces?full=1&limit=2500",
                "PUT /storage/spaces/$newId",
                "PUT /storage/spaces/space-1",
            ),
            server.requests.map { "${it.method} ${it.url.file}" },
        )
        for (request in server.requests) {
            assertNull(request.header("X-If-Unmodified-Since"))
        }
        val tabRecord = server.cleartext(newId)!!
        assertEquals("tab", tabRecord.getString("kind"))
        assertEquals(newId, tabRecord.getJSONObject("data").getString("tabId"))
        assertEquals(listOf("existing-1", newId), server.children("space-1"))
    }

    @Test
    fun safeSyncOffPinsLegacyDeleteTabSequence() {
        val server = ConflictServer(keys, syncKeys)
        server.seedSpace("space-1", children = listOf("t1", "t2"))
        server.seedTab("t1", "space-1")
        server.seedTab("t2", "space-1")
        SyncSafety.overrideForTests = false

        runBlocking { SpacesSyncService.deleteTab(client(server), id = "t1") }

        assertEquals(
            listOf(
                "GET /storage/spaces?full=1&limit=2500",
                "PUT /storage/spaces/t1",
                "PUT /storage/spaces/space-1",
            ),
            server.requests.map { "${it.method} ${it.url.file}" },
        )
        for (request in server.requests) {
            assertNull(request.header("X-If-Unmodified-Since"))
        }
        assertTrue(server.cleartext("t1")!!.getBoolean("deleted"))
        assertEquals(listOf("t2"), server.children("space-1"))
    }

    @Test
    fun safeSyncDefaultsOnWithoutAnInitializedAppContext() {
        SyncSafety.overrideForTests = null
        assertTrue(SyncSafety.safeSyncEnabled)
    }

    // MARK: Demo mode

    @Test
    fun demoModeMakesZeroTransportCalls() {
        val context: Context = ContextWrapper(null)
        val secure = RecordingPrefsStore()
        AccountStore.resetForTests()
        AccountStore.secureStoreFactory = { secure }
        AccountStore.legacyStoreFactory = { EmptyLegacyStore }
        AccountStore.save(context, DemoCatalog.account)

        val server = ConflictServer(keys, syncKeys)
        val previousAuth = AccountStore.authTransport
        val previousSync = AccountStore.syncTransport
        AccountStore.authTransport = server
        AccountStore.syncTransport = server
        try {
            val spaceId = DemoCatalog.snapshot.spaces.first().id

            SyncSafety.overrideForTests = true
            val onId = runBlocking {
                SpacesSyncService.addTab(context, "https://demo.example", "Demo ON", spaceId)
            }.recordId
            runBlocking { SpacesSyncService.loadSnapshot(context) }
            assertTrue(server.requests.isEmpty())

            SyncSafety.overrideForTests = false
            val offId = runBlocking {
                SpacesSyncService.addTab(context, "https://demo.example", "Demo OFF", spaceId)
            }.recordId
            runBlocking { SpacesSyncService.deleteTab(context, offId) }

            // Demo capability is ENABLED (catalog has normal tabs), so a
            // normal save is honored and lands in the normal bucket.
            val normal = runBlocking {
                SpacesSyncService.addTab(
                    context,
                    "https://demo.example",
                    "Demo normal",
                    spaceId,
                    kind = SaveKind.NORMAL,
                )
            }
            assertFalse(normal.fellBackToPinned)
            assertEquals(SaveKind.NORMAL, normal.kind)

            assertTrue(server.requests.isEmpty())
            val cached = requireNotNull(SnapshotCache.cachedSnapshotShared)
            val space = cached.spaces.first { it.id == spaceId }
            val pinned = space.pinned
            assertTrue(pinned.any { it is ZenSpaces.ZenItem.Tab && it.tab.id == onId })
            assertFalse(pinned.any { it is ZenSpaces.ZenItem.Tab && it.tab.id == offId })
            assertTrue(
                space.tabs.any { it is ZenSpaces.ZenItem.Tab && it.tab.id == normal.recordId },
            )
        } finally {
            AccountStore.authTransport = previousAuth
            AccountStore.syncTransport = previousSync
        }
    }

    // MARK: Helpers

    private fun keyArray(bundle: SyncCrypto.KeyBundle): JSONArray = JSONArray()
        .put(java.util.Base64.getEncoder().encodeToString(bundle.encryptionKey))
        .put(java.util.Base64.getEncoder().encodeToString(bundle.hmacKey))

    private fun strings(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    private class RecordingPrefsStore : AccountStore.AccountPrefsStore {
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

    private object EmptyLegacyStore : AccountStore.LegacyPlainStore {
        override fun read(key: String): String? = null
        override fun delete() {}
    }
}

private fun SyncHttpRequest.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/**
 * In-memory Sync storage server. Records live as cleartext JSON and are
 * encrypted on the wire, so tests can decrypt request batches and simulate a
 * concurrent desktop writer through [onBeforeRequest].
 */
private class ConflictServer(
    private val keys: SyncCrypto.KeyBundle,
    private val syncKeys: SyncCrypto.KeyBundle,
) : SyncHttpTransport {
    val requests = mutableListOf<SyncHttpRequest>()
    private val records = linkedMapOf<String, JSONObject>()

    var lastModified: String = "100.00"
        private set
    var onBeforeRequest: ((SyncHttpRequest) -> Unit)? = null

    var cryptoKeysLastModified: String = "50.00"
        private set
    private var cryptoKeys: JSONObject? = null
    private var prefsCleartext: JSONObject? = null
    private var clock = 100

    /** When true, `GET /storage/prefs` answers 500 (transient read failure). */
    var failPrefsRead = false

    override fun execute(request: SyncHttpRequest): SyncHttpResponse {
        requests += request
        onBeforeRequest?.invoke(request)
        val path = request.url.file
        return when {
            request.method == "GET" && path.startsWith("/storage/prefs") -> prefsPage()
            request.method == "GET" && path.startsWith("/storage/spaces") -> collectionPage(request)
            request.method == "POST" && path == "/storage/spaces" -> collectionPost(request)
            request.method == "PUT" && path.startsWith("/storage/spaces/") -> recordPut(request)
            request.method == "GET" && path == "/storage/crypto/keys" -> cryptoGet()
            request.method == "PUT" && path == "/storage/crypto/keys" -> cryptoPut(request)
            else -> error("unexpected ${request.method} $path")
        }
    }

    // MARK: Seed + mutate

    fun putRecord(id: String, cleartext: JSONObject) {
        records[id] = cleartext
    }

    fun cleartext(id: String): JSONObject? = records[id]?.let { JSONObject(it.toString()) }

    fun seedSpace(id: String, children: List<String>) {
        putRecord(
            id,
            JSONObject()
                .put("id", id)
                .put("kind", "space")
                .put(
                    "data",
                    JSONObject()
                        .put("uuid", id)
                        .put("name", "Space")
                        .put("children", JSONArray(children)),
                ),
        )
    }

    fun seedTab(id: String, spaceId: String) {
        putRecord(
            id,
            JSONObject()
                .put("id", id)
                .put("kind", "tab")
                .put(
                    "data",
                    JSONObject()
                        .put("tabId", id)
                        .put("url", "https://tab.example/$id")
                        .put("title", id)
                        .put("workspaceUuid", spaceId),
                ),
        )
    }

    fun seedFolder(id: String, workspaceUuid: String, children: List<String>) {
        putRecord(
            id,
            JSONObject()
                .put("id", id)
                .put("kind", "folder")
                .put(
                    "data",
                    JSONObject()
                        .put("folderId", id)
                        .put("workspaceUuid", workspaceUuid)
                        .put("children", JSONArray(children)),
                ),
        )
    }

    fun seedSplit(id: String, members: List<String>) {
        putRecord(
            id,
            JSONObject()
                .put("id", id)
                .put("kind", "split")
                .put("data", JSONObject().put("tabs", JSONArray(members))),
        )
    }

    fun seedLayout(essentials: JSONObject) {
        putRecord(
            "layout-1",
            JSONObject()
                .put("id", "layout-1")
                .put("kind", "layout")
                .put("data", JSONObject().put("essentials", essentials)),
        )
    }

    fun seedCryptoKeys(cleartext: JSONObject, lastModified: String) {
        cryptoKeys = cleartext
        cryptoKeysLastModified = lastModified
    }

    /** Seeds the single synced `prefs` record with a `value` map. */
    fun seedPrefs(values: JSONObject) {
        prefsCleartext = JSONObject().put("id", "prefs").put("kind", "prefs").put("value", values)
    }

    /** Seeds an unpinned (`pinned:false`) tab record. */
    fun seedNormalTab(id: String, spaceId: String) {
        putRecord(
            id,
            JSONObject()
                .put("id", id)
                .put("kind", "tab")
                .put(
                    "data",
                    JSONObject()
                        .put("tabId", id)
                        .put("url", "https://tab.example/$id")
                        .put("title", id)
                        .put("workspaceUuid", spaceId)
                        .put("pinned", false),
                ),
        )
    }

    /** Simulates the desktop writer appending a tab to a space. */
    fun desktopAppendTab(spaceId: String, tabId: String) {
        seedTab(tabId, spaceId)
        val data = records.getValue(spaceId).getJSONObject("data")
        val children = data.optJSONArray("children") ?: JSONArray()
        children.put(tabId)
        data.put("children", children)
        bump()
    }

    /** Decrypted cleartexts of a request whose body is a POST BSO array. */
    fun batch(request: SyncHttpRequest): List<JSONObject> {
        val arr = JSONArray(String(request.body!!, Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val bso = arr.getJSONObject(i)
            JSONObject(String(SyncCrypto.decryptBSO(bso.getString("payload"), keys), Charsets.UTF_8))
        }
    }

    fun children(requestBatch: List<JSONObject>, id: String): List<String> =
        children(requestBatch.first { it.getString("id") == id }.getJSONObject("data"))

    fun children(id: String): List<String> =
        records.getValue(id).getJSONObject("data").let(::children)

    private fun children(data: JSONObject): List<String> {
        val arr = data.optJSONArray("children") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // MARK: HTTP handlers

    private fun collectionPage(request: SyncHttpRequest): SyncHttpResponse {
        if (request.header("X-If-Unmodified-Since") != null && midReadConflictsRemaining > 0) {
            midReadConflictsRemaining--
            return SyncHttpResponse(412, mapOf("x-last-modified" to lastModified), ByteArray(0))
        }
        val all = records.entries.map { (id, cleartext) ->
            JSONObject()
                .put("id", id)
                .put("payload", SyncCrypto.encryptBSO(cleartext.toString().toByteArray(Charsets.UTF_8), keys))
        }
        val headers = linkedMapOf("x-last-modified" to lastModified)
        val offset = request.url.query
            ?.split("&")
            ?.firstOrNull { it.startsWith("offset=") }
            ?.removePrefix("offset=")
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.toIntOrNull() ?: 0
        val body: JSONArray = if (pageSize == null) {
            JSONArray(all)
        } else {
            val slice = all.drop(offset).take(pageSize!!)
            if (offset + pageSize!! < all.size) headers["x-weave-next-offset"] = (offset + pageSize!!).toString()
            JSONArray(slice)
        }
        return SyncHttpResponse(200, headers, body.toString().toByteArray(Charsets.UTF_8))
    }

    /** Serves the single encrypted `prefs` record (or none). */
    private fun prefsPage(): SyncHttpResponse {
        if (failPrefsRead) {
            return SyncHttpResponse(500, emptyMap(), ByteArray(0))
        }
        val records = JSONArray()
        prefsCleartext?.let {
            records.put(
                JSONObject()
                    .put("id", "prefs")
                    .put("payload", SyncCrypto.encryptBSO(it.toString().toByteArray(Charsets.UTF_8), keys)),
            )
        }
        return SyncHttpResponse(
            200,
            mapOf("x-last-modified" to lastModified),
            records.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun collectionPost(request: SyncHttpRequest): SyncHttpResponse {
        val condition = request.header("X-If-Unmodified-Since")
        if (condition != null && condition != lastModified) {
            return SyncHttpResponse(412, mapOf("x-last-modified" to lastModified), ByteArray(0))
        }
        val arr = JSONArray(String(request.body!!, Charsets.UTF_8))
        val success = JSONArray()
        val failed = JSONObject()
        for (i in 0 until arr.length()) {
            val bso = arr.getJSONObject(i)
            val id = bso.getString("id")
            if (forcedFailed.containsKey(id)) {
                failed.put(id, forcedFailed.getValue(id))
                continue
            }
            records[id] = JSONObject(
                String(SyncCrypto.decryptBSO(bso.getString("payload"), keys), Charsets.UTF_8),
            )
            success.put(id)
        }
        bump()
        val body = JSONObject()
            .put("modified", lastModified)
            .put("success", success)
            .put("failed", failed)
        return SyncHttpResponse(200, emptyMap(), body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun recordPut(request: SyncHttpRequest): SyncHttpResponse {
        val condition = request.header("X-If-Unmodified-Since")
        if (condition != null && condition != lastModified) {
            return SyncHttpResponse(412, mapOf("x-last-modified" to lastModified), ByteArray(0))
        }
        val id = URLDecoder.decode(
            request.url.file.removePrefix("/storage/spaces/"),
            Charsets.UTF_8.name(),
        )
        val body = JSONObject(String(request.body!!, Charsets.UTF_8))
        records[id] = JSONObject(String(SyncCrypto.decryptBSO(body.getString("payload"), keys), Charsets.UTF_8))
        bump()
        return SyncHttpResponse(200, emptyMap(), lastModified.toByteArray(Charsets.UTF_8))
    }

    private fun cryptoGet(): SyncHttpResponse {
        val cleartext = cryptoKeys ?: return SyncHttpResponse(404, emptyMap(), ByteArray(0))
        val payload = SyncCrypto.encryptBSO(cleartext.toString().toByteArray(Charsets.UTF_8), syncKeys)
        val body = JSONObject().put("id", "crypto/keys").put("payload", payload)
        return SyncHttpResponse(
            200,
            mapOf("x-last-modified" to cryptoKeysLastModified),
            body.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun cryptoPut(request: SyncHttpRequest): SyncHttpResponse {
        val condition = request.header("X-If-Unmodified-Since")
        if (condition != null && cryptoKeys != null) {
            return SyncHttpResponse(412, mapOf("x-last-modified" to cryptoKeysLastModified), ByteArray(0))
        }
        val body = JSONObject(String(request.body!!, Charsets.UTF_8))
        cryptoKeys = JSONObject(String(SyncCrypto.decryptBSO(body.getString("payload"), syncKeys), Charsets.UTF_8))
        clock++
        cryptoKeysLastModified = "$clock.00"
        return SyncHttpResponse(200, emptyMap(), cryptoKeysLastModified.toByteArray(Charsets.UTF_8))
    }

    // MARK: Test knobs

    /** Forces per-record POST failures: id -> server reason. */
    var forcedFailed: Map<String, String> = emptyMap()

    /** When set, collection GETs are served one record at a time. */
    var pageSize: Int? = null

    /** Conditional (page >= 2) GETs answered with 412 this many times. */
    var midReadConflictsRemaining: Int = 0

    private fun bump() {
        clock++
        lastModified = "$clock.00"
    }
}
