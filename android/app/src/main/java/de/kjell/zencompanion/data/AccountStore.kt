package de.kjell.zencompanion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.kjell.zencompanion.sync.FxAClient
import de.kjell.zencompanion.sync.FxACrypto
import de.kjell.zencompanion.sync.SyncClient
import de.kjell.zencompanion.sync.SyncError
import de.kjell.zencompanion.sync.SyncHttpTransport
import de.kjell.zencompanion.sync.TokenServerCreds
import de.kjell.zencompanion.sync.UrlConnectionTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/**
 * Port of `Shared/AccountStore.swift`. Secrets live exclusively in
 * EncryptedSharedPreferences backed by the Android Keystore — the Android
 * equivalent of Keychain `AfterFirstUnlockThisDeviceOnly`. Token-server
 * credentials stay memory-only and refresh 45s before expiry.
 *
 * Plaintext SharedPreferences are never written. A legacy
 * `zen_secure_prefs_plain.xml` is imported once into the encrypted store and
 * deleted immediately; if the secure store is unavailable it is deleted just
 * the same and only held in memory for the current process.
 */
object AccountStore {
    const val PREFS_NAME = "zen_secure_prefs"
    private const val LEGACY_PREFS_NAME = "zen_secure_prefs_plain"
    private const val KEY_ACCOUNT = "fxa"

    /** Test seam over the encrypted preferences. */
    internal interface AccountPrefsStore {
        fun read(key: String): String?
        fun write(key: String, value: String): Boolean
        fun remove(key: String)
    }

    /** Test seam over the legacy plaintext preferences. */
    internal interface LegacyPlainStore {
        fun read(key: String): String?
        fun delete()
    }

    private class SharedPrefsStore(private val prefs: SharedPreferences) : AccountPrefsStore {
        override fun read(key: String): String? = prefs.getString(key, null)

        override fun write(key: String, value: String): Boolean =
            prefs.edit().putString(key, value).commit()

        override fun remove(key: String) {
            prefs.edit().remove(key).commit()
        }
    }

    private class PlainLegacyStore(private val context: Context) : LegacyPlainStore {
        private fun prefs(): SharedPreferences =
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(key: String): String? = prefs().getString(key, null)

        override fun delete() {
            runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
            runCatching { legacyPrefsFile(context).delete() }
        }
    }

    /** Injectable factories; production defaults resolve the real stores. */
    internal var secureStoreFactory: (Context) -> AccountPrefsStore? = { securePrefsOrNull(it) }
    internal var legacyStoreFactory: (Context) -> LegacyPlainStore = { PlainLegacyStore(it) }

    /** Injectable FxA/token-server transport: 30s read timeout as before stage 2a. */
    internal var authTransport: SyncHttpTransport = UrlConnectionTransport(readTimeoutMs = 30_000)

    /** Injectable Sync storage transport: 60s read timeout as before stage 2a. */
    internal var syncTransport: SyncHttpTransport = UrlConnectionTransport()

    @Volatile
    private var cachedSnapshot: AccountSnapshot? = null

    @Volatile
    private var cachedCreds: TokenServerCreds? = null

    @Volatile
    private var secureStore: AccountPrefsStore? = null

    @Volatile
    private var secureStoreResolved = false

    private val credsMutex = Mutex()

    class AccountSnapshot(
        val email: String,
        val uid: String,
        val sessionTokenHex: String,
        val kBHex: String,
        val isDemo: Boolean = false,
    ) {
        fun toJSON(): JSONObject = JSONObject()
            .put("email", email)
            .put("uid", uid)
            .put("sessionTokenHex", sessionTokenHex)
            .put("kBHex", kBHex)
            .put("isDemo", isDemo)

        companion object {
            fun fromJSON(obj: JSONObject): AccountSnapshot? = runCatching {
                val email = obj.getString("email")
                val uid = obj.getString("uid")
                val sessionTokenHex = obj.getString("sessionTokenHex")
                val kBHex = obj.getString("kBHex")
                val isDemo = obj.optBoolean("isDemo", false)
                AccountSnapshot(email, uid, sessionTokenHex, kBHex, isDemo)
            }.getOrNull()
        }
    }

    /**
     * Clears all in-memory state to simulate a fresh process. Injected test
     * factories are intentionally kept so their backing stores stay reachable.
     */
    internal fun resetForTests() {
        synchronized(this) {
            secureStore = null
            secureStoreResolved = false
            cachedSnapshot = null
            cachedCreds = null
        }
    }

    /**
     * Restores the production store factories. Test classes that inject fakes
     * must call this in tearDown so state cannot leak into other test classes.
     */
    internal fun restoreDefaultFactoriesForTests() {
        secureStoreFactory = { securePrefsOrNull(it) }
        legacyStoreFactory = { PlainLegacyStore(it) }
    }

    private fun securePrefs(context: Context): AccountPrefsStore? {
        secureStore?.let { return it }
        synchronized(this) {
            secureStore?.let { return it }
            if (secureStoreResolved) return null
            secureStoreResolved = true
            val store = runCatching { secureStoreFactory(context) }.getOrNull()
            secureStore = store
            return store
        }
    }

    private fun legacyStore(context: Context): LegacyPlainStore =
        runCatching { legacyStoreFactory(context) }.getOrElse { PlainLegacyStore(context) }

