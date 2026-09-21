package de.kjell.zencompanion

import android.content.Context
import android.content.ContextWrapper
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.DemoCatalog
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.UrlConnectionTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests (no Robolectric): AccountStore's persisted stores are
 * replaced by in-memory fakes through the internal factory seams.
 */
class AccountStoreTests {
    private val context: Context = ContextWrapper(null)

    private lateinit var secure: FakeAccountPrefsStore
    private lateinit var legacy: FakeLegacyPlainStore

    @Before
    fun setUp() {
        AccountStore.resetForTests()
        secure = FakeAccountPrefsStore()
        legacy = FakeLegacyPlainStore()
        AccountStore.secureStoreFactory = { secure }
        AccountStore.legacyStoreFactory = { legacy }
    }

    @After
    fun tearDown() {
        AccountStore.resetForTests()
        AccountStore.restoreDefaultFactoriesForTests()
    }

    private fun snapshot(uid: String = "u1") = AccountStore.AccountSnapshot(
        email = "a@b.c",
        uid = uid,
        sessionTokenHex = "aa",
        kBHex = "bb",
    )

    @Test
    fun saveLoadRoundTrip() {
        val snap = snapshot()

        AccountStore.save(context, snap)
        AccountStore.resetForTests()

        val loaded = AccountStore.load(context)
        assertEquals(snap.uid, loaded?.uid)
        assertEquals(snap.sessionTokenHex, loaded?.sessionTokenHex)
    }

    @Test
    fun isSignedInTrueWhenOnlyPersistedDataExistsAfterReset() {
        AccountStore.save(context, snapshot())

        AccountStore.resetForTests()

        assertTrue(AccountStore.isSignedIn(context))
    }

    @Test
    fun isSignedInFalseWithNoData() {
        assertFalse(AccountStore.isSignedIn(context))
    }

    @Test
    fun clearRemovesPersistedSnapshotWhenInstanceWasNull() {
        AccountStore.save(context, snapshot())
        AccountStore.resetForTests()

        AccountStore.clear(context)

        assertNull(secure.read("fxa"))
        assertNull(AccountStore.load(context))
        assertFalse(AccountStore.isSignedIn(context))
    }

    @Test
    fun legacyPlainMigratesToEncryptedThenDeleted() {
        val snap = snapshot()
        legacy.values["fxa"] = snap.toJSON().toString()

        val loaded = AccountStore.load(context)

        assertEquals(snap.uid, loaded?.uid)
        assertEquals(1, secure.writeCount)
        assertEquals(snap.uid, secure.read("fxa")?.let { AccountStore.AccountSnapshot.fromJSON(JSONObject(it))?.uid })
        assertTrue(legacy.values.isEmpty())
    }

    @Test
    fun legacyPlainDeletedAndReturnedInMemoryWhenSecureStoreUnavailable() {
        AccountStore.secureStoreFactory = { null }
        val snap = snapshot()
        legacy.values["fxa"] = snap.toJSON().toString()

        val loaded = AccountStore.load(context)

        assertEquals(snap.uid, loaded?.uid)
        assertTrue(legacy.values.isEmpty())
        assertTrue(AccountStore.isSignedIn(context))
        assertEquals(0, secure.writeCount)
    }

    @Test
    fun saveNeverTouchesPlainStore() {
        AccountStore.save(context, snapshot())

        assertEquals(0, legacy.readCount)
        assertEquals(0, legacy.deleteCount)
        assertTrue(legacy.values.isEmpty())
    }

    @Test
    fun malformedStoredJsonReturnsNullInsteadOfThrowing() {
        secure.values["fxa"] = "{not valid json"

        assertNull(AccountStore.load(context))
    }

    @Test
    fun storedJsonMissingFieldsReturnsNull() {
        secure.values["fxa"] = """{"email":"a@b.c"}"""

        assertNull(AccountStore.load(context))
    }

    @Test
    fun snapshotJsonCodecDefaultsDemoToFalse() {
        val snap = snapshot()

        val decoded = AccountStore.AccountSnapshot.fromJSON(snap.toJSON())

        assertEquals(snap.email, decoded?.email)
        assertEquals(false, decoded?.isDemo)
    }

    @Test
    fun demoSnapshotRoundTripsAsDemo() {
        val decoded = AccountStore.AccountSnapshot.fromJSON(DemoCatalog.account.toJSON())

        assertTrue(decoded?.isDemo == true)
        assertEquals("demo", decoded?.uid)
    }

    @Test
    fun transportSeamsKeepPreStage2aTimeouts() {
        val auth = AccountStore.authTransport as UrlConnectionTransport
        val sync = AccountStore.syncTransport as UrlConnectionTransport

        assertEquals(30_000, privateInt(auth, "readTimeoutMs"))
        assertEquals(60_000, privateInt(sync, "readTimeoutMs"))
    }

    private fun privateInt(target: Any, field: String): Int {
        val declared = target.javaClass.getDeclaredField(field)
        declared.isAccessible = true
        return declared.getInt(target)
    }

    @Test
    fun saveSignalsStorageUnavailableWhenSecureStoreMissing() {
        AccountStore.secureStoreFactory = { null }

        val thrown = runCatching { AccountStore.save(context, snapshot()) }.exceptionOrNull()

        assertTrue(thrown is SyncError.StorageUnavailable)
        assertNull(AccountStore.load(context))
        assertFalse(AccountStore.isSignedIn(context))
    }

    @Test
    fun saveSignalsStorageUnavailableWhenWriteFails() {
        secure.failWrites = true

        val thrown = runCatching { AccountStore.save(context, snapshot()) }.exceptionOrNull()

        assertTrue(thrown is SyncError.StorageUnavailable)
        assertFalse(AccountStore.isSignedIn(context))
        assertTrue(legacy.values.isEmpty())
        assertEquals(1, secure.writeCount)
        assertEquals(1, legacy.readCount)
    }

    @Test
    fun isDemoAndConnectBehaviorParity() {
        assertFalse(AccountStore.isDemo(context))

        AccountStore.save(context, DemoCatalog.account)
        AccountStore.resetForTests()

        assertTrue(AccountStore.isDemo(context))
        try {
            runBlocking { AccountStore.connect(context) }
            fail("connect should reject demo accounts")
        } catch (expected: SyncError.NotSignedIn) {
            // Expected: demo accounts never hit the network.
        }
    }
}

private class FakeAccountPrefsStore : AccountStore.AccountPrefsStore {
    val values = mutableMapOf<String, String>()
    var writeCount = 0
    var removeCount = 0
    var failWrites = false

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String): Boolean {
        writeCount++
        if (failWrites) return false
        values[key] = value
        return true
    }

    override fun remove(key: String) {
        removeCount++
        values.remove(key)
    }
}

private class FakeLegacyPlainStore : AccountStore.LegacyPlainStore {
    val values = mutableMapOf<String, String>()
    var readCount = 0
    var deleteCount = 0

    override fun read(key: String): String? {
        readCount++
        return values[key]
    }

    override fun delete() {
        deleteCount++
        values.clear()
    }
}