    /**
     * Fail-soft on broken device keystores (seen on old API 26-28 devices):
     * deletes the unrecoverable prefs file and master key once and retries; if
     * it still fails, logs once and disables secure storage. Plaintext
     * preferences are never used as a fallback.
     */
    private fun securePrefsOrNull(context: Context): AccountPrefsStore? {
        runCatching { SharedPrefsStore(encryptedPrefs(context)) }.getOrNull()?.let { return it }

        runCatching { prefsFile(context).delete() }
        runCatching { deleteMasterKeyAlias() }

        runCatching { SharedPrefsStore(encryptedPrefs(context)) }.getOrNull()?.let { return it }

        android.util.Log.e(
            "AccountStore",
            "EncryptedSharedPreferences unavailable; secure account storage disabled",
        )
        return null
    }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun deleteMasterKeyAlias() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    private fun prefsFile(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")

    private fun legacyPrefsFile(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")

    /**
     * Persists to the encrypted store; never touches the legacy plain store.
     * Throws [SyncError.StorageUnavailable] when the secret cannot be written,
     * mirroring `AccountStore.save` on iOS.
     */
    fun save(context: Context, snapshot: AccountSnapshot) {
        val store = securePrefs(context)
        if (store == null) {
            android.util.Log.e("AccountStore", "Secure storage unavailable; account not persisted")
            throw SyncError.StorageUnavailable
        }
        val persisted = runCatching {
            store.write(KEY_ACCOUNT, snapshot.toJSON().toString())
        }.getOrDefault(false)
        if (!persisted) {
            android.util.Log.e("AccountStore", "Failed to persist account snapshot")
            throw SyncError.StorageUnavailable
        }
        synchronized(this) { cachedSnapshot = snapshot }
    }

    fun load(context: Context): AccountSnapshot? {
        cachedSnapshot?.let { return it }

        val store = securePrefs(context)
        if (store != null) {
            val raw = runCatching { store.read(KEY_ACCOUNT) }.getOrNull()
            if (raw != null) {
                val snapshot = decode(raw) ?: return null
                synchronized(this) { cachedSnapshot = snapshot }
                runCatching { legacyStore(context).delete() }
                return snapshot
            }
            return migrateLegacy(context, store)
        }

        // Secure storage unavailable: keep the plaintext snapshot in memory for
        // this process only, delete the file immediately and never persist it.
        val legacy = legacyStore(context)
        val raw = runCatching { legacy.read(KEY_ACCOUNT) }.getOrNull()
        runCatching { legacy.delete() }
        val snapshot = raw?.let(::decode) ?: return null
        synchronized(this) { cachedSnapshot = snapshot }
        return snapshot
    }

    /**
     * One-time import of `zen_secure_prefs_plain.xml` into the encrypted
     * store. The plaintext file is deleted whether or not the write succeeds.
     */
    private fun migrateLegacy(context: Context, store: AccountPrefsStore): AccountSnapshot? {
        val legacy = legacyStore(context)
        val raw = runCatching { legacy.read(KEY_ACCOUNT) }.getOrNull() ?: return null
        val snapshot = decode(raw) ?: run {
            runCatching { legacy.delete() }
            return null
        }
        val persisted = runCatching {
            store.write(KEY_ACCOUNT, snapshot.toJSON().toString())
        }.getOrDefault(false)
        runCatching { legacy.delete() }
        if (!persisted) return null
        synchronized(this) { cachedSnapshot = snapshot }
        return snapshot
    }

    private fun decode(raw: String): AccountSnapshot? =
        runCatching { AccountSnapshot.fromJSON(JSONObject(raw)) }.getOrNull()

    fun clear(context: Context) {
        synchronized(this) {
            cachedSnapshot = null
            cachedCreds = null
        }
        // Always remove the persisted key, even when no instance was created
        // in this process yet.
        runCatching { securePrefs(context)?.remove(KEY_ACCOUNT) }
        runCatching { legacyStore(context).delete() }
    }

    /** Persisted truth: reads the encrypted store, not just the memory cache. */
    fun isSignedIn(context: Context): Boolean = load(context) != null

    fun isDemo(context: Context): Boolean = load(context)?.isDemo == true

    /** Re-creates a SyncClient with fresh token-server credentials as needed. */
    suspend fun connect(context: Context): SyncClient = withContext(Dispatchers.IO) {
        // Blocking HttpURLConnection work (FxA credential refresh, crypto/keys
        // fetch) must never run on the caller's Main dispatcher.
        val account = load(context)?.takeUnless { it.isDemo } ?: throw SyncError.NotSignedIn
        val kB = FxACrypto.unhex(account.kBHex)

        val existing = credsMutex.withLock { cachedCreds?.takeIf { it.expiresAtMillis > System.currentTimeMillis() + 45_000 } }
        if (existing != null) {
            return@withContext SyncClient(creds = existing, kB = kB, transport = syncTransport)
        }

        val fxa = FxAClient(transport = authTransport)
        val creds = fxa.syncCredentials(sessionToken = account.sessionTokenHex, kB = kB)
        credsMutex.withLock { cachedCreds = creds }
        SyncClient(creds = creds, kB = kB, transport = syncTransport)
    }
}
